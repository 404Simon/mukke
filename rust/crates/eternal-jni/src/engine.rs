//! Sample-accurate renderer for an eternal playback walk.
//!
//! The renderer keeps one decoded PCM buffer alive and cuts beats out of it.
//! JNI calls in [`crate::jni`] wrap this engine in a mutex and translate
//! failures into Java exceptions.

use std::{
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicI64, AtomicU64, AtomicUsize, Ordering},
    },
};

use eternal_core::{
    AnalysisConfig, Audio, BranchConfig, BranchGraph, PlaybackPlanner, Step, analyse, decode,
};

/// Duration of the linear fades applied on either side of a jump.
const FADE_MILLIS: u32 = 2;

/// Number of steps that can be planned ahead of the audio loop. Typical
/// Android chunks (1024 frames) need at most one beat; the margin covers
/// very short beats and long render requests without allocating.
const LOOKAHEAD_STEPS: usize = 64;

pub(crate) type EngineResult<T> = Result<T, String>;

/// Lock-free playback progress published by the render thread.
pub(crate) struct Status {
    source_position_ms: AtomicU64,
    current_beat: AtomicUsize,
    jump_count: AtomicI64,
}

impl Status {
    fn new() -> Self {
        Self {
            source_position_ms: AtomicU64::new(0),
            current_beat: AtomicUsize::new(0),
            jump_count: AtomicI64::new(0),
        }
    }

    pub(crate) fn source_position_ms(&self) -> u64 {
        self.source_position_ms.load(Ordering::Relaxed)
    }

    pub(crate) fn current_beat(&self) -> usize {
        self.current_beat.load(Ordering::Relaxed)
    }

    pub(crate) fn jump_count(&self) -> i64 {
        self.jump_count.load(Ordering::Relaxed)
    }

    fn record_jump(&self) {
        self.jump_count.fetch_add(1, Ordering::Relaxed);
    }
}

/// Half-open frame range `[start, end)` of one beat in the decoded audio.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct FrameSpan {
    start: usize,
    end: usize,
}

impl FrameSpan {
    fn len(self) -> usize {
        self.end - self.start
    }
}

/// The beat currently being rendered plus its playback cursor.
struct ActiveBeat {
    step: Step,
    span: FrameSpan,
    cursor: usize,
}

impl ActiveBeat {
    fn new(step: Step, span: FrameSpan) -> Self {
        Self {
            step,
            span,
            cursor: span.start,
        }
    }

    fn remaining(&self) -> usize {
        self.span.end.saturating_sub(self.cursor)
    }
}

/// Fixed-capacity FIFO of planned steps, so rendering never allocates.
struct StepQueue {
    slots: [Option<Step>; LOOKAHEAD_STEPS],
    head: usize,
    len: usize,
}

impl StepQueue {
    fn new() -> Self {
        Self {
            slots: std::array::from_fn(|_| None),
            head: 0,
            len: 0,
        }
    }

    fn len(&self) -> usize {
        self.len
    }

    fn is_empty(&self) -> bool {
        self.len == 0
    }

    fn push(&mut self, step: Step) {
        debug_assert!(self.len < LOOKAHEAD_STEPS);
        let index = (self.head + self.len) % LOOKAHEAD_STEPS;
        self.slots[index] = Some(step);
        self.len += 1;
    }

    fn pop(&mut self) -> Option<Step> {
        if self.len == 0 {
            return None;
        }
        let step = self.slots[self.head].take();
        self.head = (self.head + 1) % LOOKAHEAD_STEPS;
        self.len -= 1;
        step
    }

    fn peek(&self) -> Option<&Step> {
        if self.len == 0 {
            None
        } else {
            self.slots[self.head].as_ref()
        }
    }

    fn get(&self, index: usize) -> Option<&Step> {
        if index >= self.len {
            return None;
        }
        self.slots[(self.head + index) % LOOKAHEAD_STEPS].as_ref()
    }

    fn clear(&mut self) {
        while self.pop().is_some() {}
    }
}

/// One prepared eternal playback session.
pub(crate) struct Engine {
    audio: Audio,
    spans: Vec<FrameSpan>,
    branches: Vec<(usize, usize)>,
    planner: PlaybackPlanner,
    current: ActiveBeat,
    upcoming: StepQueue,
    status: Arc<Status>,
}

impl Engine {
    /// Decode, analyse, and prepare a file for eternal playback.
    pub(crate) fn prepare(path: &Path, start_seconds: f64, seed: u64) -> EngineResult<Self> {
        let audio = decode(path)
            .map_err(|error| format!("could not decode {}: {error}", path.display()))?;
        Self::from_audio(audio, start_seconds, seed)
    }

    /// Prepare an already decoded track. Channel reduction happens here once;
    /// rendering works directly on the resulting buffer.
    fn from_audio(audio: Audio, start_seconds: f64, seed: u64) -> EngineResult<Self> {
        if audio.samples.is_empty() {
            return Err("the decoded audio contains no samples".to_string());
        }
        if audio.sample_rate == 0 || audio.channels == 0 {
            return Err("the decoded audio has no sample-rate or channel information".to_string());
        }
        if !start_seconds.is_finite() {
            return Err("the start position must be finite".to_string());
        }

        let audio = normalise_channels(audio);
        let analysis = analyse(&audio, &AnalysisConfig::default())
            .map_err(|error| format!("could not analyse the audio: {error}"))?;
        if analysis.beats.is_empty() {
            return Err("no beats were detected in the audio".to_string());
        }

        let graph = BranchGraph::build(&analysis, &BranchConfig::default());
        // The graph is immutable after analysis. Keep a flat edge list so the Android UI can
        // draw the possible transitions without another round-trip through the decoder.
        let branches = graph
            .branches
            .iter()
            .enumerate()
            .flat_map(|(source, targets)| {
                targets
                    .iter()
                    .map(move |target| (source, target.destination))
            })
            .collect::<Vec<_>>();
        let mut planner = PlaybackPlanner::with_seed(graph, seed);
        let spans: Vec<FrameSpan> = analysis
            .beats
            .iter()
            .map(|beat| {
                frame_span(
                    beat.start,
                    beat.duration,
                    audio.sample_rate,
                    audio.samples.len() / usize::from(audio.channels),
                )
            })
            .collect();

        let initial_beat = beat_at(&analysis.beats, start_seconds);
        if !planner.continue_from(initial_beat) {
            return Err("the playback graph does not contain the starting beat".to_string());
        }

        let status = Arc::new(Status::new());
        let current = ActiveBeat::new(step_for(initial_beat), spans[initial_beat]);
        let mut engine = Self {
            audio,
            spans,
            branches,
            planner,
            current,
            upcoming: StepQueue::new(),
            status,
        };
        engine.prefetch(0);
        engine.publish_status();
        Ok(engine)
    }

    pub(crate) fn status(&self) -> &Arc<Status> {
        &self.status
    }

    pub(crate) fn sample_rate(&self) -> u32 {
        self.audio.sample_rate
    }

    pub(crate) fn channels(&self) -> u16 {
        self.audio.channels
    }

    pub(crate) fn duration_seconds(&self) -> f64 {
        self.audio.duration_seconds()
    }

    pub(crate) fn beat_count(&self) -> usize {
        self.spans.len()
    }

    /// Flat `(source, destination)` beat pairs for the visualization.
    pub(crate) fn branches(&self) -> &[(usize, usize)] {
        &self.branches
    }

    /// Fill `frames` frames of interleaved audio. `output` must hold exactly
    /// `frames * channels` samples.
    pub(crate) fn render(&mut self, output: &mut [f32], frames: usize) -> usize {
        debug_assert_eq!(output.len(), frames * usize::from(self.audio.channels));
        let channels = usize::from(self.audio.channels);
        let mut written = 0;
        while written < frames {
            self.prefetch(frames - written);
            let produced = self.render_segment(&mut output[written * channels..], frames - written);
            if produced == 0 {
                // Pathological spans (all beats empty) must not spin forever.
                output[written * channels..].fill(0.0);
                break;
            }
            written += produced;
        }
        self.publish_status();
        frames
    }

    /// Reposition the walk at the beat containing `seconds` without resetting
    /// the planner's novelty state.
    pub(crate) fn seek_seconds(&mut self, seconds: f64) -> EngineResult<()> {
        if !seconds.is_finite() {
            return Err("the seek position must be finite".to_string());
        }
        let beat = self.beat_index_at(seconds);
        if !self.planner.continue_from(beat) {
            return Err("the requested seek position is outside the playback graph".to_string());
        }
        self.current = ActiveBeat::new(step_for(beat), self.spans[beat]);
        self.upcoming.clear();
        self.prefetch(0);
        self.publish_status();
        Ok(())
    }

    /// Plan enough steps for the requested chunk plus one successor, so the
    /// sample loop only promotes pre-planned beats and never touches the RNG.
    fn prefetch(&mut self, frames: usize) {
        let mut covered = self.current.remaining();
        let mut needed = 0;
        while covered < frames && needed < self.upcoming.len() {
            let step = self
                .upcoming
                .get(needed)
                .expect("queue index is within bounds");
            covered += self.spans[step.beat].len();
            needed += 1;
        }
        while covered < frames && self.upcoming.len() < LOOKAHEAD_STEPS {
            let Some(step) = self.planner.next_step() else {
                break;
            };
            covered += self.spans[step.beat].len();
            self.upcoming.push(step);
            needed += 1;
        }
        // The chunk may end inside the last planned beat; its successor decides
        // whether that beat fades out.
        while self.upcoming.len() <= needed && self.upcoming.len() < LOOKAHEAD_STEPS {
            let Some(step) = self.planner.next_step() else {
                break;
            };
            self.upcoming.push(step);
        }
    }

    /// Render without touching the planner. Returns the frames written, which
    /// is zero only when no renderable span is queued.
    fn render_segment(&mut self, output: &mut [f32], frames: usize) -> usize {
        let channels = usize::from(self.audio.channels);
        let fade_frames = (self.audio.sample_rate as usize * FADE_MILLIS as usize) / 1_000;
        let mut written = 0;

        while written < frames {
            if self.current.cursor >= self.current.span.end && !self.promote() {
                break;
            }
            // The next step decides whether this beat ends in a fade-out.
            if self.upcoming.is_empty() {
                break;
            }

            let offset = self.current.cursor - self.current.span.start;
            let span_frames = self.current.span.len();
            let take = (frames - written).min(span_frames - offset);
            let beats_fade_frames = fade_frames.min(span_frames / 2);
            let fade_in = self.current.step.jumped_from.is_some();
            let fade_out = self
                .upcoming
                .peek()
                .is_some_and(|step| step.jumped_from.is_some());

            let source_start = self.current.cursor * channels;
            let destination_start = written * channels;
            let destination = &mut output[destination_start..destination_start + take * channels];
            if fade_in || fade_out {
                for frame in 0..take {
                    let position = offset + frame;
                    let mut gain = 1.0;
                    if fade_in && position < beats_fade_frames {
                        gain *= position as f32 / beats_fade_frames.max(1) as f32;
                    }
                    if fade_out && span_frames - position - 1 < beats_fade_frames {
                        gain *=
                            (span_frames - position - 1) as f32 / beats_fade_frames.max(1) as f32;
                    }
                    let source = source_start + frame * channels;
                    let destination = &mut destination[frame * channels..(frame + 1) * channels];
                    for (sample, output) in self.audio.samples[source..source + channels]
                        .iter()
                        .zip(destination)
                    {
                        *output = sample * gain;
                    }
                }
            } else {
                destination.copy_from_slice(
                    &self.audio.samples[source_start..source_start + take * channels],
                );
            }

            self.current.cursor += take;
            written += take;
        }
        written
    }

    /// Move to the pre-fetched step. The caller must have checked the queue.
    fn promote(&mut self) -> bool {
        let Some(step) = self.upcoming.pop() else {
            return false;
        };
        if step.jumped_from.is_some() {
            self.status.record_jump();
        }
        let span = self.spans[step.beat];
        self.current = ActiveBeat::new(step, span);
        true
    }

    fn beat_index_at(&self, seconds: f64) -> usize {
        beat_at_from_spans(&self.spans, self.audio.sample_rate, seconds)
    }

    fn publish_status(&self) {
        let frames = self.current.cursor as u64;
        let milliseconds = frames.saturating_mul(1_000) / u64::from(self.audio.sample_rate);
        self.status
            .source_position_ms
            .store(milliseconds, Ordering::Relaxed);
        self.status
            .current_beat
            .store(self.current.step.beat, Ordering::Relaxed);
    }
}

fn step_for(beat: usize) -> Step {
    Step {
        beat,
        jumped_from: None,
    }
}

/// Frame range for a beat, clamped to the decoded buffer.
fn frame_span(start: f64, duration: f64, sample_rate: u32, total_frames: usize) -> FrameSpan {
    let rate = f64::from(sample_rate);
    let start_frame = ((start * rate).round() as usize).min(total_frames);
    let end_frame = (((start + duration) * rate).round() as usize).clamp(start_frame, total_frames);
    FrameSpan {
        start: start_frame,
        end: end_frame,
    }
}

fn beat_at(beats: &[eternal_core::Beat], seconds: f64) -> usize {
    if seconds <= beats[0].start {
        return 0;
    }
    for (index, beat) in beats.iter().enumerate() {
        if seconds < beat.start + beat.duration {
            return index;
        }
    }
    beats.len() - 1
}

fn beat_at_from_spans(spans: &[FrameSpan], sample_rate: u32, seconds: f64) -> usize {
    if seconds <= 0.0 {
        return 0;
    }
    let target = (seconds * f64::from(sample_rate)).round() as usize;
    spans
        .partition_point(|span| span.start <= target)
        .saturating_sub(1)
        .min(spans.len() - 1)
}

/// Collapse more than two channels to stereo. Mono and stereo are returned
/// unchanged, so their PCM is never copied.
fn normalise_channels(audio: Audio) -> Audio {
    let channels = usize::from(audio.channels);
    if channels <= 2 {
        return audio;
    }
    let samples = downmix_to_stereo(&audio.samples, channels);
    Audio::new(samples, audio.sample_rate, 2)
}

/// Average even channels into the left side and odd channels into the right.
/// A side without channels duplicates the other one.
fn downmix_to_stereo(samples: &[f32], channels: usize) -> Arc<[f32]> {
    debug_assert!(channels > 2);
    let mut output = Vec::with_capacity(samples.len() / channels * 2);
    for frame in samples.chunks_exact(channels) {
        let mut left = 0.0;
        let mut left_channels = 0;
        let mut right = 0.0;
        let mut right_channels = 0;
        for (index, sample) in frame.iter().enumerate() {
            if index % 2 == 0 {
                left += sample;
                left_channels += 1;
            } else {
                right += sample;
                right_channels += 1;
            }
        }
        let right = if right_channels == 0 {
            left / left_channels as f32
        } else {
            right / right_channels as f32
        };
        let left = if left_channels == 0 {
            right
        } else {
            left / left_channels as f32
        };
        output.extend_from_slice(&[left, right]);
    }
    output.into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        path::{Path, PathBuf},
        time::{SystemTime, UNIX_EPOCH},
    };

    const TEST_SAMPLE_RATE: u32 = 48_000;

    /// A decaying 440 Hz burst every half second gives the analyzer clear beats without
    /// committing an audio fixture.
    fn beat_sample(seconds: f32) -> f32 {
        let phase = seconds % 0.5;
        let envelope = (-phase * 18.0).exp();
        (std::f32::consts::TAU * 440.0 * phase).sin() * envelope * 0.25
    }

    fn test_audio(seconds: f64) -> Audio {
        let frames = (seconds * f64::from(TEST_SAMPLE_RATE)) as usize;
        let mut samples = Vec::with_capacity(frames * 2);
        for frame in 0..frames {
            let sample = beat_sample(frame as f32 / TEST_SAMPLE_RATE as f32);
            samples.extend_from_slice(&[sample, sample]);
        }
        Audio::new(samples, TEST_SAMPLE_RATE, 2)
    }

    fn temp_wav(name: &str, seconds: f64) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock is after the Unix epoch")
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "eternal-jni-{}-{name}-{nonce}.wav",
            std::process::id()
        ));
        write_wav(&path, seconds);
        path
    }

    fn write_wav(path: &Path, seconds: f64) {
        const CHANNELS: u16 = 2;
        let frames = (seconds * f64::from(TEST_SAMPLE_RATE)) as u32;
        let data_len = frames * u32::from(CHANNELS) * 2;
        let mut bytes = Vec::with_capacity(44 + data_len as usize);
        bytes.extend_from_slice(b"RIFF");
        bytes.extend_from_slice(&(36 + data_len).to_le_bytes());
        bytes.extend_from_slice(b"WAVEfmt ");
        bytes.extend_from_slice(&16u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&CHANNELS.to_le_bytes());
        bytes.extend_from_slice(&TEST_SAMPLE_RATE.to_le_bytes());
        bytes.extend_from_slice(&(TEST_SAMPLE_RATE * u32::from(CHANNELS) * 2).to_le_bytes());
        bytes.extend_from_slice(&(CHANNELS * 2).to_le_bytes());
        bytes.extend_from_slice(&16u16.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&data_len.to_le_bytes());
        for frame in 0..frames {
            let sample = beat_sample(frame as f32 / TEST_SAMPLE_RATE as f32);
            let pcm = (sample.clamp(-1.0, 1.0) * f32::from(i16::MAX)) as i16;
            bytes.extend_from_slice(&pcm.to_le_bytes());
            bytes.extend_from_slice(&pcm.to_le_bytes());
        }
        fs::write(path, bytes).expect("write wav fixture");
    }

    fn prepared() -> Engine {
        Engine::from_audio(test_audio(8.0), 0.0, 42).expect("engine prepares")
    }

    #[test]
    fn decodes_a_generated_wav() {
        let path = temp_wav("decode", 0.2);
        let audio = decode(&path).expect("wav decodes");
        assert_eq!(audio.sample_rate, TEST_SAMPLE_RATE);
        assert_eq!(audio.channels, 2);
        assert!(!audio.samples.is_empty());
        assert!(audio.duration_seconds() > 0.1);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn short_and_missing_files_are_rejected() {
        let path = temp_wav("short", 0.2);
        let short = Engine::prepare(&path, 0.0, 1)
            .err()
            .expect("fixture is too short");
        assert!(short.contains("too short"), "{short}");
        let _ = fs::remove_file(path);

        let missing = Engine::prepare(Path::new("/does/not/exist.mp3"), 0.0, 1)
            .err()
            .expect("missing file must fail");
        assert!(missing.contains("could not decode"), "{missing}");
    }

    #[test]
    fn exposes_a_valid_branch_map() {
        let engine = prepared();
        assert!(engine.beat_count() > 1);
        for &(source, destination) in engine.branches() {
            assert!(source < engine.beat_count());
            assert!(destination < engine.beat_count());
        }
    }

    #[test]
    fn prepares_and_renders_non_silent_audio() {
        let mut engine = prepared();
        let channels = usize::from(engine.channels());
        let frames = 1_024;
        let mut buffer = vec![0.0; frames * channels];

        for _ in 0..8 {
            assert_eq!(engine.render(&mut buffer, frames), frames);
            assert!(
                buffer.iter().any(|sample| sample.abs() > 1e-6),
                "rendered buffer is silent"
            );
        }

        let position = engine.status().source_position_ms();
        assert!(position <= (engine.duration_seconds() * 1_000.0) as u64);
        assert!(position > 0);
        assert!(engine.status().current_beat() < engine.spans.len());
    }

    #[test]
    fn seek_repositions_and_keeps_rendering() {
        let mut engine = prepared();
        let channel_count = usize::from(engine.channels());
        let target = engine.duration_seconds() / 2.0;
        engine.seek_seconds(target).expect("seek succeeds");

        let expected = engine.beat_index_at(target);
        assert_eq!(engine.status().current_beat(), expected);
        assert!((engine.status().source_position_ms() as f64) <= target * 1_000.0);

        let start_ms = engine.status().source_position_ms();
        let frames = 512;
        let mut buffer = vec![0.0; frames * channel_count];
        assert_eq!(engine.render(&mut buffer, frames), frames);
        assert!(buffer.iter().any(|sample| sample.abs() > 1e-6));

        let position = engine.status().source_position_ms();
        assert!(position >= start_ms);
        assert!(position <= (engine.duration_seconds() * 1_000.0) as u64);
    }

    #[test]
    fn renders_far_beyond_the_end_without_panicking() {
        let mut engine = prepared();
        let channels = usize::from(engine.channels());
        let frames = 512;
        let mut buffer = vec![0.0; frames * channels];
        let duration_ms = (engine.duration_seconds() * 1_000.0) as u64;

        // Roughly forty seconds of audio from an eight-second source.
        for _ in 0..4_000 {
            assert_eq!(engine.render(&mut buffer, frames), frames);
            assert!(engine.status().source_position_ms() <= duration_ms);
            assert!(engine.status().current_beat() < engine.spans.len());
        }
        assert!(engine.status().jump_count() > 0);
    }

    #[test]
    fn renders_a_large_chunk_in_one_call() {
        let mut engine = prepared();
        let channels = usize::from(engine.channels());
        let frames = usize::try_from(engine.sample_rate()).expect("sample rate fits") * 4;
        let mut buffer = vec![0.0; frames * channels];

        assert_eq!(engine.render(&mut buffer, frames), frames);
        assert!(buffer.iter().any(|sample| sample.abs() > 1e-6));
        assert!(
            buffer
                .chunks_exact(channels)
                .all(|frame| frame.iter().all(|sample| sample.is_finite()))
        );
        assert!(
            engine.status().source_position_ms() <= (engine.duration_seconds() * 1_000.0) as u64
        );
    }

    #[test]
    fn frame_spans_clamp_to_the_decoded_audio() {
        let span = frame_span(0.5, 1.0, 1_000, 1_200);
        assert_eq!(
            span,
            FrameSpan {
                start: 500,
                end: 1_200
            }
        );
        let past_end = frame_span(2.0, 1.0, 1_000, 1_200);
        assert_eq!(
            past_end,
            FrameSpan {
                start: 1_200,
                end: 1_200
            }
        );
        assert_eq!(past_end.len(), 0);
    }

    #[test]
    fn downmix_averages_even_and_odd_channels() {
        let samples = [
            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0,
        ];
        let stereo = downmix_to_stereo(&samples, 6);
        assert_eq!(&*stereo, &[3.0, 4.0, 30.0, 40.0]);

        let odd = downmix_to_stereo(&[1.0, 2.0, 3.0], 3);
        assert_eq!(&*odd, &[2.0, 2.0]);
    }

    #[test]
    fn mono_and_stereo_are_not_copied() {
        for channels in [1, 2] {
            let samples: Arc<[f32]> = Arc::from([0.25_f32, -0.25, 0.5, -0.5]);
            let audio = Audio::new(Arc::clone(&samples), 48_000, channels);
            let normalised = normalise_channels(audio);
            assert_eq!(normalised.channels, channels);
            assert!(Arc::ptr_eq(&normalised.samples, &samples));
        }
    }
}

package com.eddyizm.tempus.service.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.eddyizm.tempus.equalizer.ParametricEqAudioProcessor
import com.eddyizm.tempus.util.FadeAudioProcessor
import com.eddyizm.tempus.util.ReplayGainAudioProcessor

/**
 * One local ExoPlayer and the processors that belong to its audio pipeline.
 *
 * The players are interchangeable: during a crossfade the standby becomes the
 * session player and the outgoing player keeps playing only until it fades out.
 * Keeping the processors with the player means the incoming track's ReplayGain
 * and EQ travel with it instead of having to be rebuilt after the swap.
 */
@UnstableApi
class LocalPlayer(
    val player: ExoPlayer,
    val fade: FadeAudioProcessor,
    val replayGain: ReplayGainAudioProcessor,
    val parametricEq: ParametricEqAudioProcessor,
)

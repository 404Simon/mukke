package com.eddyizm.tempus.util

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a slew-limited gain inside ExoPlayer's audio pipeline.
 *
 * Volume is updated by controllers roughly every 32 ms, which is far too coarse to change the
 * gain abruptly without producing zipper noise. This processor instead moves the active gain
 * towards a target at a fixed rate, covering any change in the full 0..1 range in about 6 ms,
 * while [snapToGain] is available for changes that must be audible on the next sample.
 *
 * [onFlush] deliberately leaves the gain untouched: gapless transitions and seeks must not undo
 * a fade that is already in progress.
 */
@UnstableApi
class FadeAudioProcessor : BaseAudioProcessor() {

    @Volatile private var targetGain = 1f
    @Volatile private var activeGain = 1f
    private var gainStep = 1f / (DEFAULT_SAMPLE_RATE * RAMP_DURATION_SECONDS)

    /** Sets the gain to reach gradually. The value is clamped to 0..1. */
    fun setGain(gain: Float) {
        targetGain = gain.coerceIn(0f, 1f)
    }

    /** Sets the gain to reach and applies it immediately to the next sample. */
    fun snapToGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        targetGain = clamped
        activeGain = clamped
    }

    /** Restores immediate, unattenuated pass-through. */
    fun resetToUnity() = snapToGain(1f)

    /** The gain currently applied to incoming samples, not the pending target. */
    fun currentGain(): Float = activeGain

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        gainStep = 1f / (inputAudioFormat.sampleRate.coerceAtLeast(1) * RAMP_DURATION_SECONDS)
        return inputAudioFormat
    }

    // A flush happens at gapless transitions and seeks. Keeping the gain here means an
    // in-progress fade survives both, and the next track continues from the current level.
    override fun onFlush(streamMetadata: StreamMetadata) = Unit

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (activeGain == 1f && targetGain == 1f) {
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        var channel = 0
        var gain = activeGain

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                if (channel == 0) gain = nextGain()
                val sample = inputBuffer.getShort() * gain
                output.putShort(sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                channel = if (channel + 1 == channels) 0 else channel + 1
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                if (channel == 0) gain = nextGain()
                output.putFloat(inputBuffer.getFloat() * gain)
                channel = if (channel + 1 == channels) 0 else channel + 1
            }
        }
        output.flip()
    }

    /** Advances at most one slew step per frame, so all channels share the same gain. */
    private fun nextGain(): Float {
        val target = targetGain
        val active = activeGain
        if (active == target) return active
        val next = if (active < target) {
            (active + gainStep).coerceAtMost(target)
        } else {
            (active - gainStep).coerceAtLeast(target)
        }
        activeGain = next
        return next
    }

    private companion object {
        const val RAMP_DURATION_SECONDS = 0.006f
        const val DEFAULT_SAMPLE_RATE = 44_100
    }
}

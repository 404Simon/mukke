package com.eddyizm.tempus.util

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FadeAudioProcessorTest {

    @Test
    fun unityIsPassThrough() {
        val input = ShortArray(512) { (it * 53 - 12_000).toShort() }
        assertArrayEquals(input, run16(processor(), input))
    }

    @Test
    fun setGainReachesSilenceWithinOneBuffer() {
        val sampleRate = 48_000
        val fadeFrames = (sampleRate * 0.006f).toInt()
        val processor = processor(sampleRate = sampleRate)
        processor.setGain(0f)

        val first = run16(processor, ShortArray(2048) { 20_000 })
        assertTrue("fade should start", first.take(fadeFrames / 2).any { it != 0.toShort() })
        assertTrue("slew should finish in about 6 ms", first.drop(fadeFrames + 1).all { it == 0.toShort() })

        val second = run16(processor, ShortArray(512) { 20_000 })
        assertTrue("gain stays at zero", second.all { it == 0.toShort() })
        assertEquals(0f, processor.currentGain(), 0f)
    }

    @Test
    fun snapToGainSilencesTheFirstSample() {
        val processor = processor()
        processor.snapToGain(0f)

        val output = run16(processor, ShortArray(64) { 20_000 })
        assertEquals(0.toShort(), output.first())
        assertTrue(output.all { it == 0.toShort() })
    }

    @Test
    fun gainSurvivesFlush() {
        val processor = processor()
        processor.snapToGain(0.5f)
        processor.flush(StreamMetadata.DEFAULT)

        assertEquals(0.5f, processor.currentGain(), 0f)
        val output = run16(processor, ShortArray(64) { 10_000 })
        assertTrue(output.all { it == 5_000.toShort() })
    }

    @Test
    fun resetToUnityRestoresImmediatePassThrough() {
        val processor = processor()
        processor.setGain(0f)
        run16(processor, ShortArray(2048) { 20_000 })
        assertEquals(0f, processor.currentGain(), 0f)

        processor.resetToUnity()
        assertEquals(1f, processor.currentGain(), 0f)
        val input = ShortArray(256) { (it * 31 - 4_000).toShort() }
        assertArrayEquals(input, run16(processor, input))
    }

    @Test
    fun unsupportedEncodingIsNotAccepted() {
        val processor = FadeAudioProcessor()
        val format = processor.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_8BIT))

        assertEquals(AudioFormat.NOT_SET, format)
        assertFalse(processor.isActive)
    }

    private fun processor(
        sampleRate: Int = 48_000,
        channels: Int = 1,
        encoding: Int = C.ENCODING_PCM_16BIT
    ): FadeAudioProcessor = FadeAudioProcessor().apply {
        val format = AudioFormat(sampleRate, channels, encoding)
        assertEquals(format, configure(format))
        flush(StreamMetadata.DEFAULT)
    }

    private fun run16(processor: FadeAudioProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(samples)
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(output.remaining()).also { output.get(it) }
    }
}

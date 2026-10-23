package com.eddyizm.tempus.service.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class CrossfadeTimingTest {

    @Test
    fun disabledOrUnknownTracksDoNotFade() {
        assertEquals(0L, CrossfadeTiming.fadeDurationMs(0L, 180_000L, 0L, 1f))
        assertEquals(0L, CrossfadeTiming.fadeDurationMs(5_000L, C.TIME_UNSET, 0L, 1f))
        assertEquals(0L, CrossfadeTiming.fadeDurationMs(5_000L, 0L, 0L, 1f))
    }

    @Test
    fun aTrackShorterThanTheMinimumDoesNotFade() {
        assertEquals(
            0L,
            CrossfadeTiming.fadeDurationMs(
                configuredMs = 5_000L,
                trackDurationMs = CrossfadeTiming.MIN_FADE_MS * 2 - 1,
                positionMs = 0L,
                speed = 1f,
            )
        )
    }

    @Test
    fun normalTracksUseTheConfiguredDuration() {
        assertEquals(
            5_000L,
            CrossfadeTiming.fadeDurationMs(5_000L, 180_000L, 100_000L, 1f)
        )
    }

    @Test
    fun aFadeNeverUsesMoreThanHalfTheTrack() {
        assertEquals(
            10_000L,
            CrossfadeTiming.fadeDurationMs(12_000L, 20_000L, 0L, 1f)
        )
    }

    @Test
    fun speedIsMeasuredInWallClockTime() {
        // Two media minutes left at 2x is one wall-clock minute.
        assertEquals(
            10_000L,
            CrossfadeTiming.fadeDurationMs(10_000L, 180_000L, 60_000L, 2f)
        )
    }

    @Test
    fun theFadeIsCappedAtWhatIsLeft() {
        assertEquals(
            2_000L,
            CrossfadeTiming.fadeDurationMs(10_000L, 180_000L, 178_000L, 1f)
        )
    }

    @Test
    fun equalPowerCurvesMeetAtConstantPower() {
        assertEquals(1f, CrossfadeTiming.outgoingGain(0f), 0.0001f)
        assertEquals(0f, CrossfadeTiming.incomingGain(0f), 0.0001f)
        assertEquals(0f, CrossfadeTiming.outgoingGain(1f), 0.0001f)
        assertEquals(1f, CrossfadeTiming.incomingGain(1f), 0.0001f)

        val outgoing = CrossfadeTiming.outgoingGain(0.5f)
        val incoming = CrossfadeTiming.incomingGain(0.5f)
        assertEquals(sqrt(0.5f), outgoing, 0.0001f)
        assertEquals(sqrt(0.5f), incoming, 0.0001f)
        assertEquals(1f, outgoing * outgoing + incoming * incoming, 0.0001f)
    }
}

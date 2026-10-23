package com.eddyizm.tempus.service.playback

import androidx.media3.common.C
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure timing and volume-curve rules for crossfading. Kept separate from the
 * player/engine code so short tracks and playback-speed changes are testable.
 */
internal object CrossfadeTiming {

    /** A fade shorter than this is not worth the setup; use the normal transition. */
    const val MIN_FADE_MS = 500L

    /** How early the incoming player is prepared before the fade begins. */
    const val PREPARE_LEAD_MS = 10_000L

    /**
     * The wall-clock fade length for the current track, or 0 when it should not fade.
     *
     * [configuredMs] is the user's overlap. It is capped at half the track and at the
     * time actually left, both converted from media time with [speed] so a fade always
     * ends with the outgoing track even when playback speed is not 1.
     */
    fun fadeDurationMs(
        configuredMs: Long,
        trackDurationMs: Long,
        positionMs: Long,
        speed: Float,
    ): Long {
        if (configuredMs <= 0L || trackDurationMs <= 0L || trackDurationMs == C.TIME_UNSET) {
            return 0L
        }
        val safeSpeed = speed.coerceAtLeast(0.1f)
        val remainingWallMs = ((trackDurationMs - positionMs).coerceAtLeast(0L) / safeSpeed).toLong()
        if (remainingWallMs < MIN_FADE_MS) return 0L

        val halfTrackWallMs = (trackDurationMs / 2 / safeSpeed).toLong()
        val desired = minOf(configuredMs, halfTrackWallMs)
        return if (desired < MIN_FADE_MS) 0L else minOf(desired, remainingWallMs)
    }

    /** Equal-power fade-out for the outgoing track. */
    fun outgoingGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * (PI / 2).toFloat()).coerceIn(0f, 1f)

    /** Equal-power fade-in for the incoming track. */
    fun incomingGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * (PI / 2).toFloat()).coerceIn(0f, 1f)
}

package com.eddyizm.tempus.service.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.eddyizm.tempus.equalizer.ParametricProfile
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.util.ReplayGainUtil
import kotlin.random.Random

/**
 * Rotating two-player crossfade for local music.
 *
 * The incoming track is prepared on the standby player at position zero. When the fade begins the
 * standby is started and **published to the session first**; the outgoing player keeps playing
 * behind it and fades out. At the end the outgoing is stopped and the two players swap roles.
 *
 * That order matters. The incoming track is the session player from the start, so it is never
 * seeked at the end of the outgoing track and there is no handoff between two copies of the same
 * audio. The outgoing simply becomes a second voice that is faded to silence over the configured
 * overlap. This is the same model used by PixelPlayerOSS.
 *
 * Everything here runs on the main thread. The engine only controls audio and the player swap; the
 * service owns queue, MediaSession, ReplayGain, notifications and the actual active-player field.
 */
@UnstableApi
class CrossfadeEngine(
    context: Context,
    initial: LocalPlayer,
    private val createPlayer: () -> LocalPlayer,
    /** Move the session, service listeners and ReplayGain target to [incoming]. */
    private val publish: (outgoing: LocalPlayer, incoming: LocalPlayer) -> Unit,
    private val isSessionPlayer: (Player) -> Boolean,
    private val isSuppressed: () -> Boolean,
    /** The outgoing track reached the end of the fade; finish its scrobble/bookkeeping. */
    private val onOutgoingFinished: (MediaItem) -> Unit,
) {

    private enum class Phase { IDLE, PREPARED, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private val focus = LocalAudioFocus(context, ::onFocusChanged)

    private var active: LocalPlayer = initial
    private var standby: LocalPlayer? = null
    private var phase = Phase.IDLE
    private var preparedNextId: String? = null
    private var skippedNextId: String? = null

    private var outgoing: LocalPlayer? = null
    private var incoming: LocalPlayer? = null
    private var fadeStartedAt = 0L
    private var fadeDurationMs = 0L
    private var lastProgress = 0f
    private var bufferingSince = 0L

    private var ducked = false
    private var focusHeld = false
    private var resumeOnGain = false
    private var suppressResumeClear = false
    private var externalPlayback = false
    private var released = false
    private var tickIntervalMs = IDLE_TICK_MS
    private var tickerGeneration = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val generation = tickerGeneration
            try {
                onTick()
            } catch (t: Throwable) {
                Log.w(TAG, "crossfade tick failed, cancelling", t)
                cancelAndSettle()
                stopTicker()
            }
            if (!released && generation == tickerGeneration) {
                handler.postDelayed(this, tickIntervalMs)
            }
        }
    }

    private val activeListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (released) return
            if (isPlaying) {
                requestFocusIfNeeded()
                if (Preferences.isCrossfadeEnabled()) startTicker()
                return
            }

            // A stale pause event can arrive after GAIN has already resumed playback.
            if (active.player.playWhenReady) return

            if (phase == Phase.FADING) {
                cancelAndSettle()
            } else {
                cancelPreparation()
            }
            stopTicker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                requestFocusIfNeeded()
                if (Preferences.isCrossfadeEnabled() && isLocalSession()) startTicker()
            } else {
                if (active.player.playWhenReady) return
                if (suppressResumeClear) {
                    suppressResumeClear = false
                } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    resumeOnGain = false
                }
                if (phase != Phase.FADING) {
                    cancelPreparation()
                    stopTicker()
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                interrupt()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            interrupt()
            if (phase == Phase.IDLE && canSchedule() && active.player.isPlaying) {
                startTicker()
            }
            skippedNextId = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Queue additions (continuous play) and edits must not cut a fade that is already
            // audible. A replaced/removed current item surfaces as a media-item transition,
            // which still cancels below.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                skippedNextId = null
                if (phase == Phase.PREPARED) {
                    cancelPreparation()
                } else if (phase == Phase.IDLE && canSchedule() && active.player.isPlaying) {
                    startTicker()
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (phase == Phase.PREPARED) cancelPreparation()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (phase == Phase.PREPARED) cancelPreparation()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            interrupt()
        }

        override fun onVolumeChanged(volume: Float) {
            // The fades live in the processors, so the base volume must stay equal on both
            // players. This covers remote/notification volume changes during a blend.
            mirrorVolume(volume)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            interrupt()
        }
    }

    /** Per-player tag-based ReplayGain listener; only the current standby may apply it. */
    private val standbyListeners = HashMap<LocalPlayer, Player.Listener>()

    private fun attachStandbyListener(local: LocalPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (phase == Phase.PREPARED && standby === local) {
                    ReplayGainUtil.setReplayGainTo(local.replayGain, local.player, tracks)
                }
            }
        }
        local.player.addListener(listener)
        standbyListeners[local] = listener
    }

    fun start() {
        if (released) return
        active.player.addListener(activeListener)
        if (active.player.isPlaying) {
            requestFocusIfNeeded()
            if (Preferences.isCrossfadeEnabled() && isLocalSession()) startTicker()
        }
    }

    fun release() {
        if (released) return
        released = true
        stopTicker()
        active.player.removeListener(activeListener)
        standbyListeners.forEach { (local, listener) -> local.player.removeListener(listener) }
        standbyListeners.clear()
        standby?.let {
            stopAndClear(it)
            it.player.release()
        }
        standby = null
        outgoing = null
        incoming = null
        preparedNextId = null
        skippedNextId = null
        focus.abandon()
        focusHeld = false
        active.fade.resetToUnity()
        phase = Phase.IDLE
    }

    /**
     * The session is being handed to Eternal mode, which snapshots the live player. Stop any
     * crossfade work so the captured player stays alive and unchanged until the session switch.
     * Focus is kept: the local player remains the audible one until Eternal takes over.
     */
    fun onExternalPlaybackStarted() {
        if (released) return
        externalPlayback = true
        if (phase == Phase.FADING) cancelAndSettle() else cancelPreparation()
        stopTicker()
    }

    /** Eternal preparation was cancelled or failed; local playback never left the session. */
    fun onExternalPlaybackEnded() {
        if (released) return
        externalPlayback = false
        if (isLocalSession() && active.player.isPlaying) {
            requestFocusIfNeeded()
            if (Preferences.isCrossfadeEnabled()) startTicker()
        }
    }

    /** Called for a preference change. Enabling starts scheduling immediately. */
    fun onSettingsChanged() {
        if (released) return
        if (!Preferences.isCrossfadeEnabled()) {
            if (phase == Phase.FADING) cancelAndSettle() else cancelPreparation()
            stopTicker()
            return
        }
        // A running fade keeps the duration it started with; the next track uses the new value.
        if (phase == Phase.FADING) return
        if (canSchedule() && active.player.isPlaying) {
            startTicker()
        } else {
            cancelPreparation()
            stopTicker()
        }
    }

    /**
     * The session was changed outside the engine (Cast, UPnP, Eternal, or returning from one).
     * Local playback gets scheduling and focus back; anything else cancels the fade.
     */
    fun onSessionChanged(keepFocus: Boolean = false) {
        if (released) return
        if (isLocalSession()) {
            externalPlayback = false
            if (phase == Phase.IDLE) active.fade.setGain(duckFactor())
            if (active.player.isPlaying) {
                requestFocusIfNeeded()
                if (Preferences.isCrossfadeEnabled()) startTicker()
            }
        } else {
            if (phase == Phase.FADING) cancelAndSettle() else cancelPreparation()
            stopTicker()
            if (!keepFocus) {
                focus.abandon()
                focusHeld = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Scheduling and preparation
    // ---------------------------------------------------------------------

    private fun onTick() {
        if (released) return

        if (phase == Phase.FADING) {
            tickIntervalMs = FADE_TICK_MS
            updateFade()
            return
        }

        if (!canSchedule()) {
            cancelPreparation()
            stopTicker()
            return
        }
        if (isSuppressed()) {
            cancelPreparation()
            stopTicker()
            return
        }

        val player = active.player
        if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
            cancelPreparation()
            stopTicker()
            return
        }

        val current = player.currentMediaItem
        if (current == null || !isMusic(current)) {
            cancelPreparation()
            stopTicker()
            return
        }
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) {
            // Some streams resolve their duration late; keep a slow tick alive to pick it up.
            cancelPreparation()
            tickIntervalMs = IDLE_TICK_MS
            return
        }
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET ||
            nextIndex == player.currentMediaItemIndex ||
            nextIndex !in 0 until player.mediaItemCount
        ) {
            cancelPreparation()
            stopTicker()
            return
        }
        val next = player.getMediaItemAt(nextIndex)
        if (!isMusic(next) || next.mediaId == current.mediaId) {
            cancelPreparation()
            stopTicker()
            return
        }

        val configured = Preferences.getCrossfadeDurationMs()
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val nextDurationMs =
            (next.mediaMetadata.extras?.getInt("duration", 0) ?: 0).toLong() * 1000L
        val incomingCapWallMs =
            if (nextDurationMs > 0L) (nextDurationMs / speed).toLong() else Long.MAX_VALUE
        val fadeMs = minOf(
            CrossfadeTiming.fadeDurationMs(
                configuredMs = configured,
                trackDurationMs = duration,
                positionMs = player.currentPosition,
                speed = speed,
            ),
            incomingCapWallMs,
        )
        if (fadeMs <= 0L) {
            cancelPreparation()
            stopTicker()
            return
        }

        val remainingWallMs =
            ((duration - player.currentPosition).coerceAtLeast(0L) / speed).toLong()
        val inWindow = remainingWallMs <= fadeMs + CrossfadeTiming.PREPARE_LEAD_MS

        tickIntervalMs = when {
            remainingWallMs <= fadeMs + FAST_WINDOW_MS -> FAST_TICK_MS
            inWindow -> PREPARE_TICK_MS
            else -> IDLE_TICK_MS
        }

        if (!inWindow) {
            cancelPreparation()
            return
        }
        if (skippedNextId == next.mediaId) return

        if (phase == Phase.PREPARED) {
            standby?.player?.skipSilenceEnabled = active.player.skipSilenceEnabled
            standby?.player?.trackSelectionParameters = active.player.trackSelectionParameters
        }
        if (preparedNextId != next.mediaId || phase != Phase.PREPARED) {
            prepareStandby(next, nextIndex)
        }

        if (remainingWallMs <= fadeMs) {
            val prepared = standby
            if (prepared != null && phase == Phase.PREPARED && isStandbyReady(prepared)) {
                startFade(remainingWallMs.coerceAtLeast(CrossfadeTiming.MIN_FADE_MS))
            } else if (remainingWallMs < CrossfadeTiming.MIN_FADE_MS) {
                skippedNextId = next.mediaId
                cancelPreparation()
            }
        }
    }

    private fun prepareStandby(next: MediaItem, nextIndex: Int) {
        if (phase == Phase.FADING) return
        if (skippedNextId == next.mediaId) return
        cancelPreparation()

        val target = standby ?: createPlayer().also { standby = it }
        if (!standbyListeners.containsKey(target)) {
            attachStandbyListener(target)
        }

        val count = active.player.mediaItemCount
        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) items.add(active.player.getMediaItemAt(i))

        val shuffleOrder = copyShuffleOrder(active.player)
        if (active.player.shuffleModeEnabled && shuffleOrder == null) {
            // Never silently replace the user's shuffle order with a fresh one at the swap.
            skippedNextId = next.mediaId
            stopAndClear(target)
            return
        }

        target.player.stop()
        target.player.clearMediaItems()
        target.player.setMediaItems(items, nextIndex, 0L)
        target.player.repeatMode = active.player.repeatMode
        target.player.shuffleModeEnabled = active.player.shuffleModeEnabled
        shuffleOrder?.let { target.player.shuffleOrder = it }
        target.player.playbackParameters = active.player.playbackParameters
        target.player.trackSelectionParameters = active.player.trackSelectionParameters
        target.player.skipSilenceEnabled = active.player.skipSilenceEnabled
        target.player.volume = active.player.volume

        target.fade.snapToGain(0f)
        target.parametricEq.setProfile(
            Preferences.getParametricEqProfile()?.let { ParametricProfile.parse(it) }
        )
        ReplayGainUtil.applyGainTo(target.replayGain, target.player)
        target.player.prepare()
        target.player.pause()

        preparedNextId = next.mediaId
        skippedNextId = null
        phase = Phase.PREPARED
    }

    private fun isStandbyReady(local: LocalPlayer): Boolean {
        val player = local.player
        if (player.playerError != null || player.playbackState != Player.STATE_READY) return false
        return player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO && group.length > 0
        }
    }

    private fun copyShuffleOrder(source: androidx.media3.exoplayer.ExoPlayer): ShuffleOrder? {
        if (!source.shuffleModeEnabled) return null
        val length = source.mediaItemCount
        if (length <= 0) return null

        val order = IntArray(length)
        var index = source.shuffleOrder.firstIndex
        var i = 0
        while (index != C.INDEX_UNSET && i < length) {
            order[i++] = index
            index = source.shuffleOrder.getNextIndex(index)
        }
        return if (i == length) DefaultShuffleOrder(order, Random.nextLong()) else null
    }

    // ---------------------------------------------------------------------
    // The fade
    // ---------------------------------------------------------------------

    private fun startFade(durationMs: Long) {
        val out = active
        val inn = standby ?: return cancelPreparation()
        if (!isStandbyReady(inn)) {
            cancelPreparation()
            return
        }

        phase = Phase.FADING
        outgoing = out
        incoming = inn
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeDurationMs = durationMs.coerceAtLeast(CrossfadeTiming.MIN_FADE_MS)
        lastProgress = 0f
        bufferingSince = 0L
        preparedNextId = null

        out.player.pauseAtEndOfMediaItems = true
        inn.player.volume = out.player.volume
        inn.fade.snapToGain(0f)
        out.fade.snapToGain(duckFactor())

        // The incoming player is the session from this instant. Start it silently first, then
        // hand the session to it; the outgoing stays alive only to fade out.
        inn.player.playWhenReady = true
        inn.player.play()
        out.player.removeListener(activeListener)
        active = inn
        inn.player.addListener(activeListener)
        standby = out
        publish(out, inn)

        requestFocusIfNeeded()
        tickIntervalMs = FADE_TICK_MS
        startTicker()
    }

    private fun updateFade() {
        // A sleep timer armed mid-fade applies to the session player (the incoming track); let
        // the already-audible blend finish instead of cutting to the incoming at full volume.
        if (outgoing == null) return cancelAndSettle()
        val inn = incoming ?: return cancelAndSettle()
        if (!isSessionPlayer(inn.player)) return cancelAndSettle()

        if (!inn.player.playWhenReady) return cancelAndSettle()
        if (inn.player.playbackState == Player.STATE_ENDED || inn.player.playerError != null) {
            return cancelAndSettle()
        }

        if (!inn.player.isPlaying) {
            // Buffering: keep the outgoing at the current level rather than fading into silence.
            applyFadeGains(lastProgress)
            if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - bufferingSince >= BUFFERING_TIMEOUT_MS) {
                cancelAndSettle()
            }
            return
        }
        bufferingSince = 0L

        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAt
        lastProgress = if (fadeDurationMs <= 0L) 1f
        else (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
        applyFadeGains(lastProgress)
        if (lastProgress >= 1f) finishFade()
    }

    private fun applyFadeGains(progress: Float) {
        val out = outgoing ?: return
        val inn = incoming ?: return
        val duck = duckFactor()
        out.fade.setGain(CrossfadeTiming.outgoingGain(progress) * duck)
        inn.fade.setGain(CrossfadeTiming.incomingGain(progress) * duck)
    }

    private fun finishFade() {
        val out = outgoing ?: return
        val inn = incoming ?: return
        out.player.currentMediaItem?.let(onOutgoingFinished)
        inn.fade.snapToGain(duckFactor())
        stopAndClear(out)
        outgoing = null
        incoming = null
        phase = Phase.IDLE
        tickIntervalMs = IDLE_TICK_MS
    }

    /** Ends a fade early: a track that was already within its final seconds still completes. */
    private fun cancelAndSettle() {
        if (phase == Phase.PREPARED) {
            cancelPreparation()
            return
        }
        val out = outgoing
        val inn = incoming
        if (out != null) {
            finishOutgoingIfComplete(out)
            stopAndClear(out)
        }
        inn?.fade?.snapToGain(duckFactor())
        outgoing = null
        incoming = null
        phase = Phase.IDLE
    }

    /** A fade interrupted in its last seconds still counts as the outgoing track finishing. */
    private fun finishOutgoingIfComplete(local: LocalPlayer) {
        val duration = local.player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        if (local.player.currentPosition < duration - 1_500L) return
        local.player.currentMediaItem?.let(onOutgoingFinished)
    }

    private fun cancelPreparation() {
        if (phase != Phase.PREPARED) return
        standby?.let { stopAndClear(it) }
        preparedNextId = null
        phase = Phase.IDLE
    }

    private fun stopAndClear(local: LocalPlayer) {
        local.player.pauseAtEndOfMediaItems = false
        local.player.pause()
        local.player.stop()
        local.player.clearMediaItems()
        local.fade.resetToUnity()
    }

    private fun interrupt() {
        when (phase) {
            Phase.FADING -> cancelAndSettle()
            Phase.PREPARED -> cancelPreparation()
            Phase.IDLE -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // Audio focus
    // ---------------------------------------------------------------------

    private fun requestFocusIfNeeded() {
        if (focusHeld || !isLocalSession()) return
        when (focus.request()) {
            LocalAudioFocus.Result.GRANTED -> focusHeld = true
            LocalAudioFocus.Result.DELAYED -> {
                // Delayed focus must stay silent until GAIN arrives.
                focusHeld = false
                resumeOnGain = active.player.playWhenReady
                suppressResumeClear = true
                active.player.playWhenReady = false
            }
            LocalAudioFocus.Result.DENIED -> {
                focusHeld = false
                suppressResumeClear = true
                active.player.playWhenReady = false
            }
        }
    }

    private fun onFocusChanged(event: LocalAudioFocus.Event) {
        // The helper abandons a permanently lost request even while another session owns focus.
        if (event == LocalAudioFocus.Event.LOSS) focusHeld = false
        if (released || !isLocalSession()) return
        when (event) {
            LocalAudioFocus.Event.LOSS -> {
                resumeOnGain = wasPlaying()
                suppressResumeClear = true
                if (phase == Phase.FADING) cancelAndSettle() else cancelPreparation()
                pauseAll()
                focusHeld = false
            }
            LocalAudioFocus.Event.LOSS_TRANSIENT -> {
                resumeOnGain = wasPlaying()
                suppressResumeClear = true
                if (phase == Phase.FADING) cancelAndSettle() else cancelPreparation()
                pauseAll()
            }
            LocalAudioFocus.Event.DUCK -> {
                ducked = true
                applyCurrentGain()
            }
            LocalAudioFocus.Event.GAIN -> {
                ducked = false
                applyCurrentGain()
                if (resumeOnGain) {
                    resumeOnGain = false
                    focusHeld = focus.request() == LocalAudioFocus.Result.GRANTED
                    if (focusHeld) {
                        active.player.playWhenReady = true
                        suppressResumeClear = false
                    }
                }
            }
        }
    }

    private fun wasPlaying(): Boolean =
        active.player.playWhenReady || outgoing?.player?.playWhenReady == true

    private fun pauseAll() {
        active.player.playWhenReady = false
        outgoing?.player?.playWhenReady = false
        incoming?.player?.playWhenReady = false
    }

    private fun applyCurrentGain() {
        if (phase == Phase.FADING) {
            applyFadeGains(lastProgress)
        } else {
            // Use the slew-limited setter for focus duck/gain changes; a snap is audible.
            active.fade.setGain(duckFactor())
        }
    }

    private fun duckFactor(): Float = if (ducked) DUCK_GAIN else 1f

    private fun mirrorVolume(volume: Float) {
        standby?.player?.takeIf { it.volume != volume }?.volume = volume
        outgoing?.player?.takeIf { it.volume != volume }?.volume = volume
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private fun canSchedule(): Boolean =
        !released &&
            !externalPlayback &&
            Preferences.isCrossfadeEnabled() &&
            isLocalSession() &&
            !isSuppressed() &&
            active.player.currentMediaItem?.let(::isMusic) == true

    private fun isLocalSession(): Boolean = isSessionPlayer(active.player)

    private fun isMusic(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC

    private fun startTicker() {
        if (released || externalPlayback || !isLocalSession()) return
        if (phase != Phase.FADING && active.player.currentMediaItem?.let(::isMusic) != true) return
        tickerGeneration++
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        tickerGeneration++
        tickIntervalMs = IDLE_TICK_MS
        handler.removeCallbacks(ticker)
    }

    private companion object {
        const val TAG = "CrossfadeEngine"

        const val IDLE_TICK_MS = 250L
        const val PREPARE_TICK_MS = 100L
        const val FAST_TICK_MS = 50L
        const val FADE_TICK_MS = 32L

        /** Start the faster ticker this close to the fade. */
        const val FAST_WINDOW_MS = 1_500L

        /** Hold a buffering incoming track this long before settling to it. */
        const val BUFFERING_TIMEOUT_MS = 3_000L

        /** Local audio-focus duck level. */
        const val DUCK_GAIN = 0.2f
    }
}

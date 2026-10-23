package com.eddyizm.tempus.eternal

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.service.BaseMediaService
import com.eddyizm.tempus.util.Constants
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "EternalPlayback"
private const val EXIT_FADE_STEPS = 12
private const val EXIT_FADE_STEP_MS = 15L
private const val EXIT_PREPARE_TIMEOUT_MS = 4_000L
private const val EXIT_PREPARE_POLL_MS = 25L

/**
 * Owns the one eternal session and bridges it to the service's [androidx.media3.session.MediaSession].
 *
 * The normal ExoPlayer stays alive (paused) for the whole session, so leaving eternal mode is
 * instant and the queue survives. The eternal [EternalPlayer] is installed as the session player
 * directly instead of through [BaseMediaService.setPlayer], which would copy the queue over the
 * old player and stop it.
 *
 * Player objects are only touched on the main thread. File/engine preparation runs on the supplied
 * single-thread executor. Every asynchronous step carries a generation number so an exit or a
 * player switch during preparation discards the result and cleans up its file and engine.
 */
@UnstableApi
class EternalPlaybackController(
    private val service: BaseMediaService,
    private val mainHandler: Handler,
    private val executor: ExecutorService,
) {

    private enum class Phase { INACTIVE, PREPARING, PLAYING }

    @Volatile
    private var released = false

    @Volatile
    private var phase = Phase.INACTIVE

    // Written on the main thread, read by preparation callbacks posted back to the main thread.
    @Volatile
    private var generation = 0L

    @Volatile
    private var prepareCancelled: AtomicBoolean? = null

    // The local player paused for the session, and everything created for the eternal session.
    @Volatile
    private var exoPlayer: ExoPlayer? = null
    @Volatile
    private var eternalPlayer: EternalPlayer? = null
    private var audioOutput: EternalAudioOutput? = null
    private var engine: EternalEngine? = null
    private var exitFade: Runnable? = null
    private var restoring = false

    // Snapshot of the state to put the local player back into when eternal mode ends.
    private var restorePlayWhenReady = false
    private var restoreIndex = 0

    // Track that the running preparation belongs to. Used to cancel early if the queue moves.
    private var preparingMediaId: String? = null

    fun isActive(): Boolean = phase != Phase.INACTIVE

    /**
     * The play/pause state the user currently wants: the live eternal player while it is
     * playing, or the state captured before preparation while it is still starting.
     */
    internal fun playIntent(): Boolean =
        if (phase == Phase.PLAYING) {
            eternalPlayer?.playWhenReady ?: restorePlayWhenReady
        } else {
            // During preparation normal playback is still live; the user may have paused or
            // resumed it since the button was tapped.
            exoPlayer?.playWhenReady ?: restorePlayWhenReady
        }

    internal fun isEternalPlayer(player: Player?): Boolean =
        player != null && player === eternalPlayer

    /** Starts, cancels a preparation, or leaves eternal mode depending on the current phase. */
    fun toggle() {
        runOnMain {
            when (phase) {
                Phase.PREPARING -> cancelPreparation()
                Phase.PLAYING -> stopAndRestoreOnMain(
                    currentIndexOrRestore(),
                    currentSourcePositionMs(),
                    play = playIntent(),
                )
                Phase.INACTIVE -> startFromCurrent()
            }
        }
    }

    /**
     * Leaves eternal mode at [index]/[positionMs] and resumes normal playback.
     *
     * Safe from any thread and idempotent. Passing no index/position uses the live eternal
     * position, so a same-track exit continues from the part of the endless walk that is
     * currently audible. The defaults are resolved on the main thread, where the player lives.
     */
    fun stopAndRestore(
        index: Int = C.INDEX_UNSET,
        positionMs: Long = C.TIME_UNSET,
        play: Boolean = true,
    ) {
        runOnMain {
            val targetIndex = if (index == C.INDEX_UNSET) currentIndexOrRestore() else index
            val targetPosition =
                if (positionMs == C.TIME_UNSET) currentSourcePositionMs() else positionMs
            stopAndRestoreOnMain(targetIndex, targetPosition, play)
        }
    }

    /** Tears down eternal mode without putting the local player back into the session. */
    internal fun stopWithoutRestore() {
        if (released) return

        // Invalidate a preparation that is still running (or whose install runnable is already
        // queued) before the new session player is installed. Otherwise it could overwrite the
        // Cast/UPnP handoff with the eternal player.
        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null
        preparingMediaId = null
        phase = Phase.INACTIVE

        // Release after the caller has swapped in the new session player, so the old player is
        // never released while the session and notification still point at it.
        mainHandler.post {
            if (released) return@post
            teardownEternal()
            exoPlayer = null
            EternalMode.post(EternalModeState.Inactive)
        }
    }

    /**
     * Called when something else (Cast, UPnP) installs a player in the session while eternal mode
     * is active. Stops the eternal output but never fights the new session player.
     */
    internal fun onSessionPlayerReplaced(newPlayer: Player) {
        runOnMain {
            if (phase == Phase.INACTIVE || newPlayer === eternalPlayer) return@runOnMain
            Log.d(TAG, "Session player replaced, detaching eternal mode")
            detachOnMain()
        }
    }

    /**
     * Cancels a preparation when the queue moves to a different track. Normal playback is not
     * touched; only the analysis for the track that is no longer current is abandoned.
     */
    internal fun onMediaItemChanged(mediaId: String?) {
        runOnMain {
            if (released) return@runOnMain
            val expected = preparingMediaId
            if (phase == Phase.PREPARING && expected != null && mediaId != expected) {
                Log.d(TAG, "Track changed during eternal preparation; cancelling")
                cancelPreparation()
            }
        }
    }

    /** Tears everything down on service destroy. Idempotent and never restores playback. */
    fun release() {
        if (released) return
        released = true
        exitFade?.let { mainHandler.removeCallbacks(it) }
        exitFade = null
        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null
        preparingMediaId = null

        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachOnMain()
            exoPlayer = null
        } else {
            mainHandler.post {
                teardownEternal()
                exoPlayer = null
                phase = Phase.INACTIVE
                EternalMode.post(EternalModeState.Inactive)
            }
        }
        executor.shutdownNow()
    }

    // ─────────────────────────────────────────────────────────────
    // Start
    // ─────────────────────────────────────────────────────────────

    private fun startFromCurrent() {
        if (released || restoring || phase != Phase.INACTIVE) return

        // LAN remote playback is driven by a separate controller; starting a local eternal
        // walk here would play over the renderer.
        if (LanRemoteSession.isActive()) {
            reportError("Eternal mode is not available during remote playback")
            return
        }

        val player = service.livePlayer()
        if (player !is ExoPlayer) {
            reportError("Eternal mode is only available during local playback")
            return
        }

        val item = player.currentMediaItem
        if (item == null || player.mediaItemCount == 0) {
            reportError("Nothing is playing")
            return
        }
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) {
            reportError("Nothing is playing")
            return
        }

        // Radio and podcasts have no finite song to walk; only ordinary music qualifies.
        val type = item.mediaMetadata.extras?.getString("type")
        if (type != Constants.MEDIA_TYPE_MUSIC) {
            reportError("Eternal mode is only available for music")
            return
        }

        val positionMs = player.currentPosition.let { if (it == C.TIME_UNSET) 0L else it }
        val queue = service.getQueueFromPlayer(player)
        if (queue.isEmpty()) {
            reportError("Nothing is playing")
            return
        }

        // Eternal snapshots the live player during analysis. Stop any crossfade work so the
        // player it captured is still the same one when the engine is ready.
        service.onEternalPreparationStarted()

        // Normal playback keeps running throughout analysis. The switch position is read when
        // the engine is ready, not here, so the listener never hears a silent gap.
        exoPlayer = player
        preparingMediaId = item.mediaId

        phase = Phase.PREPARING
        EternalMode.post(EternalModeState.Preparing(item.mediaMetadata.title?.toString()))
        Log.d(TAG, "Preparing eternal mode from index $index at ${positionMs}ms (playback continues)")

        val thisGeneration = ++generation
        val cancelled = AtomicBoolean(false)
        prepareCancelled = cancelled

        try {
            executor.execute {
                prepareInBackground(
                    generation = thisGeneration,
                    cancelled = cancelled,
                    item = item,
                    positionMs = positionMs,
                    queue = queue,
                    index = index,
                )
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Eternal preparation rejected", e)
            failPreparation("Eternal mode is shutting down")
        }
    }

    private fun prepareInBackground(
        generation: Long,
        cancelled: AtomicBoolean,
        item: MediaItem,
        positionMs: Long,
        queue: List<MediaItem>,
        index: Int,
    ) {
        // Normal playback keeps running while this thread decodes and analyses. Audio must win
        // any scheduling contention, so never run the preparation at default priority.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var temporaryFile: File? = null
        var preparedEngine: EternalEngine? = null

        try {
            val resolved = EternalSourceResolver.resolve(
                context = service.applicationContext,
                mediaItem = item,
                cancelled = cancelled,
            )
            if (resolved.temporary) temporaryFile = resolved.file

            if (cancelled.get() || this.generation != generation || released) {
                throw CancellationException("Eternal preparation cancelled")
            }

            preparedEngine = EternalEngine.prepare(
                path = resolved.file.absolutePath,
                startSeconds = positionMs / 1000.0,
                seed = Random.nextLong(),
            )

            // The engine holds decoded PCM; the source file is no longer needed.
            if (temporaryFile != null) {
                if (!temporaryFile.delete() && temporaryFile.exists()) {
                    Log.w(TAG, "Could not delete eternal source file $temporaryFile")
                }
                temporaryFile = null
            }

            if (cancelled.get() || this.generation != generation || released) {
                throw CancellationException("Eternal preparation cancelled")
            }

            // Ownership passes to the main-thread runnable, which closes it if it is stale.
            val ready = preparedEngine
                ?: throw IllegalStateException("Eternal engine was not prepared")
            preparedEngine = null
            mainHandler.post {
                if (released || cancelled.get() || this.generation != generation) {
                    ready.close()
                    return@post
                }
                installPreparedEngine(
                    prepared = ready,
                    expectedMediaId = item.mediaId,
                    queue = queue,
                    index = index,
                    positionMs = positionMs,
                )
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Eternal preparation cancelled")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to prepare eternal mode", t)
            mainHandler.post {
                if (!released && this.generation == generation) {
                    failPreparation(t.message ?: "Could not prepare eternal mode")
                }
            }
        } finally {
            temporaryFile?.let {
                if (!it.delete() && it.exists()) {
                    Log.w(TAG, "Could not delete eternal source file $it")
                }
            }
            preparedEngine?.let {
                try {
                    it.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to close abandoned eternal engine", t)
                }
            }
        }
    }

    private fun installPreparedEngine(
        prepared: EternalEngine,
        expectedMediaId: String,
        queue: List<MediaItem>,
        index: Int,
        positionMs: Long,
    ) {
        val exo = exoPlayer
        if (exo == null) {
            prepared.close()
            failPreparation("Local playback is no longer available")
            return
        }
        preparingMediaId = null

        // Normal playback ran during the analysis. Only a track change makes the prepared
        // engine useless; a pause or seek is fine because the switch reads the live position.
        if (exo.currentMediaItem?.mediaId != expectedMediaId) {
            prepared.close()
            generation++
            prepareCancelled = null
            phase = Phase.INACTIVE
            service.onEternalPreparationEnded()
            EternalMode.post(EternalModeState.Inactive)
            Log.d(TAG, "Discarding eternal preparation; playback moved on")
            return
        }

        // Own the engine from here on so a construction failure tears it down too.
        engine = prepared

        // The queue may have changed while the analysis ran (an enqueue, a network refresh).
        // Prefer the live ExoPlayer queue when it still points at the same track, so the
        // eternal snapshot, the notification index and the eventual exit index all agree.
        val liveQueue = runCatching { service.getQueueFromPlayer(exo) }.getOrNull()
        val liveIndex = exo.currentMediaItemIndex
        val useLiveQueue = liveQueue != null &&
            liveQueue.isNotEmpty() &&
            liveIndex in liveQueue.indices &&
            liveQueue[liveIndex].mediaId == expectedMediaId
        val queueForPlayer = if (useLiveQueue) liveQueue ?: queue else queue
        val indexForPlayer = if (useLiveQueue) liveIndex else index
        restoreIndex = indexForPlayer
        val wasPlaying = exo.playWhenReady
        restorePlayWhenReady = wasPlaying

        val beatCount = runCatching { prepared.beatCount }.getOrDefault(0)
        val branches = if (beatCount > 0) {
            runCatching { prepared.branchPairs() }.getOrDefault(IntArray(0))
        } else {
            IntArray(0)
        }
        EternalMode.publishMap(if (beatCount > 0) EternalMap(beatCount, branches) else null)

        var pausedForSwitch = false
        var switchPositionMs = positionMs
        try {
            val output = EternalAudioOutput(
                context = service.applicationContext,
                engine = prepared,
                audioSessionId = exo.audioSessionId,
                onFatalError = ::onPlaybackError,
            )
            audioOutput = output

            // Create the AudioTrack and fill one chunk while the normal player is still
            // audible. Only the pause/start switch remains after this point, so the hand-off
            // has no silence. The tiny repeat caused by the source advancing during priming is
            // shorter than one audio chunk and inaudible.
            output.prepare()
            if (output.isFailed) {
                throw IllegalStateException("Could not prepare the eternal audio output")
            }

            switchPositionMs = exo.currentPosition.let {
                if (it == C.TIME_UNSET) positionMs else it.coerceAtLeast(0L)
            }
            prepared.seekToSeconds(switchPositionMs / 1000.0)
            output.prime()
            if (output.isFailed) {
                throw IllegalStateException("Could not prime the eternal audio output")
            }

            val eternal = EternalPlayer(
                engine = prepared,
                output = output,
                queue = queueForPlayer,
                initialIndex = indexForPlayer,
                durationUs = (prepared.durationSeconds * 1_000_000.0).toLong(),
                looper = Looper.getMainLooper(),
                initialVolume = exo.volume,
                context = service.applicationContext,
                onExitRequested = { exitIndex, exitPosition ->
                    // Never release this player from inside its own handleSeek callback.
                    mainHandler.post {
                        stopAndRestore(exitIndex, exitPosition, play = true)
                    }
                },
                onFatalError = ::onPlaybackError,
                addToQueue = { insertIndex, mediaItems ->
                    val player = exoPlayer
                    player != null && player === exo && runCatching {
                        player.addMediaItems(
                            insertIndex.coerceIn(0, player.mediaItemCount),
                            mediaItems,
                        )
                    }.isSuccess
                },
            )

            exo.pause()
            pausedForSwitch = true
            eternalPlayer = eternal
            phase = Phase.PLAYING

            // Start the primed AudioTrack before the session swap so notification/session work
            // cannot add silence to the hand-off. The swap then only attaches the controls.
            if (wasPlaying) eternal.start() else eternal.prepare()
            service.installSessionPlayer(eternal)
            eternal.playWhenReady = wasPlaying

            EternalMode.post(EternalModeState.Playing)
            Log.d(
                TAG,
                "Eternal mode started at ${switchPositionMs}ms " +
                    "(index $indexForPlayer, wasPlaying=$wasPlaying)"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start eternal playback", t)
            phase = Phase.INACTIVE
            exoPlayer?.let { service.installSessionPlayer(it) }
            teardownEternal()
            service.onEternalPreparationEnded()
            EternalMode.post(EternalModeState.Inactive)
            EternalMode.reportError(t.message ?: "Could not start eternal mode")
            if (pausedForSwitch) {
                restoreExoOnMain(
                    restoreIndex,
                    switchPositionMs,
                    play = restorePlayWhenReady,
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Stop / cancel
    // ─────────────────────────────────────────────────────────────

    private fun stopAndRestoreOnMain(index: Int, positionMs: Long, play: Boolean) {
        if (released) return
        when (phase) {
            Phase.INACTIVE -> return
            Phase.PREPARING -> {
                cancelPreparation()
                return
            }
            Phase.PLAYING -> Unit
        }

        // A same-track exit continues where the endless walk currently is; a track change
        // (next/previous) starts the target track at the position the player asked for.
        val sameTrack = index == currentIndexOrRestore()
        val enginePosition = engineSourcePositionOrNull()
        val targetPosition = if (sameTrack && enginePosition != null) enginePosition else positionMs

        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null

        // Keep the level the user chose during eternal mode when normal playback resumes.
        val eternalVolume = eternalPlayer?.volume

        phase = Phase.INACTIVE
        restoring = true
        // restoreExoOnMain prepares the local player while the eternal output is still
        // audible, then hands the session over and crossfades out of Eternal. Do not tear the
        // eternal player down before that, or the hand-off leaves a silent gap.
        restoreExoOnMain(index, targetPosition, play, eternalVolume)
        EternalMode.post(EternalModeState.Inactive)
        Log.d(TAG, "Eternal mode stopped at index $index, ${targetPosition}ms, play=$play")
    }

    private fun cancelPreparation() {
        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null
        preparingMediaId = null

        if (phase != Phase.PREPARING) return

        phase = Phase.INACTIVE
        service.onEternalPreparationEnded()
        EternalMode.post(EternalModeState.Inactive)

        // Normal playback was never paused by preparation, so there is nothing to restore.
        Log.d(TAG, "Eternal preparation cancelled")
    }

    private fun failPreparation(message: String) {
        if (released) return
        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null
        preparingMediaId = null

        teardownEternal()
        phase = Phase.INACTIVE
        service.onEternalPreparationEnded()
        EternalMode.post(EternalModeState.Inactive)
        EternalMode.reportError(message)
        Log.w(TAG, "Eternal mode failed: $message")
    }

    private fun detachOnMain() {
        if (phase == Phase.INACTIVE) return

        generation++
        prepareCancelled?.set(true)
        prepareCancelled = null
        preparingMediaId = null

        teardownEternal()
        exoPlayer = null
        phase = Phase.INACTIVE
        EternalMode.post(EternalModeState.Inactive)
        Log.d(TAG, "Eternal mode detached without restoring")
    }

    private fun restoreExoOnMain(
        index: Int,
        positionMs: Long,
        play: Boolean,
        volume: Float? = null,
    ) {
        val exo = exoPlayer ?: run {
            restoring = false
            teardownEternal()
            EternalMode.post(EternalModeState.Inactive)
            return
        }
        val output = audioOutput

        val safeIndex = if (index in 0 until exo.mediaItemCount) {
            index
        } else {
            exo.currentMediaItemIndex
        }
        val targetVolume = (volume ?: exo.volume).coerceIn(0f, 1f)
        val eternalOutput = output
        val canBlend = play && eternalOutput != null && eternalPlayer?.playWhenReady == true

        // Prepare the local player silently while Eternal keeps playing. This is the same idea
        // as the entry hand-off: the outgoing audio is never stopped before the incoming one is
        // ready. Playback at zero volume does not produce an audible gap.
        if (safeIndex != C.INDEX_UNSET) {
            exo.seekTo(safeIndex, positionMs.coerceAtLeast(0L))
        }
        exo.volume = if (canBlend) 0f else targetVolume
        exo.prepare()
        exo.playWhenReady = play

        if (!canBlend) {
            // Nothing audible to blend with: switch silently and keep the target level.
            service.installSessionPlayer(exo)
            teardownEternal()
            exo.volume = targetVolume
            service.onEternalPreparationEnded()
            restoring = false
            EternalMode.post(EternalModeState.Inactive)
            return
        }
        val outputToFade = requireNotNull(eternalOutput)

        awaitExoPlaying(exo, EXIT_PREPARE_TIMEOUT_MS) { ready ->
            if (released) return@awaitExoPlaying
            if (!restoreStillOwnsSession()) {
                // Cast/UPnP or another queue took the session while we prepared.
                exo.playWhenReady = false
                teardownEternal()
                restoring = false
                return@awaitExoPlaying
            }
            val eternalStillPlaying = eternalPlayer?.playWhenReady == true
            if (!ready || !eternalStillPlaying) {
                // Could not get normal playback ready, or the user paused Eternal while we
                // prepared. Switch silently and honour the current play/pause intent.
                service.installSessionPlayer(exo)
                teardownEternal()
                exo.volume = targetVolume
                exo.playWhenReady = play && eternalStillPlaying
                service.onEternalPreparationEnded()
                restoring = false
                EternalMode.post(EternalModeState.Inactive)
                return@awaitExoPlaying
            }

            service.installSessionPlayer(exo)
            // Keep crossfade scheduling out of the exit blend; it resumes when the fade ends.
            service.onEternalPreparationStarted()

            val eternalStartVolume = output.volume.coerceIn(0f, 1f)
            val fade = object : Runnable {
                private var step = 0

                override fun run() {
                    if (released) return
                    if (service.livePlayer() !== exo) {
                        // Another session took over mid-blend; never steal it back.
                        exitFade = null
                        exo.playWhenReady = false
                        teardownEternal()
                        restoring = false
                        return
                    }
                    if (!exo.playWhenReady) {
                        // Paused during the short blend: stop the eternal output immediately.
                        exitFade = null
                        teardownEternal()
                        exo.volume = targetVolume
                        service.onEternalPreparationEnded()
                        restoring = false
                        EternalMode.post(EternalModeState.Inactive)
                        return
                    }
                    step++
                    val progress = (step.toFloat() / EXIT_FADE_STEPS).coerceIn(0f, 1f)
                    val incoming = sin(progress * (Math.PI / 2)).toFloat()
                    val outgoing = cos(progress * (Math.PI / 2)).toFloat()
                    exo.volume = targetVolume * incoming
                    output.volume = eternalStartVolume * outgoing
                    if (step < EXIT_FADE_STEPS) {
                        mainHandler.postDelayed(this, EXIT_FADE_STEP_MS)
                    } else {
                        exitFade = null
                        teardownEternal()
                        exo.volume = targetVolume
                        service.onEternalPreparationEnded()
                        restoring = false
                        EternalMode.post(EternalModeState.Inactive)
                    }
                }
            }
            exitFade = fade
            mainHandler.postDelayed(fade, EXIT_FADE_STEP_MS)
        }
    }

    private fun restoreStillOwnsSession(): Boolean {
        val live = service.livePlayer()
        return live === eternalPlayer || live === exoPlayer
    }

    private fun awaitExoPlaying(exo: ExoPlayer, timeoutMs: Long, onReady: (Boolean) -> Unit) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        val poll = object : Runnable {
            override fun run() {
                if (released) {
                    onReady(false)
                    return
                }
                if (exo.playbackState == Player.STATE_READY && exo.isPlaying) {
                    onReady(true)
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline ||
                    exo.playerError != null
                ) {
                    onReady(false)
                    return
                }
                mainHandler.postDelayed(this, EXIT_PREPARE_POLL_MS)
            }
        }
        mainHandler.post(poll)
    }

    private fun onPlaybackError(error: Throwable) {
        Log.w(TAG, "Eternal playback failed", error)
        mainHandler.post {
            if (released || phase != Phase.PLAYING) return@post
            EternalMode.reportError(error.message ?: "Eternal playback failed")
            stopAndRestoreOnMain(
                currentIndexOrRestore(),
                currentSourcePositionMs(),
                play = playIntent(),
            )
        }
    }

    private fun teardownEternal() {
        exitFade?.let { mainHandler.removeCallbacks(it) }
        exitFade = null
        restoring = false
        val player = eternalPlayer.also { eternalPlayer = null }
        val output = audioOutput.also { audioOutput = null }
        val engineToClose = engine.also { engine = null }

        player?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to release eternal player", t)
            }
        }
        output?.let {
            try {
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close eternal audio output", t)
            }
        }
        engineToClose?.let {
            try {
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close eternal engine", t)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun currentIndexOrRestore(): Int {
        val index = eternalPlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
        return if (index != C.INDEX_UNSET) index else restoreIndex
    }

    private fun currentSourcePositionMs(): Long {
        val enginePosition = engineSourcePositionOrNull()
        if (enginePosition != null) return enginePosition
        val position = eternalPlayer?.currentPosition ?: C.TIME_UNSET
        return if (position == C.TIME_UNSET) 0L else position
    }

    /**
     * The native engine can fail while rendering; never let a diagnostic read abort the
     * teardown/restore path, which must always put normal playback back.
     */
    private fun engineSourcePositionOrNull(): Long? = try {
        engine?.sourcePositionMs()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read the eternal source position", t)
        null
    }

    private fun runOnMain(block: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { if (!released) block() }
        }
    }

    private fun reportError(message: String) {
        Log.w(TAG, message)
        EternalMode.reportError(message)
    }
}

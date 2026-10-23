package com.eddyizm.tempus.eternal

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The Media3 [Player] for an eternal session.
 *
 * The queue is a frozen snapshot: this player only ever shows the item eternal mode was started
 * from, plus its neighbours for next/previous. Seeking to another item hands control back to the
 * service through [onExitRequested]; leaving eternal mode is the controller's job.
 */
@UnstableApi
class EternalPlayer(
    private val engine: EternalEngine,
    private val output: EternalAudioOutput,
    queue: List<MediaItem>,
    initialIndex: Int,
    durationUs: Long,
    looper: Looper,
    private val initialVolume: Float,
    context: Context,
    private val onExitRequested: (index: Int, positionMs: Long) -> Unit,
    private val onFatalError: (Throwable) -> Unit,
    private val addToQueue: (index: Int, items: List<MediaItem>) -> Boolean,
) : SimpleBasePlayer(looper) {

    private val appContext: Context = context.applicationContext
    private val items: MutableList<MediaItem> = queue.toMutableList()

    // Stable MediaItemData UIDs. They must not depend on the index: queue edits can shift the
    // current item and the session must not report it as a different media item.
    private val uids: MutableList<String> =
        queue.mapIndexed { index, item -> "eternal:$index:${item.mediaId}" }.toMutableList()
    private var nextUid: Long = queue.size.toLong()
    private var currentIndex: Int =
        if (items.isEmpty()) C.INDEX_UNSET else initialIndex.coerceIn(items.indices)
    private val sourceDurationUs: Long = if (durationUs > 0) durationUs else C.TIME_UNSET
    private val sourceDurationMs: Long =
        if (sourceDurationUs == C.TIME_UNSET) 0L else sourceDurationUs / 1_000L

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val wakeLock: PowerManager.WakeLock? =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    @Volatile
    private var playWhenReady = false

    @Volatile
    private var volume = initialVolume.coerceIn(0f, 1f)

    @Volatile
    private var audioFocusHeld = false

    @Volatile
    private var resumeAfterFocusGain = false

    @Volatile
    private var focusSuppressed = false

    @Volatile
    private var ducked = false

    @Volatile
    private var released = false

    private var fatalReported = false
    private var noisyReceiverRegistered = false

    // MediaSession only sends position updates when a player event fires. Eternal playback
    // jumps around inside the source track, so a tick keeps controllers' extrapolated position
    // (and the notification progress) honest. The visualization runs faster than the session
    // state, but far below the audio render rate.
    private val positionHandler = Handler(looper)
    private var lastSessionInvalidateMs = 0L
    private val positionTick = object : Runnable {
        override fun run() {
            if (released || !playWhenReady) return
            publishTelemetry()
            val now = SystemClock.elapsedRealtime()
            if (now - lastSessionInvalidateMs >= POSITION_UPDATE_INTERVAL_MS) {
                lastSessionInvalidateMs = now
                invalidateState()
            }
            positionHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onAudioFocusChanged(change)
    }

    private val focusRequest: FocusRequestApi26? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager?.let {
                FocusRequestApi26(it, focusListener, Handler(looper))
            }
        } else {
            null
        }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !released) {
                pausePlayback(abandonFocus = true)
            }
        }
    }

    private fun buildAvailableCommands(): Player.Commands {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_RELEASE
            )
        if (currentIndex != C.INDEX_UNSET) {
            commands.addAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM
            )
        }
        if (currentIndex > 0) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
        }
        if (currentIndex >= 0 && currentIndex < items.lastIndex) {
            commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        return commands.build()
    }

    init {
        output.volume = effectiveVolume()
        registerNoisyReceiver(appContext)
    }

    override fun getState(): State {
        val playlist = items.mapIndexed { index, item ->
            MediaItemData.Builder(uids[index])
                .setMediaItem(item)
                .setIsSeekable(index == currentIndex)
                .setDurationUs(
                    if (index == currentIndex) sourceDurationUs else durationUsOf(item)
                )
                .build()
        }

        val builder = State.Builder()
            .setAvailableCommands(buildAvailableCommands())
            .setPlaylist(playlist)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(
                playWhenReady,
                if (focusSuppressed) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                } else {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                }
            )
            .setPlaybackSuppressionReason(
                // Also covers the moment before the AudioTrack is running, so isPlaying never
                // claims audio that is not audible.
                if (playWhenReady && (focusSuppressed || !output.isPlaying)) {
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                } else {
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE
                }
            )
            .setVolume(volume)

        if (items.isEmpty()) {
            builder.setCurrentMediaItemIndex(C.INDEX_UNSET).setContentPositionMs(0L)
        } else {
            builder.setCurrentMediaItemIndex(currentIndex)
                .setContentPositionMs(
                    // A native render failure must not crash the session while reporting state.
                    PositionSupplier { runCatching { engine.sourcePositionMs() }.getOrDefault(0L) }
                )
        }
        return builder.build()
    }

    /** Prepares and starts the eternal session. */
    fun start() {
        verifyApplicationThread()
        if (released) return
        prepare()
        play()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        if (playWhenReady) startPlayback() else pausePlayback(abandonFocus = true)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        if (playWhenReady && !focusSuppressed) {
            try {
                output.setPlayWhenReady(true)
            } catch (t: Throwable) {
                fail(t)
                return Futures.immediateVoidFuture()
            }
        }
        updateWakeLock()
        if (playWhenReady) startPositionTicker() else stopPositionTicker()
        publishTelemetry()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!released) pausePlayback(abandonFocus = true)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // BaseSessionCallback.onSetMediaItems exits eternal mode before a new queue is applied,
        // so the session normally routes this to ExoPlayer instead. Fail safely if it doesn't.
        return Futures.immediateFailedFuture<Any?>(
            IllegalStateException("Set the queue after leaving eternal mode")
        )
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        if (released || mediaItems.isEmpty()) return Futures.immediateVoidFuture()

        val insertAt = index.coerceIn(0, items.size)
        // Add to the real ExoPlayer queue as well, so leaving eternal mode keeps the queue the
        // user is looking at. Both copies stay in the same order.
        if (!addToQueue(insertAt, mediaItems)) {
            return Futures.immediateFailedFuture<Any?>(
                IllegalStateException("The local queue is no longer available")
            )
        }

        items.addAll(insertAt, mediaItems)
        uids.addAll(
            insertAt,
            mediaItems.map { item -> "eternal:${nextUid++}:${item.mediaId}" }
        )
        if (currentIndex == C.INDEX_UNSET) {
            currentIndex = 0
        } else if (insertAt <= currentIndex) {
            currentIndex += mediaItems.size
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        return Futures.immediateFailedFuture<Any?>(
            UnsupportedOperationException("Reordering is unavailable during eternal mode")
        )
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        return Futures.immediateFailedFuture<Any?>(
            UnsupportedOperationException("Replacing items is unavailable during eternal mode")
        )
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ): ListenableFuture<*> {
        return Futures.immediateFailedFuture<Any?>(
            UnsupportedOperationException("Removing items is unavailable during eternal mode")
        )
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (released || items.isEmpty()) return Futures.immediateVoidFuture()

        val targetIndex = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> currentIndex + 1
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                currentIndex - 1
            else -> if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex
        }
        if (targetIndex !in items.indices) {
            Log.w(TAG, "seek to index $targetIndex is outside the queue, ignoring it")
            return Futures.immediateVoidFuture()
        }

        if (targetIndex != currentIndex) {
            // The controller leaves eternal mode and resumes normal playback at this item.
            onExitRequested(targetIndex, 0L)
            return Futures.immediateVoidFuture()
        }

        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        try {
            output.seekTo(target)
        } catch (t: Throwable) {
            fail(t)
            return Futures.immediateVoidFuture()
        }
        publishTelemetry()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(
        volume: Float,
        volumeOperationType: Int,
    ): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        output.volume = effectiveVolume()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        released = true
        stopPositionTicker()
        EternalMode.publishFrame(null)
        unregisterNoisyReceiver()
        abandonAudioFocus()
        releaseWakeLock()
        try {
            output.setPlayWhenReady(false)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop the audio output on release", e)
        }
        return Futures.immediateVoidFuture()
    }

    private fun publishTelemetry() {
        val position = runCatching { engine.sourcePositionMs() }.getOrDefault(0L)
        val beat = runCatching { engine.currentBeat() }.getOrDefault(0)
        val jumps = runCatching { engine.jumpCount() }.getOrDefault(0L)
        EternalMode.publishFrame(EternalFrame(position, sourceDurationMs, beat, jumps))
    }

    private fun startPlayback() {
        if (items.isEmpty()) return
        if (!requestAudioFocus()) {
            playWhenReady = false
            focusSuppressed = false
            resumeAfterFocusGain = false
            try {
                output.setPlayWhenReady(false)
            } catch (t: Throwable) {
                Log.w(TAG, "could not stop the audio output after focus denial", t)
            }
            updateWakeLock()
            invalidateState()
            return
        }
        playWhenReady = true
        focusSuppressed = false
        resumeAfterFocusGain = false
        try {
            output.setPlayWhenReady(true)
        } catch (t: Throwable) {
            fail(t)
            return
        }
        updateWakeLock()
        startPositionTicker()
        publishTelemetry()
        invalidateState()
    }

    private fun pausePlayback(abandonFocus: Boolean) {
        playWhenReady = false
        focusSuppressed = false
        resumeAfterFocusGain = false
        stopPositionTicker()
        publishTelemetry()
        restoreDuckedVolume()
        try {
            output.setPlayWhenReady(false)
        } catch (t: Throwable) {
            fail(t)
            return
        }
        if (abandonFocus) abandonAudioFocus()
        updateWakeLock()
        invalidateState()
    }

    private fun onAudioFocusChanged(change: Int) {
        if (released) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                playWhenReady = false
                focusSuppressed = false
                resumeAfterFocusGain = false
                restoreDuckedVolume()
                try {
                    output.setPlayWhenReady(false)
                } catch (t: Throwable) {
                    fail(t)
                    return
                }
                abandonAudioFocus()
                stopPositionTicker()
                updateWakeLock()
                invalidateState()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playWhenReady) {
                    resumeAfterFocusGain = true
                    focusSuppressed = true
                    try {
                        output.setPlayWhenReady(false)
                    } catch (t: Throwable) {
                        fail(t)
                        return
                    }
                    stopPositionTicker()
                    updateWakeLock()
                    invalidateState()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playWhenReady && !ducked) {
                    ducked = true
                    output.volume = effectiveVolume()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreDuckedVolume()
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    if (playWhenReady) {
                        audioFocusHeld = true
                        focusSuppressed = false
                        try {
                            output.setPlayWhenReady(true)
                        } catch (t: Throwable) {
                            fail(t)
                            return
                        }
                        startPositionTicker()
                    }
                }
                updateWakeLock()
                invalidateState()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        if (audioFocusHeld) return true

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.request() == true
        } else {
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        audioFocusHeld = granted
        if (!granted) Log.w(TAG, "audio focus was not granted")
        return granted
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!audioFocusHeld) return
        audioFocusHeld = false
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.abandon()
        } else {
            manager.abandonAudioFocus(focusListener)
        }
    }

    private fun effectiveVolume(): Float = if (ducked) minOf(volume, DUCK_VOLUME) else volume

    private fun restoreDuckedVolume() {
        if (!ducked) return
        ducked = false
        output.volume = effectiveVolume()
    }

    @SuppressLint("Wakelock", "WakelockTimeout")
    private fun updateWakeLock() {
        val lock = wakeLock ?: return
        val wanted = !released && playWhenReady && output.isPlaying
        if (wanted && !lock.isHeld) {
            lock.acquire()
        } else if (!wanted && lock.isHeld) {
            lock.release()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun registerNoisyReceiver(context: Context) {
        try {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "could not register the audio-becoming-noisy receiver", e)
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        try {
            appContext.unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "could not unregister the audio-becoming-noisy receiver", e)
        }
    }

    private fun fail(error: Throwable) {
        if (released || fatalReported) return
        fatalReported = true
        Log.e(TAG, "eternal playback failed", error)
        playWhenReady = false
        focusSuppressed = false
        resumeAfterFocusGain = false
        restoreDuckedVolume()
        try {
            output.setPlayWhenReady(false)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop the audio output after a failure", e)
        }
        abandonAudioFocus()
        stopPositionTicker()
        updateWakeLock()
        invalidateState()
        onFatalError(error)
    }

    private fun startPositionTicker() {
        positionHandler.removeCallbacks(positionTick)
        if (!released && playWhenReady) {
            lastSessionInvalidateMs = SystemClock.elapsedRealtime()
            positionHandler.postDelayed(positionTick, TELEMETRY_INTERVAL_MS)
        }
    }

    private fun stopPositionTicker() {
        positionHandler.removeCallbacks(positionTick)
    }

    private fun durationUsOf(item: MediaItem): Long {
        val seconds = item.mediaMetadata.extras?.getInt("duration", 0) ?: 0
        return if (seconds > 0) seconds * 1000L else C.TIME_UNSET
    }

    /** AudioFocusRequest is only touched on API 26+, so older devices never load the class. */
    @RequiresApi(Build.VERSION_CODES.O)
    private class FocusRequestApi26(
        private val audioManager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
        handler: Handler,
    ) {
        private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN
        )
            .setAudioAttributes(EternalAudioOutput.mediaAudioAttributes())
            .setOnAudioFocusChangeListener(listener, handler)
            .setWillPauseWhenDucked(false)
            .build()

        fun request(): Boolean =
            audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        fun abandon() {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    companion object {
        private const val TAG = "EternalPlayer"

        private const val WAKE_LOCK_TAG = "tempus:EternalPlayer"

        // Keep ducking quiet but audible; restored to the user's volume on gain.
        private const val DUCK_VOLUME = 0.2f

        // Session position refresh while the endless walk is audible.
        private const val POSITION_UPDATE_INTERVAL_MS = 1_000L

        // Visualization refresh rate. Ten frames per second keeps the cursor smooth without
        // touching the media session or allocating on the audio thread.
        private const val TELEMETRY_INTERVAL_MS = 100L
    }
}

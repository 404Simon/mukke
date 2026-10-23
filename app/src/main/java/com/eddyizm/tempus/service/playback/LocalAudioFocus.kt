package com.eddyizm.tempus.service.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi

/**
 * Audio focus for the two local ExoPlayers used for crossfading.
 *
 * Both players are built with `setAudioAttributes(attributes, false)`, so they never request
 * focus themselves. This helper holds one [AudioManager.AUDIOFOCUS_GAIN] request for the pair
 * and reports every change through [onEvent]; the engine decides how to pause, duck or resume.
 *
 * All methods are main-thread only.
 */
class LocalAudioFocus(
    context: Context,
    private val onEvent: (Event) -> Unit,
) {

    enum class Event { LOSS, LOSS_TRANSIENT, DUCK, GAIN }

    enum class Result { GRANTED, DELAYED, DENIED }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var held = false
    private var pending = false
    private var request: FocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // A permanent loss revokes focus immediately. Drop the request so a later
                // request is a fresh one instead of reusing a stale registration.
                abandon()
                onEvent(Event.LOSS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onEvent(Event.LOSS_TRANSIENT)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(Event.DUCK)
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                pending = false
                onEvent(Event.GAIN)
            }
        }
    }

    /**
     * Requests permanent focus. Delayed focus is reported separately so the caller can stay
     * paused until [Event.GAIN] actually arrives.
     */
    @MainThread
    @Suppress("InlinedApi")
    fun request(): Result {
        if (held) return Result.GRANTED
        val manager = audioManager ?: return Result.DENIED
        val focusRequest = request ?: createRequest(manager).also { request = it }

        val result = focusRequest.request()
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                held = true
                pending = false
                Result.GRANTED
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                held = false
                pending = true
                Result.DELAYED
            }
            else -> {
                held = false
                pending = false
                Log.w(TAG, "audio focus was not granted (result=$result)")
                Result.DENIED
            }
        }
    }

    /** Releases the request. Safe when focus is not held. */
    @MainThread
    fun abandon() {
        if (!held && !pending) return
        held = false
        pending = false
        request?.abandon()
    }

    private fun createRequest(manager: AudioManager): FocusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            FocusRequest26(manager, focusListener, mainHandler)
        } else {
            FocusRequestLegacy(manager, focusListener)
        }

    private interface FocusRequest {
        fun request(): Int
        fun abandon()
    }

    @Suppress("DEPRECATION")
    private class FocusRequestLegacy(
        private val audioManager: AudioManager,
        private val listener: AudioManager.OnAudioFocusChangeListener,
    ) : FocusRequest {
        override fun request(): Int =
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )

        override fun abandon() {
            audioManager.abandonAudioFocus(listener)
        }
    }

    /**
     * Kept in its own class so API 24-25 never load [AudioFocusRequest].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private class FocusRequest26(
        private val audioManager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
        handler: Handler,
    ) : FocusRequest {
        private val focusRequest: AudioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAudioAttributes())
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener, handler)
                .build()

        override fun request(): Int = audioManager.requestAudioFocus(focusRequest)

        override fun abandon() {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private companion object {
        const val TAG = "LocalAudioFocus"

        fun mediaAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
    }
}

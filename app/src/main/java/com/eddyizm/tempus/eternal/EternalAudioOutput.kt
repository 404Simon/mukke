package com.eddyizm.tempus.eternal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Plays the endless PCM stream from [EternalEngine] through an [AudioTrack].
 *
 * A single render thread fills 1024-frame chunks and writes them with blocking writes. Render,
 * write, pause, seek and close are serialised by [lock]; a blocking write holds it for roughly
 * 25 ms at 44.1 kHz, which keeps seeks and pauses race free without stalling for long.
 *
 * The engine is owned by the controller, not by this class.
 */
class EternalAudioOutput(
    context: Context,
    private val engine: EternalEngine,
    private val audioSessionId: Int,
    private val onFatalError: (Throwable) -> Unit,
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private val mainHandler = Handler(context.mainLooper)
    private val failureReported = AtomicBoolean()

    private val sampleRate: Int = engine.sampleRate
    private val channelCount: Int = engine.channels
    private val channelMask: Int =
        if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    private val chunkBytes: Int = CHUNK_FRAMES * channelCount * Float.SIZE_BYTES
    private val bufferSizeBytes: Int = run {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        check(minimum > 0) { "AudioTrack.getMinBufferSize failed with $minimum" }
        maxOf(minimum * 2, chunkBytes * CHUNKS_IN_BUFFER)
    }

    private val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())

    private val renderThread = Thread(::renderLoop, "EternalAudioOutput")

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playWhenReady = false

    @Volatile
    private var audioRunning = false

    @Volatile
    private var failed = false

    @Volatile
    private var closeCalled = false

    /** 0..1, clamped. */
    @Volatile
    var volume: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            field = clamped
            lock.withLock {
                try {
                    track?.setVolume(clamped)
                } catch (e: Exception) {
                    Log.w(TAG, "could not set AudioTrack volume", e)
                }
            }
        }

    /** True only while the AudioTrack is actually playing and playback was requested. */
    val isPlaying: Boolean
        get() = playWhenReady && audioRunning && !failed && !closeCalled &&
            track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** True once the output has failed and cannot be used. */
    val isFailed: Boolean
        get() = failed

    init {
        renderThread.start()
    }

    fun setPlayWhenReady(play: Boolean) {
        lock.withLock {
            if (closeCalled || failed || play == playWhenReady) return
            if (play) startLocked() else pauseLocked()
        }
    }

    /**
     * Creates the AudioTrack without starting it. Called while normal playback is still
     * audible, so AudioTrack construction is not part of the hand-off.
     */
    fun prepare() {
        lock.withLock {
            if (closeCalled || failed) return
            try {
                ensureTrackLocked()
            } catch (t: Throwable) {
                fatalLocked(t)
            }
        }
    }

    /**
     * Renders one chunk into the paused AudioTrack so [setPlayWhenReady] can start without an
     * underrun. The engine must already be at the position where the hand-off happens.
     */
    fun prime() {
        lock.withLock {
            if (closeCalled || failed || playWhenReady) return
            val trackNow = try {
                ensureTrackLocked()
            } catch (t: Throwable) {
                fatalLocked(t)
                return
            }

            repeat(PRIME_CHUNKS) {
                buffer.clear()
                val rendered = try {
                    engine.render(buffer, CHUNK_FRAMES)
                } catch (t: Throwable) {
                    fatalLocked(t)
                    return
                }
                if (rendered != CHUNK_FRAMES) {
                    fatalLocked(
                        IllegalStateException(
                            "native render wrote $rendered of $CHUNK_FRAMES frames while priming"
                        )
                    )
                    return
                }

                buffer.position(0)
                buffer.limit(chunkBytes)
                try {
                    writeFully(trackNow)
                } catch (t: Throwable) {
                    fatalLocked(t)
                    return
                }
            }
        }
    }

    /** Repositions the walk. Pauses and flushes first, then resumes if it was playing. */
    fun seekTo(positionMs: Long) {
        lock.withLock {
            if (closeCalled || failed) return
            val target = positionMs.coerceAtLeast(0L) / 1000.0
            val trackNow = track
            val wasPlaying = playWhenReady && audioRunning
            try {
                if (trackNow != null) {
                    trackNow.pause()
                    audioRunning = false
                    trackNow.flush()
                }
                engine.seekToSeconds(target)
                if (wasPlaying && trackNow != null) {
                    trackNow.play()
                    audioRunning = true
                }
            } catch (t: Throwable) {
                fatalLocked(t)
            }
        }
    }

    override fun close() {
        var thread: Thread? = null
        lock.withLock {
            if (closeCalled) return
            closeCalled = true
            playWhenReady = false
            audioRunning = false
            try {
                track?.pause()
                track?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "could not pause AudioTrack while closing", e)
            }
            idle.signalAll()
            thread = renderThread
        }

        // Join without the lock so a chunk in flight can finish.
        val renderThreadNow = thread
        if (renderThreadNow != null && Thread.currentThread() !== renderThreadNow) {
            var interrupted = false
            while (renderThreadNow.isAlive) {
                try {
                    renderThreadNow.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        lock.withLock {
            val trackNow = track
            track = null
            if (trackNow != null) {
                try {
                    trackNow.release()
                } catch (e: Exception) {
                    Log.w(TAG, "could not release AudioTrack", e)
                }
            }
        }
    }

    private fun startLocked() {
        try {
            val trackNow = ensureTrackLocked()
            trackNow.play()
            playWhenReady = true
            audioRunning = true
            idle.signalAll()
        } catch (t: Throwable) {
            fatalLocked(t)
        }
    }

    private fun pauseLocked() {
        playWhenReady = false
        audioRunning = false
        try {
            track?.pause()
        } catch (t: Throwable) {
            fatalLocked(t)
        }
    }

    private fun ensureTrackLocked(): AudioTrack {
        track?.let { return it }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(mediaAudioAttributes())
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeBytes)
        if (audioSessionId > 0) builder.setSessionId(audioSessionId)

        val created = builder.build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            val state = created.state
            created.release()
            throw IllegalStateException("AudioTrack is not initialized (state $state)")
        }
        created.setVolume(volume)
        track = created
        Log.d(TAG, "AudioTrack ready: ${sampleRate} Hz, $channelCount ch, session $audioSessionId")
        return created
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (true) {
            lock.withLock {
                while (!playWhenReady && !closeCalled && !failed) {
                    try {
                        idle.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (closeCalled || failed) return
            }

            // One chunk per lock acquisition so a pause or seek never waits for more than one write.
            lock.withLock {
                if (closeCalled || failed || !playWhenReady) return@withLock
                val trackNow = track ?: return@withLock

                buffer.clear()
                val rendered = try {
                    engine.render(buffer, CHUNK_FRAMES)
                } catch (t: Throwable) {
                    fatalLocked(t)
                    return@withLock
                }
                if (rendered != CHUNK_FRAMES) {
                    fatalLocked(
                        IllegalStateException(
                            "native render wrote $rendered of $CHUNK_FRAMES frames"
                        )
                    )
                    return@withLock
                }

                buffer.position(0)
                buffer.limit(chunkBytes)
                try {
                    writeFully(trackNow)
                } catch (t: Throwable) {
                    fatalLocked(t)
                }
            }
        }
    }

    private fun writeFully(trackNow: AudioTrack) {
        var remaining = chunkBytes
        while (remaining > 0) {
            val written = trackNow.write(buffer, remaining, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                throw IllegalStateException("AudioTrack.write returned $written")
            }
            remaining -= written
        }
    }

    private fun fatalLocked(error: Throwable) {
        if (!failureReported.compareAndSet(false, true)) return
        failed = true
        playWhenReady = false
        audioRunning = false
        try {
            track?.pause()
            track?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "could not stop AudioTrack after a failure", e)
        }
        idle.signalAll()
        Log.e(TAG, "eternal audio output failed", error)
        mainHandler.post { onFatalError(error) }
    }

    companion object {
        private const val TAG = "EternalAudioOutput"

        private const val CHUNK_FRAMES = 1024

        // Fill most of the AudioTrack buffer before the hand-off. The engine runs ahead by this
        // amount, but the audible start still begins at the exact switch position.
        private const val PRIME_CHUNKS = 3

        // Four chunks, so a scheduling hiccup on the render thread does not underrun.
        private const val CHUNKS_IN_BUFFER = 4

        /** Attributes shared with the audio-focus request in [EternalPlayer]. */
        internal fun mediaAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
    }
}

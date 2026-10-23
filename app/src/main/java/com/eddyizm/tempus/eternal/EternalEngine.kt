package com.eddyizm.tempus.eternal

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.random.Random

/**
 * Kotlin wrapper around the Rust Eternal Jukebox renderer.
 *
 * Every native entry point is static, exactly as the JNI library expects. Calls after [close] are
 * rejected with [IllegalStateException]; the owning controller closes the engine once the audio
 * output using it has stopped.
 */
class EternalEngine private constructor(private val handle: Long) : AutoCloseable {

    private val lock = ReentrantReadWriteLock()
    private var closed = false

    val sampleRate: Int = nativeSampleRate(handle).also {
        check(it > 0) { "nativeSampleRate returned $it" }
    }

    val channels: Int = nativeChannels(handle).also {
        check(it in 1..2) { "unsupported channel count $it" }
    }

    val durationSeconds: Double = nativeDurationSeconds(handle)

    val beatCount: Int = nativeBeatCount(handle).also {
        check(it > 0) { "nativeBeatCount returned $it" }
    }

    /** Flat `[source0, destination0, source1, destination1, ...]` transition pairs. */
    fun branchPairs(): IntArray = lock.read {
        checkOpen()
        nativeBranches(handle)
    }

    /** Fills [frames] interleaved f32 samples per channel into [destination]. */
    fun render(destination: ByteBuffer, frames: Int): Int = lock.read {
        checkOpen()
        require(frames > 0) { "frames must be positive" }
        require(destination.isDirect) { "destination must be a direct ByteBuffer" }
        val needed = frames * channels * Float.SIZE_BYTES
        require(destination.remaining() >= needed) {
            "destination has ${destination.remaining()} bytes, needs $needed"
        }
        nativeRender(handle, destination, frames)
    }

    fun sourcePositionMs(): Long = lock.read {
        checkOpen()
        nativeSourcePositionMs(handle).coerceAtLeast(0L)
    }

    fun currentBeat(): Int = lock.read {
        checkOpen()
        nativeCurrentBeat(handle)
    }

    fun jumpCount(): Long = lock.read {
        checkOpen()
        nativeJumpCount(handle)
    }

    fun seekToSeconds(seconds: Double) = lock.read {
        checkOpen()
        require(seconds.isFinite()) { "seconds must be finite" }
        nativeSeekSeconds(handle, seconds.coerceIn(0.0, durationSeconds))
    }

    override fun close() {
        lock.write {
            if (closed) return
            closed = true
            nativeDestroy(handle)
        }
    }

    private fun checkOpen() {
        check(!closed) { "EternalEngine is closed" }
    }

    companion object {
        init {
            System.loadLibrary("eternal_jni")
        }

        /**
         * Decodes, analyses and prepares [path]. [startSeconds] is clamped into the track by the
         * native side. Throws if the file cannot be decoded or the engine cannot be created.
         */
        @JvmStatic
        fun prepare(path: String, startSeconds: Double, seed: Long = Random.nextLong()): EternalEngine {
            require(path.isNotBlank()) { "path must not be blank" }
            require(startSeconds.isFinite()) { "startSeconds must be finite" }
            val handle = nativeCreate(path, startSeconds.coerceAtLeast(0.0), seed)
            check(handle != 0L) { "nativeCreate returned a null handle" }
            return EternalEngine(handle)
        }

        @JvmStatic
        private external fun nativeCreate(path: String, startSeconds: Double, seed: Long): Long

        @JvmStatic
        private external fun nativeRender(handle: Long, destination: ByteBuffer, frames: Int): Int

        @JvmStatic
        private external fun nativeSampleRate(handle: Long): Int

        @JvmStatic
        private external fun nativeChannels(handle: Long): Int

        @JvmStatic
        private external fun nativeDurationSeconds(handle: Long): Double

        @JvmStatic
        private external fun nativeSourcePositionMs(handle: Long): Long

        @JvmStatic
        private external fun nativeCurrentBeat(handle: Long): Int

        @JvmStatic
        private external fun nativeJumpCount(handle: Long): Long

        @JvmStatic
        private external fun nativeBeatCount(handle: Long): Int

        @JvmStatic
        private external fun nativeBranches(handle: Long): IntArray

        @JvmStatic
        private external fun nativeSeekSeconds(handle: Long, seconds: Double)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}

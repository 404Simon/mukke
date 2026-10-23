package com.eddyizm.tempus.eternal

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** The state of the process-wide Eternal Jukebox session. */
sealed interface EternalModeState {
    data object Inactive : EternalModeState
    data class Preparing(val title: String?) : EternalModeState
    data object Playing : EternalModeState
}

/** Immutable beat graph used by the player-side Eternal visualization. */
class EternalMap(val beatCount: Int, val branches: IntArray)

/** Live playback position inside the source track. */
data class EternalFrame(
    val positionMs: Long,
    val durationMs: Long,
    val beat: Int,
    val jumpCount: Long,
)

/**
 * Process-local state shared by the player service and the UI. Everything is delivered on the
 * main thread, so observers never need to switch threads.
 */
object EternalMode {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Lazy, because these are only ever created from the main thread. The service may call
    // reportError() from a background thread, and that path posts before touching them.
    private val stateLiveData: MutableLiveData<EternalModeState> by lazy {
        MutableLiveData<EternalModeState>(EternalModeState.Inactive)
    }
    private val errorLiveData: MutableLiveData<String?> by lazy { MutableLiveData<String?>(null) }
    private val mapLiveData: MutableLiveData<EternalMap?> by lazy { MutableLiveData<EternalMap?>(null) }
    private val frameLiveData: MutableLiveData<EternalFrame?> by lazy {
        MutableLiveData<EternalFrame?>(null)
    }

    @Volatile
    private var latest: EternalModeState = EternalModeState.Inactive

    val state: LiveData<EternalModeState>
        get() = stateLiveData

    val error: LiveData<String?>
        get() = errorLiveData

    val map: LiveData<EternalMap?>
        get() = mapLiveData

    val frame: LiveData<EternalFrame?>
        get() = frameLiveData

    @JvmStatic
    fun isActive(): Boolean = when (latest) {
        is EternalModeState.Preparing, is EternalModeState.Playing -> true
        EternalModeState.Inactive -> false
    }

    /** Publishes a new state. Safe to call from any thread; observers see it on the main thread. */
    @JvmStatic
    fun post(state: EternalModeState) {
        latest = state
        onMain {
            stateLiveData.value = state
            if (state !is EternalModeState.Playing) {
                mapLiveData.value = null
                frameLiveData.value = null
            }
        }
    }

    /** Publishes the static beat graph for the current session. */
    @JvmStatic
    fun publishMap(map: EternalMap?) {
        onMain { mapLiveData.value = map }
    }

    /** Publishes a live position snapshot; called at a UI-friendly rate by the eternal player. */
    @JvmStatic
    fun publishFrame(frame: EternalFrame?) {
        onMain { frameLiveData.value = frame }
    }

    /** Stores a one-shot message for the UI, which clears it once shown. */
    @JvmStatic
    fun reportError(message: String?) {
        onMain { errorLiveData.value = message }
    }

    @JvmStatic
    fun clearError() {
        onMain { errorLiveData.value = null }
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}

package dev.ciel.app.voice

import android.Manifest
import android.content.Context
import dev.ciel.app.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class HeyCielState {
    object Idle : HeyCielState()
    data class Downloading(val progress: Float) : HeyCielState()
    object Starting : HeyCielState()
    data class Failed(val message: String) : HeyCielState()
}

/**
 * One-tap "Hey Ciel": the UI asks for the microphone (and notification) permission, then this
 * downloads the offline wake-word model if needed, saves the setting and starts listening.
 */
class HeyCielSetup(private val graph: AppGraph) {
    private val _state = MutableStateFlow<HeyCielState>(HeyCielState.Idle)
    val state: StateFlow<HeyCielState> = _state

    val isEnabled: Boolean get() = graph.settings.current.wakeWordEnabled

    fun hasMic() = graph.capabilities.has(Manifest.permission.RECORD_AUDIO)

    /** Call after the microphone permission was granted. Must run while Ciel is on screen. */
    suspend fun enable(context: Context) {
        if (!hasMic()) { _state.value = HeyCielState.Failed("Ciel needs the microphone to hear \"Hey Ciel\"."); return }
        try {
            if (!VoskModels.isInstalled(context)) {
                _state.value = HeyCielState.Downloading(0f)
                VoskModels.download(context) { p -> _state.value = HeyCielState.Downloading(p) }
            }
            _state.value = HeyCielState.Starting
            graph.settings.update { it.copy(wakeWordEnabled = true) }
            WakeWordService.start(context)
            _state.value = HeyCielState.Idle
        } catch (e: Exception) {
            _state.value = HeyCielState.Failed(
                if (e is java.net.UnknownHostException || e is java.net.ConnectException) "No internet connection — the one-time 40 MB voice model couldn't download."
                else "Setup failed: ${e.message}")
        }
    }

    suspend fun disable(context: Context) {
        graph.settings.update { it.copy(wakeWordEnabled = false) }
        WakeWordService.stop(context)
    }
}

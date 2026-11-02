package dev.ciel.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

sealed class ListenState {
    object Idle : ListenState()
    data class Listening(val partial: String = "", val level: Float = 0f) : ListenState()
    data class Error(val message: String) : ListenState()
}

/**
 * Speech-to-text via Android's SpeechRecognizer (on-device when available/offline).
 * Must be used from the main thread.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    /** End callback of the session in progress; invoked exactly once. */
    private var pendingEnd: (() -> Unit)? = null
    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onEnd] is called when listening ends without a usable result (silence, error, cancel). */
    fun start(preferOffline: Boolean, onEnd: () -> Unit = {}, onFinal: (String) -> Unit) {
        cancel()
        if (!isAvailable) { _state.value = ListenState.Error("No speech recognition service is installed on this device."); onEnd(); return }
        pendingEnd = onEnd
        val r = if (preferOffline && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = ListenState.Listening() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val s = _state.value
                if (s is ListenState.Listening) _state.value = s.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                _state.value = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenState.Idle
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenState.Error("Microphone permission is needed.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ListenState.Error("Speech recognition needs a connection (no offline language pack).")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenState.Error("The recogniser is busy; try again.")
                    else -> ListenState.Error("Speech recognition error ($error).")
                }
                release()
                endSession()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = ListenState.Idle
                release()
                if (text.isNotBlank()) { pendingEnd = null; onFinal(text) } else endSession()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val s = _state.value
                _state.value = if (s is ListenState.Listening) s.copy(partial = text) else ListenState.Listening(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        _state.value = ListenState.Listening()
        r.startListening(intent)
    }

    fun stop() { recognizer?.stopListening() }
    fun cancel() {
        recognizer?.cancel(); release()
        if (_state.value is ListenState.Listening) _state.value = ListenState.Idle
        endSession()
    }
    private fun endSession() { val e = pendingEnd; pendingEnd = null; e?.invoke() }
    private fun release() { recognizer?.destroy(); recognizer = null }
}

/**
 * Ciel's voice: Android TextToSpeech with a user-selectable voice. Android does not expose a
 * voice's gender, so the default pick uses known female voice identifiers of Google's TTS engine
 * and the user can preview and choose any installed voice in Settings → Voice.
 */
class CielVoice(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        _ready.value = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { _speaking.value = false; onDone?.invoke(); onDone = null }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { _speaking.value = false; onDone = null }
            override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false; onDone = null }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false; onDone = null }
        })
    }

    fun voices(): List<Voice> = runCatching {
        val lang = Locale.getDefault().language
        tts.voices.orEmpty().filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
            .sortedWith(compareBy<Voice> { if (looksFemale(it)) 0 else 1 }.thenBy { it.name })
    }.getOrDefault(emptyList())

    fun configure(voiceName: String?, rate: Float, pitch: Float) {
        if (!_ready.value) return
        val chosen = voices().firstOrNull { it.name == voiceName } ?: voices().firstOrNull { looksFemale(it) }
        chosen?.let { tts.voice = it }
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        tts.setPitch(pitch.coerceIn(0.5f, 2f))
    }

    fun speak(text: String, then: (() -> Unit)? = null) {
        if (!_ready.value) { then?.invoke(); return }
        onDone = then
        val clean = forSpeech(text)
        val chunks = clean.chunked(TextToSpeech.getMaxSpeechInputLength() - 10)
        chunks.forEachIndexed { i, c ->
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    fun preview(voice: Voice) { tts.voice = voice; speak("Notice. This is how I would sound, Master.") }

    /** Voice interruption: stops speaking immediately. */
    fun stop() { tts.stop(); _speaking.value = false; onDone = null }

    fun shutdown() = tts.shutdown()

    companion object {
        // Google TTS voice ids known to be female-sounding (e.g. en-us-x-sfg-local). Heuristic only.
        private val femaleIds = listOf("sfg", "tpf", "tpc", "iog", "iob", "gba", "rjs", "fis", "female")
        fun looksFemale(v: Voice) = femaleIds.any { v.name.contains(it, ignoreCase = true) }

        /** Strips Markdown and URLs so speech sounds natural. */
        fun forSpeech(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (code omitted) ")
            .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1")
            .replace(Regex("https?://\\S+"), "link")
            .replace(Regex("[*_#>`|]"), "")
            .replace(Regex("\\[\\d+]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

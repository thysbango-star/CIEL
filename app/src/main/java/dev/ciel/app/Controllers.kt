package dev.ciel.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.ciel.ai.ChatMessage
import dev.ciel.ai.ImageInput
import dev.ciel.ai.Role
import dev.ciel.app.data.MessageEntity
import dev.ciel.app.platform.Notifications
import dev.ciel.engine.CielState
import dev.ciel.engine.EngineEvent
import dev.ciel.engine.ReasoningMode
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.ConfirmationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A consequential action awaiting the user's decision. */
data class PendingConfirmation(val id: Long, val request: ConfirmationRequest, internal val answer: CompletableDeferred<Boolean>)

/**
 * The confirmation gate shared by every subsystem. The in-app dialog and the floating overlay
 * both render [pending]; a notification is posted so the user notices while in another app.
 * Unanswered requests are declined after 3 minutes. Emergency stop declines them all.
 */
class ConfirmationBroker(private val context: Context) : ConfirmationGate {
    private val _pending = MutableStateFlow<List<PendingConfirmation>>(emptyList())
    val pending: StateFlow<List<PendingConfirmation>> = _pending.asStateFlow()
    private var nextId = 1L

    override suspend fun confirm(request: ConfirmationRequest): Boolean {
        val p = synchronized(this) { PendingConfirmation(nextId++, request, CompletableDeferred()) }
        _pending.value = _pending.value + p
        notify(p)
        return try {
            withTimeoutOrNull(180_000) { p.answer.await() } ?: false
        } finally {
            _pending.value = _pending.value - p
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + p.id.toInt())
        }
    }

    fun answer(id: Long, approved: Boolean) { _pending.value.firstOrNull { it.id == id }?.answer?.complete(approved) }
    fun cancelAll() { _pending.value.forEach { it.answer.complete(false) } }

    private fun notify(p: PendingConfirmation) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 7, Intent(context, dev.ciel.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notifications.TASKS)
            .setSmallIcon(R.drawable.ic_ciel_mono)
            .setContentTitle("Notice. ${p.request.title}")
            .setContentText(p.request.actionDescription)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.request.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + p.id.toInt(), n) }
    }

    companion object { private const val NOTIF_BASE = 5000 }
}

data class ToolActivity(val tool: String, val skill: String, val done: Boolean, val success: Boolean?, val verified: Boolean?, val summary: String?)

/**
 * The conversation with Ciel: persists messages, runs the engine, exposes live state for the
 * full-screen and floating interfaces, and speaks replies.
 */
class ConversationController(private val graph: AppGraph) {
    val conversationId = "main"
    val messages: Flow<List<MessageEntity>> = graph.db.messages().observe(conversationId)

    private val _state = MutableStateFlow(CielState.IDLE)
    val state: StateFlow<CielState> = _state.asStateFlow()
    private val _stateLabel = MutableStateFlow<String?>(null)
    val stateLabel: StateFlow<String?> = _stateLabel.asStateFlow()
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()
    private val _activity = MutableStateFlow<List<ToolActivity>>(emptyList())
    val activity: StateFlow<List<ToolActivity>> = _activity.asStateFlow()
    private val _lastReply = MutableStateFlow<String?>(null)
    val lastReply: StateFlow<String?> = _lastReply.asStateFlow()
    private val _provider = MutableStateFlow<String?>(null)
    val provider: StateFlow<String?> = _provider.asStateFlow()

    private var job: Job? = null

    init {
        graph.emergencyStop.addListener { job?.cancel(); _state.value = CielState.WARNING; _stateLabel.value = "Stopped" }
        graph.appScope.launch(Dispatchers.Main) {
            graph.voice.speaking.collect { speaking ->
                if (speaking) _state.value = CielState.SPEAKING
                else if (_state.value == CielState.SPEAKING) _state.value = CielState.IDLE
            }
        }
    }

    fun setListening(listening: Boolean) {
        if (listening) { _state.value = CielState.LISTENING; _stateLabel.value = null }
        else if (_state.value == CielState.LISTENING) _state.value = CielState.IDLE
    }

    fun send(text: String, images: List<ImageInput> = emptyList(), mode: ReasoningMode = ReasoningMode.CONVERSE, speak: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) return
        job?.cancel()
        graph.voice.stop()
        job = graph.appScope.launch {
            val dao = graph.db.messages()
            val history = dao.recent(conversationId, 40).reversed().map {
                ChatMessage(if (it.role == "user") Role.USER else Role.ASSISTANT, it.text)
            }
            dao.insert(MessageEntity(conversationId = conversationId, role = "user", text = text + if (images.isNotEmpty()) " [image]" else ""))
            _streaming.value = ""; _activity.value = emptyList(); _state.value = CielState.PROCESSING
            var finalText: String? = null
            var offline = false
            graph.engine.respond(history, text, images, mode).collect { ev ->
                when (ev) {
                    is EngineEvent.State -> { _state.value = ev.state; _stateLabel.value = ev.label }
                    is EngineEvent.ProviderChosen -> _provider.value = "${ev.name} · ${ev.model}"
                    is EngineEvent.TextDelta -> _streaming.value += ev.text
                    EngineEvent.ResetText -> _streaming.value = ""
                    is EngineEvent.ToolStarted -> _activity.value = _activity.value + ToolActivity(ev.tool, ev.skill, false, null, null, null)
                    is EngineEvent.ToolFinished -> _activity.value = _activity.value.map {
                        if (it.tool == ev.tool && !it.done) it.copy(done = true, success = ev.success, verified = ev.verified, summary = ev.summary) else it
                    }
                    is EngineEvent.Final -> { finalText = ev.text; offline = ev.offline }
                    is EngineEvent.Failure -> { finalText = "Notice. I could not complete that: ${ev.message}"; _state.value = CielState.ERROR }
                }
            }
            val reply = finalText ?: return@launch
            dao.insert(MessageEntity(conversationId = conversationId, role = "ciel", text = reply, providerName = _provider.value, offline = offline))
            _streaming.value = ""
            _lastReply.value = reply
            if (speak) {
                withContext(Dispatchers.Main) { graph.voice.speak(reply) { onFinished?.invoke() } }
            } else onFinished?.invoke()
        }
    }

    fun cancel() { job?.cancel(); _streaming.value = ""; _state.value = CielState.IDLE }

    suspend fun clear() = graph.db.messages().clear(conversationId)
}

/**
 * Voice flow shared by the in-app mic, the assistant gesture, the quick-settings tile and the
 * wake word: listen → recognise → respond → speak → (optionally) listen again.
 */
class VoiceCoordinator(private val graph: AppGraph) {
    fun listen(continuous: Boolean = graph.settings.current.voice.continuousConversation, done: (() -> Unit)? = null) {
        graph.appScope.launch(Dispatchers.Main) {
            graph.voice.stop()
            graph.conversation.setListening(true)
            graph.speech.start(
                preferOffline = !graph.network.online.value,
                onEnd = { graph.conversation.setListening(false); done?.invoke() },
            ) { text ->
                graph.conversation.setListening(false)
                graph.conversation.send(text, speak = graph.settings.current.voice.speakResponses) {
                    if (continuous && !dev.ciel.engine.LocalIntentParser.isStopCommand(text)) listen(true, done) else done?.invoke()
                }
            }
        }
    }

    /** Called by the wake-word service after it released the microphone; [resume] restarts it. */
    fun onWakeWord(resume: () -> Unit) {
        graph.appScope.launch(Dispatchers.Main) {
            graph.voice.speak("Yes?") { listen(continuous = false) { resume() } }
        }
    }
}

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
import kotlinx.coroutines.flow.flatMapLatest
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
    private val _conversationId = MutableStateFlow("main")
    /** The conversation currently shown on Home. */
    val currentConversation: StateFlow<String> = _conversationId.asStateFlow()
    val conversationId: String get() = _conversationId.value
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: Flow<List<MessageEntity>> = _conversationId.flatMapLatest { graph.db.messages().observe(it) }
    val conversations = graph.db.messages().conversations()

    /** Starts a fresh conversation (history is kept in Chats). */
    fun newConversation() {
        cancel()
        _conversationId.value = "c-" + System.currentTimeMillis()
        _lastReply.value = null
    }

    fun open(id: String) { cancel(); _conversationId.value = id; _lastReply.value = null }

    suspend fun delete(id: String) {
        graph.db.messages().clear(id)
        if (id == conversationId) newConversation()
    }

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
        // Resume the most recent conversation after a restart.
        graph.appScope.launch { graph.db.messages().latestConversationId()?.let { if (_conversationId.value == "main") _conversationId.value = it } }
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

    /**
     * While a game task runs, "what are you doing / why / skip that / pause / stop" go to the task
     * (answered from its live state, without stopping it) instead of starting a new conversation turn.
     * Voice is routed here too, so speaking to Ciel never reaches the game's controls.
     */
    private fun routeToGameTask(text: String, speak: Boolean, onFinished: (() -> Unit)?): Boolean {
        val a = graph.gameEngine.activity.value ?: return false
        if (a.status == dev.ciel.game.GameActivity.Status.FINISHED || a.status == dev.ciel.game.GameActivity.Status.FAILED) return false
        val msg = dev.ciel.game.TaskMessage.parse(text)
        val aboutTask = msg !is dev.ciel.game.TaskMessage.Question ||
            Regex("(?i)\\b(what are you doing|what('?s| is) next|why|explain|where did you|how much|what does|which (building|quest)|status|progress|plan)\\b").containsMatchIn(text)
        if (!aboutTask) return false
        val cid = conversationId
        graph.appScope.launch {
            val dao = graph.db.messages()
            dao.insert(MessageEntity(conversationId = cid, role = "user", text = text))
            val reply = if (msg is dev.ciel.game.TaskMessage.Question) graph.gameEngine.answer(a.taskId, text) else {
                graph.messageGameTask(text)
                when (msg) {
                    dev.ciel.game.TaskMessage.Stop -> "Affirmative. Everything is stopped."
                    dev.ciel.game.TaskMessage.Pause -> "Paused, Master. Say \"continue\" when you're ready."
                    dev.ciel.game.TaskMessage.Resume -> "Continuing."
                    else -> "Understood. I'll take that into account from the next action."
                }
            }
            dao.insert(MessageEntity(conversationId = cid, role = "ciel", text = reply))
            _lastReply.value = reply
            if (speak) withContext(Dispatchers.Main) { graph.voice.speak(reply) { onFinished?.invoke() } } else onFinished?.invoke()
        }
        return true
    }

    fun send(text: String, images: List<ImageInput> = emptyList(), mode: ReasoningMode = ReasoningMode.CONVERSE, speak: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (images.isEmpty() && routeToGameTask(text, speak, onFinished)) return
        if (images.isEmpty()) UiCommands.parse(text)?.let { handleUi(it, text, speak, onFinished); return }
        job?.cancel()
        graph.voice.stop()
        val cid = conversationId // a reply always lands in the conversation it was asked in
        job = graph.appScope.launch {
            val dao = graph.db.messages()
            val history = dao.recent(cid, 40).reversed().map {
                ChatMessage(if (it.role == "user") Role.USER else Role.ASSISTANT, it.text)
            }
            dao.insert(MessageEntity(conversationId = cid, role = "user", text = text + if (images.isNotEmpty()) " [image]" else ""))
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
            val reply = finalText ?: run { onFinished?.invoke(); return@launch }
            dao.insert(MessageEntity(conversationId = cid, role = "ciel", text = reply, providerName = _provider.value, offline = offline))
            _streaming.value = ""
            _lastReply.value = reply
            if (speak) {
                withContext(Dispatchers.Main) { graph.voice.speak(reply) { onFinished?.invoke() } }
            } else onFinished?.invoke()
        }
        // A stop or a newer request cancels this turn; the caller (e.g. the wake-word service)
        // must still get control back.
        job?.invokeOnCompletion { cause -> if (cause != null) onFinished?.invoke() }
    }

    /** Interface commands ("open tasks", "switch to Starfall", "stop talking"…) — instant and offline. */
    private fun handleUi(cmd: UiCommand, text: String, speak: Boolean, onFinished: (() -> Unit)?) {
        val reply: String? = when (cmd) {
            is UiCommand.Navigate -> { graph.navigate(cmd.route); "Opening ${cmd.label}." }
            is UiCommand.SetTheme -> { graph.appScope.launch { graph.settings.update { it.copy(appearance = it.appearance.copy(theme = cmd.theme, accent = null)) } }; "Affirmative. ${cmd.theme.title} theme applied." }
            is UiCommand.SetLayout -> { graph.appScope.launch { graph.settings.update { it.copy(appearance = it.appearance.copy(design = cmd.design)) } }; "Affirmative. ${cmd.design.title} layout applied." }
            UiCommand.StopSpeaking -> { graph.voice.stop(); null }
            UiCommand.Repeat -> _lastReply.value ?: "I haven't said anything yet, Master."
            UiCommand.Status -> {
                val active = graph.tasks.activeTasks()
                if (active.isEmpty()) "Report. No tasks are running. I'm standing by."
                else "Report. " + active.joinToString(" ") { "${it.name}: ${it.status.name.lowercase().replace('_', ' ')}${it.currentStep?.let { s -> ", $s" } ?: ""}." }
            }
        }
        if (reply == null) { onFinished?.invoke(); return }
        val cid = conversationId
        graph.appScope.launch {
            val dao = graph.db.messages()
            if (cmd !is UiCommand.Repeat) {
                dao.insert(MessageEntity(conversationId = cid, role = "user", text = text))
                dao.insert(MessageEntity(conversationId = cid, role = "ciel", text = reply, offline = true))
                _lastReply.value = reply
            }
            _state.value = CielState.COMPLETED
            if (speak) withContext(Dispatchers.Main) { graph.voice.speak(reply) { onFinished?.invoke() } } else onFinished?.invoke()
        }
    }

    fun cancel() { job?.cancel(); _streaming.value = ""; _state.value = CielState.IDLE }

    suspend fun clear() = graph.db.messages().clearAll()
}

/**
 * Voice flow shared by the in-app mic, the assistant gesture, the quick-settings tile and
 * "Hey Ciel": listen → recognise → respond → speak → (optionally) listen again.
 */
class VoiceCoordinator(private val graph: AppGraph) {
    /**
     * [offlineFallback] is used after the wake word: if Android's recogniser refuses to work while
     * another app is open, the wake-word service transcribes the command offline instead.
     */
    fun listen(
        continuous: Boolean = graph.settings.current.voice.continuousConversation,
        offlineFallback: Boolean = false,
        done: (() -> Unit)? = null,
    ) {
        graph.appScope.launch(Dispatchers.Main) {
            graph.voice.stop()
            graph.conversation.setListening(true)
            val handle: (String) -> Unit = { text ->
                graph.conversation.setListening(false)
                graph.conversation.send(text, speak = graph.settings.current.voice.speakResponses) {
                    if (continuous && !dev.ciel.engine.LocalIntentParser.isStopCommand(text)) listen(true, offlineFallback, done) else done?.invoke()
                }
            }
            graph.speech.start(
                preferOffline = !graph.network.online.value,
                onEnd = {
                    val failed = graph.speech.state.value is dev.ciel.app.voice.ListenState.Error
                    val wake = dev.ciel.app.voice.WakeWordService.instance
                    if (failed && offlineFallback && wake != null) {
                        graph.conversation.setListening(true)
                        wake.dictate { text ->
                            graph.conversation.setListening(false)
                            if (text != null) handle(text) else done?.invoke()
                        }
                    } else {
                        graph.conversation.setListening(false)
                        done?.invoke()
                    }
                },
                onFinal = handle,
            )
        }
    }

    /** Called by the wake-word service after it released the microphone; [resume] restarts it. */
    fun onWakeWord(resume: () -> Unit) {
        graph.appScope.launch(Dispatchers.Main) {
            dev.ciel.app.overlay.VoiceOverlay.show(graph.context)
            chime()
            kotlinx.coroutines.delay(250) // so the chime isn't captured as the start of the command
            listen(continuous = graph.settings.current.voice.continuousConversation, offlineFallback = true) {
                graph.appScope.launch(Dispatchers.Main) { dev.ciel.app.overlay.VoiceOverlay.hideSoon() }
                resume()
            }
        }
    }

    private fun chime() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        }
    }
}

package dev.ciel.desktop.core

import dev.ciel.ai.ChatMessage
import dev.ciel.ai.ConnectionWizard
import dev.ciel.ai.ProviderConfig
import dev.ciel.ai.Role
import dev.ciel.core.http.UrlConnectionTransport
import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import dev.ciel.engine.CielEngine
import dev.ciel.engine.CielState
import dev.ciel.engine.EngineEvent
import dev.ciel.engine.EngineSettings
import dev.ciel.engine.InMemoryUsageTracker
import dev.ciel.engine.LlmGateway
import dev.ciel.engine.PersonaSettings
import dev.ciel.engine.ProviderSelector
import dev.ciel.engine.ProviderSource
import dev.ciel.memory.MemoryManager
import dev.ciel.memory.MemorySkill
import dev.ciel.research.PageFetcher
import dev.ciel.research.ResearchSkill
import dev.ciel.research.SearchConfig
import dev.ciel.research.SearchProviderKind
import dev.ciel.research.SearchProviders
import dev.ciel.skills.Capability
import dev.ciel.skills.SkillRegistry
import dev.ciel.skills.builtin.CalculateSkill
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.ConfirmationRequest
import dev.ciel.task.EmergencyStop
import dev.ciel.task.TaskManager
import dev.ciel.windows.KnownLocations
import dev.ciel.windows.RealShell
import dev.ciel.windows.WindowsPcSkill
import dev.ciel.windows.WindowsServices
import dev.ciel.windows.WindowsSystemImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A question Ciel must ask before acting; the UI shows it as a dialog. */
class PendingConfirmation(val id: Long, val request: ConfirmationRequest, val answer: CompletableDeferred<Boolean>)

class ConfirmationBroker : ConfirmationGate {
    private val _pending = MutableStateFlow<List<PendingConfirmation>>(emptyList())
    val pending: StateFlow<List<PendingConfirmation>> = _pending.asStateFlow()
    private var next = 1L
    override suspend fun confirm(request: ConfirmationRequest): Boolean {
        val p = synchronized(this) { PendingConfirmation(next++, request, CompletableDeferred()) }
        _pending.value = _pending.value + p
        return try { withTimeoutOrNull(300_000) { p.answer.await() } ?: false } finally { _pending.value = _pending.value - p }
    }
    fun answer(id: Long, approved: Boolean) { _pending.value.firstOrNull { it.id == id }?.answer?.complete(approved) }
    fun cancelAll() = _pending.value.forEach { it.answer.complete(false) }
}

data class ChatLine(val role: String, val text: String, val at: Long = System.currentTimeMillis(), val offline: Boolean = false)
data class ToolLine(val tool: String, val done: Boolean, val ok: Boolean?, val summary: String?)

/** One conversation, saved as JSON. Same engine and persona as Ciel on the phone. */
class Conversation(private val graph: DesktopGraph, private val file: File = File(DesktopPaths.dataDir, "conversation.json")) {
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()
    private val _state = MutableStateFlow(CielState.IDLE)
    val state: StateFlow<CielState> = _state.asStateFlow()
    private val _tools = MutableStateFlow<List<ToolLine>>(emptyList())
    val tools: StateFlow<List<ToolLine>> = _tools.asStateFlow()
    private val _provider = MutableStateFlow<String?>(null)
    val provider: StateFlow<String?> = _provider.asStateFlow()
    private var job: Job? = null

    fun send(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        val history = _lines.value.takeLast(40).map { ChatMessage(if (it.role == "user") Role.USER else Role.ASSISTANT, it.text) }
        add(ChatLine("user", text.trim()))
        _streaming.value = ""; _tools.value = emptyList(); _state.value = CielState.PROCESSING
        job = graph.scope.launch {
            var final: String? = null; var offline = false
            runCatching {
                graph.engine.respond(history, text.trim()).collect { ev ->
                    when (ev) {
                        is EngineEvent.State -> _state.value = ev.state
                        is EngineEvent.ProviderChosen -> _provider.value = "${ev.name} · ${ev.model}"
                        is EngineEvent.TextDelta -> _streaming.value += ev.text
                        EngineEvent.ResetText -> _streaming.value = ""
                        is EngineEvent.ToolStarted -> _tools.value = _tools.value + ToolLine(ev.tool, false, null, null)
                        is EngineEvent.ToolFinished -> _tools.value = _tools.value.map { if (it.tool == ev.tool && !it.done) it.copy(done = true, ok = ev.success, summary = ev.summary) else it }
                        is EngineEvent.Final -> { final = ev.text; offline = ev.offline }
                        is EngineEvent.Failure -> final = "Notice. ${ev.message}"
                    }
                }
            }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) final = "Notice. Something went wrong: ${e.message}" }
            final?.let { add(ChatLine("ciel", it, offline = offline)); if (graph.settings.current.speakReplies) graph.speak(it) }
            _streaming.value = ""; _state.value = CielState.IDLE
        }
    }

    fun stop() { job?.cancel(); _state.value = CielState.IDLE; _streaming.value = "" }
    fun clear() { _lines.value = emptyList(); save() }

    private fun add(l: ChatLine) { _lines.value = (_lines.value + l).takeLast(500); save() }
    private fun save() = runCatching { file.writeText(json { "lines" to _lines.value.map { l -> json { "r" to l.role; "t" to l.text; "at" to l.at } } }.toString()) }
    private fun load(): List<ChatLine> = runCatching {
        Json.parse(file.readText())["lines"]?.array.orEmpty().map { ChatLine(it["r"]?.string ?: "ciel", it["t"]?.string ?: "", it["at"]?.long ?: 0) }
    }.getOrDefault(emptyList())
}

/**
 * Ciel for Windows: the same engine, persona, providers, memory and safety core as the phone app,
 * plus the Windows PC skill. Created once at start-up.
 */
class DesktopGraph(val secrets: SecretStore, private val moveToTrash: (File) -> Boolean) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore()
    val http = UrlConnectionTransport()
    val shell = RealShell()
    val windows = WindowsSystemImpl(shell)
    val emergencyStop = EmergencyStop()
    val tasks = TaskManager(scope, emergencyStop)
    val confirmations = ConfirmationBroker()
    val usage = InMemoryUsageTracker()
    val providerSource = object : ProviderSource {
        override fun configs(): List<ProviderConfig> = settings.current.providers
        override fun apiKey(providerId: String): String? = secrets.get(keyName(providerId))
    }
    val gateway = LlmGateway(providerSource, http, usage)
    val connectionWizard = ConnectionWizard(http)
    val memory = MemoryManager(FileMemoryRepository())

    val pc = WindowsServices(shell, windows, KnownLocations.fromEnvironment(), DesktopPaths.dataDir, moveToTrash,
        { settings.current.autonomy }, { settings.current.gameProfiles },
        { settings.current.scanExclusions.mapNotNull { runCatching { java.nio.file.Paths.get(it) }.getOrNull() } })

    val skills = SkillRegistry { setOf(Capability.INTERNET, Capability.NOTIFICATIONS_POST) }.apply {
        register(CalculateSkill())
        register(MemorySkill(memory))
        register(ResearchSkill({
            val s = settings.current
            val kind = s.searchKind ?: SearchProviderKind.BUILT_IN
            runCatching { SearchProviders.create(SearchConfig(kind, secrets.get("search:key:${kind.name}"), null), http) }.getOrNull()
        }, PageFetcher(http)))
        register(WindowsPcSkill(pc))
    }

    val engine = CielEngine(
        providers = providerSource, gateway = gateway, selector = ProviderSelector(usage), registry = skills, memory = memory,
        tasks = tasks, device = { null }, confirm = confirmations, isOnline = { online() },
        settings = {
            val s = settings.current
            EngineSettings(persona = PersonaSettings(formOfAddress = s.formOfAddress, platform = "Windows PC"), preferredProviderId = s.preferredProviderId)
        },
    )
    val conversation = Conversation(this)

    init {
        emergencyStop.addListener { _ -> confirmations.cancelAll(); conversation.stop() }
    }

    /** Spoken replies with Windows' own voices (System.Speech), entirely on this PC. */
    fun speak(text: String) {
        val clean = text.replace(Regex("[*_`#>\\[\\]]"), "").take(1500)
        scope.launch { shell.powershell("Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak(${dev.ciel.windows.psQuote(clean)})", 120_000) }
    }

    private fun online(): Boolean = runCatching { java.net.InetAddress.getByName("one.one.one.one"); true }.getOrDefault(false) ||
        settings.current.providers.any { dev.ciel.ai.InferenceLocation.of(it.baseUrl) != dev.ciel.ai.InferenceLocation.CLOUD }

    companion object { fun keyName(providerId: String) = "provider:${providerId.substringBefore(':')}" }
}

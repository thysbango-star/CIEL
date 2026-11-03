package dev.ciel.app

import android.content.Context
import android.content.Intent
import dev.ciel.ai.ProviderConfig
import dev.ciel.app.data.CielDatabase
import dev.ciel.app.data.RoomGameProfileRepository
import dev.ciel.app.data.RoomMemoryRepository
import dev.ciel.app.data.RoomTaskHistory
import dev.ciel.app.data.SettingsStore
import dev.ciel.app.platform.AndroidContactDirectory
import dev.ciel.app.platform.AndroidDeviceController
import dev.ciel.app.platform.AndroidInstalledPackages
import dev.ciel.app.platform.AndroidReminderScheduler
import dev.ciel.app.platform.CapabilityChecker
import dev.ciel.app.platform.CielNotificationListener
import dev.ciel.app.platform.DiscordWebhookSender
import dev.ciel.app.platform.EmailSender
import dev.ciel.app.platform.MediaStoreDocumentStore
import dev.ciel.app.platform.NetworkMonitor
import dev.ciel.app.platform.SmsSender
import dev.ciel.app.platform.TelegramSender
import dev.ciel.app.platform.WhatsAppSender
import dev.ciel.app.security.KeyVault
import dev.ciel.app.voice.CielVoice
import dev.ciel.app.voice.SpeechInput
import dev.ciel.comms.CommunicationEngine
import dev.ciel.comms.CommunicationSkill
import dev.ciel.core.http.UrlConnectionTransport
import dev.ciel.engine.CielEngine
import dev.ciel.engine.InMemoryUsageTracker
import dev.ciel.engine.LlmActionPlanner
import dev.ciel.engine.LlmGateway
import dev.ciel.engine.ProviderSelector
import dev.ciel.engine.ProviderSource
import dev.ciel.game.GameEngine
import dev.ciel.game.GameMasterSkill
import dev.ciel.memory.MemoryManager
import dev.ciel.memory.MemorySkill
import dev.ciel.research.PageFetcher
import dev.ciel.research.ResearchSkill
import dev.ciel.research.SearchConfig
import dev.ciel.research.SearchProviders
import dev.ciel.skills.DeclarativeSkill
import dev.ciel.skills.SkillRegistry
import dev.ciel.skills.builtin.CalculateSkill
import dev.ciel.skills.builtin.CreateSkill
import dev.ciel.skills.builtin.ExecuteSkill
import dev.ciel.skills.builtin.ManageSkill
import dev.ciel.skills.builtin.SystemSkill
import dev.ciel.task.ActionRecord
import dev.ciel.task.EmergencyStop
import dev.ciel.task.GuardedDevice
import dev.ciel.task.ResourceLocks
import dev.ciel.task.ScreenAgent
import dev.ciel.task.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-written dependency graph: every Ciel subsystem, created once per process and connected
 * to the same central engine, emergency stop and confirmation broker.
 */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val vault = KeyVault(context)
    val db = CielDatabase.create(context)
    val settings = SettingsStore(context, appScope)
    val http = UrlConnectionTransport()
    val network = NetworkMonitor(context)
    val capabilities = CapabilityChecker(context)

    // ---- safety core
    val emergencyStop = EmergencyStop()
    val locks = ResourceLocks()
    val taskHistory = RoomTaskHistory(db.tasks())
    val tasks = TaskManager(appScope, emergencyStop, taskHistory)
    val confirmations = ConfirmationBroker(context)
    private val deviceController = AndroidDeviceController(context)
    val guardedDevice = GuardedDevice(deviceController, emergencyStop, confirmations) { record: ActionRecord ->
        appScope.launch { taskHistory.log(record) }
    }

    // ---- providers
    val usage = InMemoryUsageTracker()
    val providerSource = object : ProviderSource {
        override fun configs(): List<ProviderConfig> = settings.current.providers
        override fun apiKey(providerId: String): String? = vault.get(KeyVault.providerKeyName(providerId))
    }
    val gateway = LlmGateway(providerSource, http, usage)
    val selector = ProviderSelector(usage)

    // ---- memory, games, comms
    val memory = MemoryManager(RoomMemoryRepository(db.memories()), settings.current.memory)
    val gameProfiles = RoomGameProfileRepository(db.games())
    val reminders = AndroidReminderScheduler(context, db.reminders())

    fun screenAgentOrNull(): ScreenAgent? = screenAgent()

    private fun screenAgent(): ScreenAgent? {
        if (!guardedDevice.isAvailable || settings.current.providers.none { it.enabled }) return null
        return ScreenAgent(guardedDevice, LlmActionPlanner(gateway) { settings.current.providers }, locks)
    }

    /**
     * Game tasks only need screen control: with no AI connected (or credits exhausted) the game
     * engine continues with its on-phone decisions and says what it cannot do.
     */
    private fun gameAgent(): ScreenAgent? =
        if (!guardedDevice.isAvailable) null else ScreenAgent(guardedDevice, LlmActionPlanner(gateway) { settings.current.providers }, locks)

    val gameEngine = GameEngine(gameProfiles, AndroidInstalledPackages(context), { gameAgent() }, { settings.current.allowScreenshots }, confirmations).apply {
        // Each finished game run's log is kept on the phone so the user can share it for diagnosis.
        runLogSink = { pkg, text -> runCatching { java.io.File(context.filesDir, "game-logs").apply { mkdirs() }.resolve("$pkg.txt").writeText(text) } }
        // Questions asked while a game task runs are answered by the fast model from live context;
        // the task keeps running (non-blocking communication).
        answerer = dev.ciel.game.TaskQuestionAnswerer { ctx, question ->
            val ordered = settings.current.providers.filter { it.enabled }.sortedBy { it.tier.ordinal }
            if (ordered.isEmpty()) return@TaskQuestionAnswerer null
            val request = dev.ciel.ai.ChatRequest(listOf(
                dev.ciel.ai.ChatMessage.system("You are Ciel, explaining what you are doing in a game task for your user. Answer in 1–3 short sentences using ONLY the context. " +
                    "Never claim an action that is not in the context. If the context does not say, say you are not sure yet. Address the user as Master."),
                dev.ciel.ai.ChatMessage.user("Context:\n$ctx\n\nQuestion: $question"),
            ), temperature = 0.2, maxOutputTokens = 200)
            runCatching { gateway.call(ordered, request, stream = false).second.text }.getOrNull()
        }
    }

    /**
     * A message for the running game task (from the notch, voice or chat). Control words act at
     * once — Stop never waits for an AI reply — and questions are answered while the task runs.
     * Returns false when no game task is running.
     */
    fun messageGameTask(text: String): Boolean {
        val a = gameEngine.activity.value ?: return false
        val active = a.status == dev.ciel.game.GameActivity.Status.RUNNING || a.status == dev.ciel.game.GameActivity.Status.WAITING || a.status == dev.ciel.game.GameActivity.Status.PAUSED
        val channel = gameEngine.channel(a.taskId)
        when (val m = dev.ciel.game.TaskMessage.parse(text)) {
            dev.ciel.game.TaskMessage.Stop -> { emergencyStop.trigger("stop requested during game task"); channel?.say(true, text) }
            dev.ciel.game.TaskMessage.Pause -> if (active) { tasks.pause(a.taskId); gameEngine.markPaused(true); channel?.say(true, text) }
            dev.ciel.game.TaskMessage.Resume -> if (active) { tasks.resume(a.taskId); gameEngine.markPaused(false); channel?.say(true, text) }
            is dev.ciel.game.TaskMessage.Skip -> channel?.directive("Skip ${m.what}. Do not do it; continue with the rest.")
            is dev.ciel.game.TaskMessage.Instruction -> channel?.directive(m.text)
            is dev.ciel.game.TaskMessage.Question -> appScope.launch { gameEngine.answer(a.taskId, m.text) }
        }
        return true
    }
    val communication = CommunicationEngine(
        AndroidContactDirectory(context) { settings.current.discordWebhooks },
        listOf(SmsSender(context), WhatsAppSender(context), TelegramSender(context), EmailSender(context),
            DiscordWebhookSender(vault, http) { settings.current.discordWebhooks }),
        CielNotificationListener.source(capabilities),
    )

    // ---- skills
    val skills = SkillRegistry { capabilities.granted() }.apply {
        register(CalculateSkill())
        register(MemorySkill(memory))
        register(SystemSkill())
        register(ManageSkill(reminders))
        register(CreateSkill(MediaStoreDocumentStore(context)))
        register(ExecuteSkill({ screenAgent() }, { settings.current.allowScreenshots }))
        register(GameMasterSkill(gameEngine, gameProfiles))
        register(CommunicationSkill(communication))
        register(ResearchSkill({
            val s = settings.current
            // No choice made → built-in keyless search, so Ciel can always search the web.
            val kind = s.searchKind ?: dev.ciel.research.SearchProviderKind.BUILT_IN
            runCatching { SearchProviders.create(SearchConfig(kind, vault.get(KeyVault.searchKeyName(kind.name)) ?: vault.get(KeyVault.searchKeyName()), s.searchBaseUrl), http) }.getOrNull()
        }, PageFetcher(http)))
    }
    private val userSkillsDir = File(context.filesDir, "skills").apply { mkdirs() }

    // ---- the central intelligence
    val engine = CielEngine(
        providers = providerSource, gateway = gateway, selector = selector, registry = skills, memory = memory,
        tasks = tasks, device = { guardedDevice }, confirm = confirmations,
        isOnline = { network.online.value }, settings = { settings.current.engine },
    )

    // ---- voice & conversation
    val speech = SpeechInput(context)
    val voice = CielVoice(context)
    val conversation = ConversationController(this)
    val voiceCoordinator = VoiceCoordinator(this)
    val heyCiel = dev.ciel.app.voice.HeyCielSetup(this)
    val heyCielDiagnostics = dev.ciel.app.voice.HeyCielDiagnostics(this)
    val taskLauncher = TaskLauncher(this)
    val connectionWizard = dev.ciel.ai.ConnectionWizard(http)

    init {
        taskLauncher.watchCompletions()
        dev.ciel.app.overlay.ActivityNotch.start(context, this)
        loadUserSkills()
        // Keep subsystems in sync with settings.
        appScope.launch { settings.flow.map { it.memory }.distinctUntilChanged().collect { memory.updateSettings(it) } }
        appScope.launch(Dispatchers.Main) {
            settings.flow.map { it.voice }.distinctUntilChanged().collect { v -> voice.configure(v.voiceName, v.speechRate, v.pitch) }
        }
        appScope.launch(Dispatchers.Main) { voice.ready.collect { if (it) settings.current.voice.let { v -> voice.configure(v.voiceName, v.speechRate, v.pitch) } } }
        appScope.launch { memory.purgeExpired() }
        // Emergency stop: silence Ciel, cancel any pending confirmation, stop listening.
        emergencyStop.addListener { _ ->
            appScope.launch(Dispatchers.Main) {
                voice.stop(); speech.cancel(); confirmations.cancelAll()
            }
        }
    }

    fun capabilitiesChanged() = skills.refresh()

    /** Navigation requests from voice ("Ciel, open tasks"). Brings Ciel to the front if needed. */
    val navEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    fun navigate(route: String) {
        if (dev.ciel.app.ui.MainActivity.visible) navEvents.tryEmit(route)
        else runCatching {
            context.startActivity(Intent(context, dev.ciel.app.ui.MainActivity::class.java)
                .putExtra(dev.ciel.app.ui.MainActivity.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- installable skills (JSON files, no rebuild)
    fun loadUserSkills() {
        userSkillsDir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            runCatching { skills.register(DeclarativeSkill.parse(f.readText())) }
        }
    }

    /** Validates and installs a skill definition. Returns the skill name or throws with a clear reason. */
    fun installSkill(json: String): String {
        val skill = DeclarativeSkill.parse(json)
        skills.unregister(skill.id)
        skills.register(skill)
        File(userSkillsDir, skill.id + ".json").writeText(json)
        return skill.displayName
    }

    fun uninstallSkill(id: String) {
        skills.unregister(id)
        File(userSkillsDir, "$id.json").delete()
    }

    fun openApp(intent: Intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

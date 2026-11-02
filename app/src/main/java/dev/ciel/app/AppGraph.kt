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

    private fun screenAgent(): ScreenAgent? {
        if (!guardedDevice.isAvailable || settings.current.providers.none { it.enabled }) return null
        return ScreenAgent(guardedDevice, LlmActionPlanner(gateway) { settings.current.providers }, locks)
    }

    val gameEngine = GameEngine(gameProfiles, AndroidInstalledPackages(context), { screenAgent() }, { settings.current.allowScreenshots })
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
            s.searchKind?.let { kind -> runCatching { SearchProviders.create(SearchConfig(kind, vault.get(KeyVault.searchKeyName()), s.searchBaseUrl), http) }.getOrNull() }
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
    val connectionWizard = dev.ciel.ai.ConnectionWizard(http)

    init {
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

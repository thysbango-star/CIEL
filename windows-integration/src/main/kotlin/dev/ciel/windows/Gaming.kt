package dev.ciel.windows

import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import kotlinx.coroutines.delay
import java.io.File

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val path: String? = null,
    val memoryBytes: Long = 0,
    /** Total CPU seconds used so far (from Windows). */
    val cpuSeconds: Double = 0.0,
    val windowTitle: String? = null,
    val commandLine: String? = null,
) {
    val exe: String get() = name.lowercase().removeSuffix(".exe")
}

data class PowerScheme(val guid: String, val name: String, val active: Boolean)

/** The Windows facilities Gaming Mode and the monitor use. Real: [WindowsSystemImpl]; tests: a fake. */
interface WindowsSystem {
    suspend fun processes(): List<ProcessInfo>
    /** Asks a program to close normally (like clicking ×). Never forced: unsaved work prompts stay. */
    suspend fun closeGracefully(pid: Int): Boolean
    suspend fun isRunning(pid: Int): Boolean
    suspend fun start(path: String): Boolean
    suspend fun powerSchemes(): List<PowerScheme>
    suspend fun setPowerScheme(guid: String): Boolean
    suspend fun foregroundPid(): Int?
    /** Windows "Game Mode" switch (Settings → Gaming), null if unknown. Read only. */
    suspend fun gameModeEnabled(): Boolean?
    /** Hardware-accelerated GPU scheduling, null if unknown. Read only. */
    suspend fun gpuSchedulingEnabled(): Boolean?
    suspend fun openSettings(uri: String): Boolean
}

enum class ProcessClass(val label: String, val closable: Boolean) {
    ESSENTIAL("Windows", false), SECURITY("Security", false), HARDWARE("Drivers & hardware", false), AUDIO("Audio", false),
    NETWORK("Network", false), CIEL("Ciel", false), GAME("Game", false), LAUNCHER("Game launcher", false),
    BROWSER("Browser (may hold unsaved work)", true), COMMUNICATION("Chat / voice", true), SYNC("File sync", true),
    UPDATER("Updater", true), BACKGROUND_APP("Background app", true), USER_APP("Open app", true), UNKNOWN("Unknown", false),
}

/** Sorts running programs into what must stay and what may be closed for gaming. */
object ProcessClassifier {
    private val essential = setOf("system", "idle", "registry", "smss", "csrss", "wininit", "winlogon", "services", "lsass", "lsaiso", "svchost", "dwm",
        "explorer", "fontdrvhost", "sihost", "ctfmon", "taskhostw", "runtimebroker", "startmenuexperiencehost", "shellexperiencehost", "searchhost",
        "searchindexer", "searchapp", "textinputhost", "spoolsv", "wudfhost", "dllhost", "conhost", "smartscreen", "secure system", "memory compression",
        "applicationframehost", "systemsettings", "lockapp", "userinit", "wlanext", "dashost", "wmiprvse", "msiexec", "trustedinstaller", "tiworker",
        "powershell", "pwsh", "cmd", "windowsterminal", "openconsole", "taskmgr", "audiosrv", "backgroundtaskhost", "useroobebroker", "widgets", "widgetservice", "phoneexperiencehost")
    private val security = listOf("msmpeng", "nissrv", "securityhealth", "mpdefendercoreservice", "mbam", "avast", "avg", "ekrn", "egui", "bdagent", "vsserv", "mcshield", "norton", "kaspersky", "avp", "sophos", "crowdstrike", "csfalcon", "windefend", "smartscreen")
    private val hardware = listOf("nvdisplay.container", "nvcontainer", "nvidia", "amdrsserv", "radeonsoftware", "atiesrxx", "atieclxx", "igfx", "intel", "rtkaud", "realtek", "asus", "armourycrate",
        "lenovo", "dell", "hp", "msi", "razer", "logi", "synaptics", "elan", "dts", "nahimic", "dolby", "battery", "thermal", "acpi", "hotkey", "touchpad")
    private val audio = listOf("audiodg", "voicemeeter", "steelseries sonar")
    private val network = listOf("wlan", "vpn", "openvpn", "wireguard", "nordvpn", "expressvpn", "tailscale", "zerotier")
    private val launchers = listOf("steam", "steamwebhelper", "epicgameslauncher", "epicwebhelper", "battle.net", "riotclient", "riotclientservices", "vanguard", "vgc", "eadesktop", "origin",
        "ubisoftconnect", "upc", "galaxyclient", "minecraftlauncher", "minecraft", "xboxapp", "gamingservices", "gamebar", "easyanticheat", "battleye", "beservice")
    private val browsers = setOf("chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi")
    private val communication = listOf("discord", "teams", "ms-teams", "slack", "zoom", "skype", "whatsapp", "telegram")
    private val sync = listOf("onedrive", "dropbox", "googledrivefs", "googledrive", "icloud", "megasync", "box")
    private val updaters = listOf("update", "updater", "googleupdate", "adobearm", "adobeupdate", "armsvc", "jusched", "edgeupdate", "softwareupdate")
    private val backgroundApps = listOf("spotify", "adobe", "creative cloud", "ccxprocess", "cortana", "yourphone", "phonelink", "skypeapp", "epicwebhelper", "wallpaper", "lively", "rainmeter", "obs", "geforce experience", "nvidia share")

    fun classify(p: ProcessInfo, cielPids: Set<Int> = emptySet(), gamePid: Int? = null, keep: Set<String> = emptySet()): ProcessClass {
        val n = p.exe
        val path = p.path?.lowercase().orEmpty()
        return when {
            p.pid in cielPids || n == "ciel" -> ProcessClass.CIEL
            p.pid == gamePid -> ProcessClass.GAME
            n in keep -> ProcessClass.GAME
            n in essential || path.startsWith("c:\\windows\\") && n !in browsers -> ProcessClass.ESSENTIAL
            security.any { n.contains(it) } -> ProcessClass.SECURITY
            audio.any { n.contains(it) } -> ProcessClass.AUDIO
            network.any { n.contains(it) } -> ProcessClass.NETWORK
            launchers.any { n == it || n.startsWith(it) } -> ProcessClass.LAUNCHER
            hardware.any { n.contains(it) } || path.contains("\\drivers\\") -> ProcessClass.HARDWARE
            n in browsers -> ProcessClass.BROWSER
            communication.any { n.contains(it) } -> ProcessClass.COMMUNICATION
            sync.any { n.contains(it) } -> ProcessClass.SYNC
            updaters.any { n.contains(it) } -> ProcessClass.UPDATER
            backgroundApps.any { n.contains(it) } -> ProcessClass.BACKGROUND_APP
            p.windowTitle.isNullOrBlank().not() -> ProcessClass.USER_APP
            else -> ProcessClass.UNKNOWN
        }
    }
}

enum class GamingPreset(val label: String, val description: String) {
    BALANCED("Balanced Gaming", "Closes updaters and file sync; keeps your power plan."),
    MAXIMUM("Maximum Performance", "Also closes idle background apps and switches to the best available power plan."),
    CUSTOM("Custom", "Only what you allow; your keep list always stays open."),
}

/** A saved game setup, e.g. Minecraft: keep the launcher and Java, close what you allowed. */
data class GameProfile(
    val name: String,
    /** Executable names that identify the game (without .exe). */
    val exeNames: List<String>,
    /** Programs the game needs (never closed while this profile is active). */
    val keep: List<String> = emptyList(),
    val preset: GamingPreset = GamingPreset.BALANCED,
    /** Programs you allow Ciel to close for this game (Custom). */
    val allowClose: List<String> = emptyList(),
    /** Apply without asking each time (you authorised this profile). */
    val autoApprove: Boolean = false,
    /** Text in the command line that identifies the game (for Java games such as Minecraft). */
    val commandLineMarker: String? = null,
) {
    companion object {
        val MINECRAFT = GameProfile("Minecraft", listOf("javaw", "minecraft"), keep = listOf("minecraftlauncher", "minecraft", "javaw", "java"),
            commandLineMarker = "minecraft")
    }
}

data class PlannedClose(val process: ProcessInfo, val cls: ProcessClass, val reason: String, val reopen: Boolean)

data class GamingPlan(
    val preset: GamingPreset,
    val profile: GameProfile?,
    val close: List<PlannedClose>,
    val keep: List<Pair<ProcessInfo, ProcessClass>>,
    val powerScheme: PowerScheme?,
    val recommendations: List<String>,
) {
    val memoryFreedEstimate: Long get() = close.sumOf { it.process.memoryBytes }
}

/** Builds a plan from what is running. Nothing is changed here. */
object GamingPlanner {
    private const val HIGH = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"
    private const val ULTIMATE = "e9a42b02-d5df-448d-aa00-03f14749eb61"

    fun plan(processes: List<ProcessInfo>, preset: GamingPreset, profile: GameProfile?, schemes: List<PowerScheme>, gamePid: Int?,
             cielPids: Set<Int>, gameMode: Boolean?, gpuScheduling: Boolean?): GamingPlan {
        val keepNames = profile?.keep?.map { it.lowercase() }?.toSet().orEmpty()
        val allow = profile?.allowClose?.map { it.lowercase().removeSuffix(".exe") }?.toSet().orEmpty()
        val close = mutableListOf<PlannedClose>()
        val keep = mutableListOf<Pair<ProcessInfo, ProcessClass>>()
        // One decision per program (Chrome has many processes: close it once).
        for ((_, group) in processes.groupBy { it.exe }) {
            val main = group.maxByOrNull { it.memoryBytes }!!
            val cls = ProcessClassifier.classify(main, cielPids, gamePid, keepNames)
            val mem = group.sumOf { it.memoryBytes }
            val closeIt = when (preset) {
                GamingPreset.CUSTOM -> main.exe in allow && cls.closable
                GamingPreset.BALANCED -> cls == ProcessClass.UPDATER || cls == ProcessClass.SYNC || (main.exe in allow && cls.closable)
                GamingPreset.MAXIMUM -> cls == ProcessClass.UPDATER || cls == ProcessClass.SYNC || cls == ProcessClass.BACKGROUND_APP || (main.exe in allow && cls.closable)
            }
            val info = main.copy(memoryBytes = mem)
            if (closeIt) {
                val reason = when (cls) {
                    ProcessClass.UPDATER -> "Updater — downloads in the background; can run after gaming."
                    ProcessClass.SYNC -> "File sync — uses disk and network while you play; resumes when reopened."
                    ProcessClass.BACKGROUND_APP -> "Background app using ${human(mem)}."
                    ProcessClass.BROWSER -> "Browser using ${human(mem)}. It will ask about unsaved work before closing."
                    ProcessClass.COMMUNICATION -> "Chat app using ${human(mem)} (you allowed this)."
                    else -> "You allowed Ciel to close it (${human(mem)})."
                }
                close += PlannedClose(info, cls, reason, reopen = cls == ProcessClass.SYNC || cls == ProcessClass.COMMUNICATION)
            } else keep += info to cls
        }
        val active = schemes.firstOrNull { it.active }
        val target = if (preset == GamingPreset.MAXIMUM) schemes.firstOrNull { it.guid.equals(ULTIMATE, true) } ?: schemes.firstOrNull { it.guid.equals(HIGH, true) } else null
        val recs = buildList {
            if (gameMode == false) add("Windows Game Mode is off — turn it on in Settings → Gaming → Game Mode (Ciel can open the page).")
            if (gpuScheduling == false) add("Hardware-accelerated GPU scheduling is off — on many recent GPUs it helps; try it in Settings → Display → Graphics.")
            if (preset == GamingPreset.MAXIMUM && target == null) add("Only the ${active?.name ?: "current"} power plan exists on this laptop; Ciel leaves it as it is. Plug in the charger for best performance.")
            add("For a specific game you can choose \"High performance\" GPU in Settings → Display → Graphics.")
        }
        return GamingPlan(preset, profile, close.sortedByDescending { it.process.memoryBytes }, keep.sortedByDescending { it.first.memoryBytes },
            target?.takeIf { it.guid != active?.guid }, recs)
    }
}

/** What Gaming Mode changed, saved to disk before any change (so it can be undone even after a crash). */
data class GamingState(
    val active: Boolean,
    val startedAt: Long,
    val previousPowerScheme: String?,
    val appliedPowerScheme: String?,
    /** Programs Ciel closed: name, path, whether to reopen. */
    val closed: List<Triple<String, String?, Boolean>>,
    val notes: List<String>,
)

class GamingStateStore(private val file: File) {
    fun save(s: GamingState) {
        file.parentFile?.mkdirs()
        file.writeText(json {
            "active" to s.active; "startedAt" to s.startedAt; opt("prevPower", s.previousPowerScheme); opt("power", s.appliedPowerScheme)
            "closed" to s.closed.map { (n, p, r) -> json { "name" to n; opt("path", p); "reopen" to r } }; "notes" to s.notes
        }.toString())
    }
    fun load(): GamingState? = runCatching {
        val o = Json.parse(file.readText())
        GamingState(o["active"]?.bool ?: false, o["startedAt"]?.long ?: 0, o["prevPower"]?.string, o["power"]?.string,
            o["closed"]?.array.orEmpty().map { Triple(it["name"]?.string ?: "", it["path"]?.string, it["reopen"]?.bool ?: false) },
            o["notes"]?.array.orEmpty().mapNotNull { it.string })
    }.getOrNull()
    fun clear() { file.delete() }
}

/**
 * Turns Gaming Mode on and off. On: save the original state → switch power plan (if planned) →
 * close approved programs gracefully and verify. Off: restore the power plan, reopen the
 * programs Ciel closed (only those, and only if wanted), report what could not be restored.
 */
class GamingModeController(private val sys: WindowsSystem, private val store: GamingStateStore, private val now: () -> Long = System::currentTimeMillis) {

    val isActive: Boolean get() = store.load()?.active == true

    data class Report(val lines: List<String>, val ok: Boolean)

    suspend fun activate(plan: GamingPlan, approved: List<PlannedClose> = plan.close): Report {
        if (isActive) return Report(listOf("Gaming Mode is already on."), false)
        val schemes = sys.powerSchemes()
        val previous = schemes.firstOrNull { it.active }?.guid
        // 1. Record first.
        var state = GamingState(true, now(), previous, null, emptyList(), emptyList())
        store.save(state)
        val lines = mutableListOf<String>()
        // 2. Power plan.
        plan.powerScheme?.let { target ->
            if (sys.setPowerScheme(target.guid)) { lines += "Power plan: ${target.name} (was ${schemes.firstOrNull { it.active }?.name ?: "unknown"})."; state = state.copy(appliedPowerScheme = target.guid) }
            else lines += "Couldn't switch the power plan; left it unchanged."
            store.save(state)
        }
        // 3. Close approved programs, gracefully, and verify.
        val all = sys.processes()
        val closed = mutableListOf<Triple<String, String?, Boolean>>()
        for (c in approved) {
            val pids = all.filter { it.exe == c.process.exe }.map { it.pid }
            pids.forEach { sys.closeGracefully(it) }
            var gone = false
            repeat(10) { if (!gone) { delay(500); gone = pids.none { sys.isRunning(it) } } }
            if (gone) { closed += Triple(c.process.name, c.process.path, c.reopen); lines += "Closed ${c.process.name} (${human(c.process.memoryBytes)})." }
            else lines += "${c.process.name} didn't close (it may be asking about unsaved work, or refuses to close without force). Left running."
            state = state.copy(closed = closed.toList()); store.save(state)
        }
        if (approved.isEmpty() && plan.powerScheme == null) lines += "Nothing needed changing."
        return Report(lines, true)
    }

    suspend fun deactivate(reopenApps: Boolean = true): Report {
        val s = store.load() ?: return Report(listOf("Gaming Mode wasn't on."), false)
        val lines = mutableListOf<String>()
        var ok = true
        if (s.appliedPowerScheme != null && s.previousPowerScheme != null) {
            if (sys.setPowerScheme(s.previousPowerScheme)) lines += "Power plan restored."
            else { lines += "Couldn't restore the power plan — set it in Settings → System → Power."; ok = false }
        }
        if (reopenApps) for ((name, path, reopen) in s.closed) {
            if (!reopen) { lines += "$name stays closed (it starts again on its own or when you open it)."; continue }
            if (path == null) { lines += "Couldn't reopen $name (its location wasn't known)."; ok = false; continue }
            if (sys.start(path)) lines += "Reopened $name." else { lines += "Couldn't reopen $name."; ok = false }
        }
        store.clear()
        if (lines.isEmpty()) lines += "Nothing to restore."
        return Report(lines, ok)
    }
}

/** Which game is running: the foreground program matched against profiles and known game folders. */
object GameDetector {
    private val gameFolders = listOf("\\steamapps\\common\\", "\\epic games\\", "\\riot games\\", "\\xboxgames\\", "\\gog galaxy\\games\\", "\\ea games\\", "\\ubisoft game launcher\\games\\")

    fun detect(processes: List<ProcessInfo>, foregroundPid: Int?, profiles: List<GameProfile>): Pair<ProcessInfo, GameProfile?>? {
        val fg = processes.firstOrNull { it.pid == foregroundPid }
        val candidates = listOfNotNull(fg) + processes
        for (p in candidates) {
            val prof = profiles.firstOrNull { pr ->
                p.exe in pr.exeNames.map { it.lowercase() } && (pr.commandLineMarker == null || p.commandLine?.contains(pr.commandLineMarker, true) == true || p.windowTitle?.contains(pr.name, true) == true)
            }
            if (prof != null) return p to prof
            if (p === fg && gameFolders.any { p.path?.lowercase()?.contains(it) == true }) return p to null
        }
        return null
    }
}

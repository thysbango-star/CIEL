package dev.ciel.windows

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.int
import dev.ciel.skills.str
import dev.ciel.task.ConfirmationRequest
import dev.ciel.task.Risk
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** How much Ciel may do without asking (always asks for deletion of personal files, installs, security). */
enum class Autonomy(val label: String, val description: String) {
    ASSIST("Assist", "Ciel analyses and recommends; you approve every action."),
    ASSISTED("Assisted autonomy", "Low-risk housekeeping (temp/cache cleanup, closing updaters) without asking; anything else asks."),
    AUTHORISED("Authorised autonomy", "Your saved workflows (e.g. a game profile) run without asking, within their limits."),
}

/** Everything the Windows tools share: one place, created by the desktop app. */
class WindowsServices(
    val shell: Shell,
    val system: WindowsSystem,
    val locations: KnownLocations,
    val dataDir: File,
    val moveToTrash: (File) -> Boolean,
    val autonomy: () -> Autonomy,
    val gameProfiles: () -> List<GameProfile>,
    val exclusions: () -> List<java.nio.file.Path> = { emptyList() },
) {
    val scanner = StorageScanner(locations)
    val planner = CleanupPlanner(locations)
    val executor = CleanupExecutor(locations, shell, moveToTrash, File(dataDir, "cleanup-log.jsonl"))
    val history = ScanHistory(File(dataDir, "scan-history.json"))
    val gaming = GamingModeController(system, GamingStateStore(File(dataDir, "gaming-state.json")))
    val monitor = PerfMonitor(shell)
    val health = PcHealth(shell, monitor)
    val downloads = DownloadManager(shell, File(locations.downloads.toFile(), "Ciel"))
    val activity = ActivityLog(File(dataDir, "activity.jsonl"))
    @Volatile var lastScan: ScanResult? = null
    @Volatile var lastPlan: CleanupPlanner.Plan? = null

    /** The user folders scanned by a quick scan (fast, where personal storage usually goes). */
    fun quickRoots() = listOfNotNull(locations.home) + locations.programDirs + locations.tempDirs.filter { t -> !t.startsWith(locations.home) }

    suspend fun scan(roots: List<java.nio.file.Path>, onProgress: (Long, Long, String) -> Unit = { _, _, _ -> }, exclusions: List<java.nio.file.Path> = this.exclusions()): ScanResult =
        scanner.scan(ScanOptions(roots, exclusions), onProgress).also { r ->
            lastScan = r
            lastPlan = null
            runCatching { history.add(r, Drives.list()) }
            activity.add("Storage scan of ${roots.size} location(s): ${human(r.totalBytes)}", "You asked what is using storage", "read-only", true)
        }

    suspend fun plan(withDuplicates: Boolean = false): CleanupPlanner.Plan? {
        val s = lastScan ?: return null
        val dups = if (withDuplicates) DuplicateFinder.find(s.largestFiles + s.categories.flatMap { it.top }).first else emptyList()
        return planner.plan(s, CleanupExecutor.recycleBinBytes(shell), dups).also { lastPlan = it }
    }

    fun processPids(): Set<Int> = setOf(ProcessHandle.current().pid().toInt())
}

/**
 * Ciel's tools on Windows. Reading is free; changes go through the confirmation gate (or the
 * autonomy level for low-risk housekeeping); every change is written to the activity log.
 */
class WindowsPcSkill(private val s: WindowsServices) : Skill {
    override val id = "windows_pc"
    override val displayName = "Windows PC"
    override val description = "Storage analysis and cleanup, Gaming Mode, processes, PC health and permission-based downloads on this Windows PC."
    override val worksOffline = true
    override val tools = listOf(
        ToolSpec("drives", "List drives with capacity, used and free space.", Schema.obj()),
        ToolSpec("scan_storage", "Scan storage (file sizes only, nothing is uploaded) and summarise what uses space. scope: quick = your user folder, programs and temp; drive = a whole drive (slower).",
            Schema.obj(emptyList(), "scope" to Schema.str("quick or drive", listOf("quick", "drive")), "drive" to Schema.str("Drive root for scope=drive, e.g. C:\\"))),
        ToolSpec("find_large_files", "Largest files from the last scan, optionally inside one folder and above a size. sort: largest, oldest, newest.",
            Schema.obj(emptyList(), "folder" to Schema.str("Folder path or name (e.g. Downloads, .minecraft)"), "min_mb" to Schema.int("Minimum size in MB"),
                "sort" to Schema.str("Order", listOf("largest", "oldest", "newest")), "limit" to Schema.int("Max results (1-50)"))),
        ToolSpec("cleanup_suggestions", "Cleanup opportunities from the last scan, grouped as low risk / review / protected. Never deletes anything.",
            Schema.obj(emptyList(), "duplicates" to dev.ciel.skills.Schema.bool("Also look for identical duplicate files (slower)"))),
        ToolSpec("clean_up", "Remove items the user chose from cleanup_suggestions. Personal files go to the Recycle Bin. Always asks the user to confirm the list first.",
            Schema.obj(listOf("paths"), "paths" to Schema.str("Exact paths from cleanup_suggestions, separated by |"))),
        ToolSpec("gaming_mode", "Gaming Mode. action: status | plan (show what would change) | on | off. preset: balanced, maximum, custom.",
            Schema.obj(listOf("action"), "action" to Schema.str("What to do", listOf("status", "plan", "on", "off")), "preset" to Schema.str("Preset", listOf("balanced", "maximum", "custom")),
                "game" to Schema.str("Game profile name, e.g. Minecraft"))),
        ToolSpec("system_status", "CPU, memory, GPU (where measurable) and the programs using the most memory.", Schema.obj()),
        ToolSpec("pc_health", "Read-only PC health check: storage, memory, startup apps, battery, power plan, Windows Update, graphics driver, app errors.", Schema.obj()),
        ToolSpec("startup_apps", "Programs that start with Windows.", Schema.obj()),
        ToolSpec("open_location", "Open a folder, or show a file in File Explorer.", Schema.obj(listOf("path"), "path" to Schema.str("File or folder path"))),
        ToolSpec("download_file", "Download a file the user asked for, after showing them the details and getting approval. HTTPS only; programs are checked and never run.",
            Schema.obj(listOf("url"), "url" to Schema.str("https URL of the file, preferably from the official publisher"), "sha256" to Schema.str("Optional published SHA-256 to verify"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome = when (tool) {
        "drives" -> ToolOutcome.ok(Drives.list().joinToString("\n") { d -> "${d.root} ${d.label ?: ""} ${d.fileSystem ?: ""}: ${human(d.usedBytes)} used of ${human(d.totalBytes)} (${d.usedPercent}%), ${human(d.freeBytes)} free" }, true)
        "scan_storage" -> {
            val roots = if (args.str("scope") == "drive") listOf(Paths.get(args.str("drive") ?: "C:\\")) else s.quickRoots()
            ToolOutcome.ok(s.scan(roots).summary(), true)
        }
        "find_large_files" -> {
            val scan = s.lastScan ?: s.scan(s.quickRoots())
            val folder = args.str("folder")?.let { resolveFolder(it) }
            val min = (args.int("min_mb") ?: 0) * 1_000_000L
            var list = (scan.largestFiles + scan.categories.flatMap { it.top }).distinctBy { it.path }.filter { it.size >= min && (folder == null || it.path.lowercase().startsWith(folder.lowercase())) }
            list = when (args.str("sort")) { "oldest" -> list.sortedBy { it.modified }; "newest" -> list.sortedByDescending { it.modified }; else -> list.sortedByDescending { it.size } }
            val n = (args.int("limit") ?: 15).coerceIn(1, 50)
            if (list.isEmpty()) ToolOutcome.ok("No files matched in the last scan" + (folder?.let { " under $it" } ?: "") + ". (Only the largest files are kept per scan.)", true)
            else ToolOutcome.ok(list.take(n).joinToString("\n") { "${human(it.size)}  ${it.path}  (${it.category.label}, modified ${java.time.Instant.ofEpochMilli(it.modified).toString().take(10)})" }, true)
        }
        "cleanup_suggestions" -> {
            if (s.lastScan == null) s.scan(s.quickRoots())
            val p = s.plan(args["duplicates"]?.bool == true)!!
            ToolOutcome.ok(buildString {
                append("LOW RISK (${human(p.lowBytes)}):\n"); p.low.take(15).forEach { append("- ${it.path} — ${human(it.size)}: ${it.reason} [${it.method.label}]\n") }
                append("\nREVIEW (${human(p.reviewBytes)}):\n"); p.review.take(20).forEach { append("- ${it.path} — ${human(it.size)}: ${it.reason}\n") }
                if (p.protected.isNotEmpty()) { append("\nPROTECTED (not removable by Ciel):\n"); p.protected.take(8).forEach { append("- ${it.path} — ${human(it.size)}: ${it.reason}\n") } }
                append("\nNothing has been removed. Ask the user which items to clean.")
            }, true)
        }
        "clean_up" -> cleanUp(args.str("paths").orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }, ctx)
        "gaming_mode" -> gaming(args, ctx)
        "system_status" -> {
            val b = s.monitor.basic()
            val top = s.system.processes().groupBy { it.exe }.map { (n, g) -> n to g.sumOf { it.memoryBytes } }.sortedByDescending { it.second }.take(8)
            ToolOutcome.ok("CPU ${b.cpuPercent?.let { "%.0f%%".format(it) } ?: "?"}, memory ${b.ramUsedBytes?.let(::human)} of ${b.ramTotalBytes?.let(::human)}.\nTop memory: " +
                top.joinToString { "${it.first} ${human(it.second)}" } + "\n(GPU usage appears in the Gaming Mode monitor where Windows provides it.)", true)
        }
        "pc_health" -> ToolOutcome.ok(s.health.check().joinToString("\n") { "${it.status}: ${it.area} — ${it.detail}" + (it.recommendation?.let { r -> " → $r" } ?: "") }, true)
        "startup_apps" -> ToolOutcome.ok(s.health.startupAppList().joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "None found." }, true)
        "open_location" -> {
            val p = args.str("path") ?: ""
            val target = resolveFolder(p) ?: p
            if (!File(target).exists()) ToolOutcome.fail("$target doesn't exist.")
            else { (s.system as? WindowsSystemImpl)?.openFolder(target); s.activity.add("Opened $target in File Explorer", "You asked", "read-only", true); ToolOutcome.ok("Opened $target.", true) }
        }
        "download_file" -> download(args.str("url") ?: "", args.str("sha256"), ctx)
        else -> ToolOutcome.fail("Unknown tool $tool")
    }

    private fun resolveFolder(name: String): String? {
        val loc = s.locations
        val known = mapOf("downloads" to loc.downloads, "documents" to loc.documents, "videos" to loc.videos, "pictures" to loc.pictures, "music" to loc.music,
            "desktop" to loc.desktop, ".minecraft" to loc.roamingAppData?.resolve(".minecraft"), "minecraft" to loc.roamingAppData?.resolve(".minecraft"))
        known[name.trim().lowercase()]?.let { return it.toString() }
        return if (Files.exists(Paths.get(name))) name else null
    }

    private suspend fun cleanUp(paths: List<String>, ctx: SkillContext): ToolOutcome {
        val plan = s.lastPlan ?: s.plan() ?: return ToolOutcome.fail("Run cleanup_suggestions first.")
        val all = plan.low + plan.review
        val chosen = paths.mapNotNull { p -> all.firstOrNull { it.path.equals(p, ignoreCase = true) } }
        val refused = paths.filter { p -> chosen.none { it.path.equals(p, true) } }
        if (chosen.isEmpty()) return ToolOutcome.fail("None of those paths are in the current cleanup suggestions (protected items can't be removed).")
        val lowOnly = chosen.all { it.risk == CleanupRisk.LOW && it.method == CleanupMethod.DELETE }
        val skipConfirm = lowOnly && s.autonomy() != Autonomy.ASSIST
        if (!skipConfirm) {
            val detail = chosen.joinToString("\n") { "• ${it.path} (${human(it.size)}) — ${it.method.label}" } +
                "\n\nTotal: ${human(chosen.sumOf { it.size })}." + (if (chosen.any { !it.method.reversible }) "\nItems marked \"permanently\" can't be undone." else "")
            val ok = ctx.confirm.confirm(ConfirmationRequest("Clean up ${chosen.size} item(s)?", detail, Risk.CONSEQUENTIAL, "Clean up storage"))
            if (!ok) return ToolOutcome.ok("You declined; nothing was removed.", true)
        }
        val records = s.executor.execute(chosen)
        val freed = records.sumOf { it.bytes }
        records.forEach { s.activity.add("Cleanup: ${it.path} — ${it.note}", "From cleanup suggestions", if (skipConfirm) "autonomy: ${s.autonomy().label}" else "you approved", it.ok) }
        return ToolOutcome.ok("Freed ${human(freed)}.\n" + records.joinToString("\n") { "${if (it.ok) "✓" else "✗"} ${it.path}: ${it.note}" } +
            (if (refused.isNotEmpty()) "\nNot in the suggestions (left alone): ${refused.joinToString()}" else ""), true)
    }

    private suspend fun gaming(args: JsonObject, ctx: SkillContext): ToolOutcome {
        val action = args.str("action") ?: "status"
        if (action == "status") return ToolOutcome.ok(if (s.gaming.isActive) "Gaming Mode is ON." else "Gaming Mode is off.", true)
        if (action == "off") {
            val r = s.gaming.deactivate()
            s.activity.add("Gaming Mode off", "You asked", "restore", r.ok)
            return ToolOutcome.ok(r.lines.joinToString("\n"), true)
        }
        val profile = args.str("game")?.let { g -> s.gameProfiles().firstOrNull { it.name.equals(g, true) } }
        val preset = when (args.str("preset")) { "maximum" -> GamingPreset.MAXIMUM; "custom" -> GamingPreset.CUSTOM; else -> profile?.preset ?: GamingPreset.BALANCED }
        val procs = s.system.processes()
        val fg = s.system.foregroundPid()
        val detected = GameDetector.detect(procs, fg, s.gameProfiles())
        val prof = profile ?: detected?.second
        val plan = GamingPlanner.plan(procs, preset, prof, s.system.powerSchemes(), detected?.first?.pid, s.processPids(), s.system.gameModeEnabled(), s.system.gpuSchedulingEnabled())
        val text = buildString {
            append("${plan.preset.label}${prof?.let { " for ${it.name}" } ?: ""}${detected?.let { " (game running: ${it.first.name})" } ?: ""}.\n")
            if (plan.close.isEmpty()) append("Nothing needs closing.\n") else { append("Would close:\n"); plan.close.forEach { append("- ${it.process.name}: ${it.reason}\n") } }
            plan.powerScheme?.let { append("Would switch the power plan to ${it.name}.\n") }
            plan.recommendations.forEach { append("Tip: $it\n") }
            append("Kept open: Windows, security, drivers, audio, network, game launchers and the game.")
        }
        if (action == "plan") return ToolOutcome.ok(text + "\nNothing has changed yet.", true)
        val authorised = (prof?.autoApprove == true && s.autonomy() == Autonomy.AUTHORISED)
        if (!authorised && !ctx.confirm.confirm(ConfirmationRequest("Turn on Gaming Mode?", text, Risk.CONSEQUENTIAL, "Gaming Mode"))) return ToolOutcome.ok("You declined; nothing changed.", true)
        val r = s.gaming.activate(plan)
        s.activity.add("Gaming Mode on (${plan.preset.label})", "You asked", if (authorised) "authorised profile ${prof?.name}" else "you approved", r.ok)
        return ToolOutcome.ok(r.lines.joinToString("\n") + "\nSay \"turn off Gaming Mode\" to restore everything.", true)
    }

    private suspend fun download(url: String, sha: String?, ctx: SkillContext): ToolOutcome {
        val p = s.downloads.inspect(url)
        if (p.blocked) return ToolOutcome.fail(p.warnings.first { it.startsWith("BLOCKED") }.removePrefix("BLOCKED: "))
        val detail = "File: ${p.fileName}\nFrom: ${p.host}${p.knownPublisher?.let { " ($it)" } ?: " (publisher not recognised)"}\nSize: ${p.sizeBytes?.let(::human) ?: "unknown"}\n" +
            "Type: ${p.contentType ?: "unknown"}${if (p.executable) " — a program (will be checked, never run)" else ""}\nSave to: Downloads\\Ciel\\${p.category.folder}" +
            (if (p.warnings.isNotEmpty()) "\n\nWarnings:\n" + p.warnings.joinToString("\n") { "• $it" } else "")
        if (!ctx.confirm.confirm(ConfirmationRequest("Download ${p.fileName}?", detail, Risk.CONSEQUENTIAL, "Download a file"))) return ToolOutcome.ok("You declined; nothing was downloaded.", true)
        return try {
            val r = s.downloads.download(p, sha)
            s.activity.add("Downloaded ${p.fileName} (${human(r.bytes)}) from ${p.host}", "You asked", "you approved", true)
            ToolOutcome.ok("Saved to ${r.file.absolutePath} (${human(r.bytes)}).\nSHA-256: ${r.sha256}" + (r.hashMatches?.let { if (it) " ✓ matches" else " ✗ DOES NOT MATCH" } ?: "") +
                (r.signature?.let { "\nSignature: $it${r.signer?.let { s -> " ($s)" } ?: ""}" } ?: "") +
                (r.defenderClean?.let { "\nMicrosoft Defender: ${if (it) "no threats found" else "THREAT FOUND"}" } ?: "") +
                r.notes.joinToString("") { "\n⚠ $it" } + "\nIt has not been opened or installed.", true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            s.activity.add("Download of ${p.fileName} failed", "You asked", "you approved", false)
            ToolOutcome.fail("Download failed: ${e.message}")
        }
    }
}

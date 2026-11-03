package dev.ciel.testing

import dev.ciel.core.json.json
import dev.ciel.skills.SkillContext
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.EmergencyStop
import dev.ciel.task.TaskManager
import dev.ciel.windows.Autonomy
import dev.ciel.windows.BatteryReport
import dev.ciel.windows.CleanupExecutor
import dev.ciel.windows.CleanupMethod
import dev.ciel.windows.CleanupPlanner
import dev.ciel.windows.CleanupRisk
import dev.ciel.windows.DownloadManager
import dev.ciel.windows.DuplicateFinder
import dev.ciel.windows.GameDetector
import dev.ciel.windows.GameProfile
import dev.ciel.windows.GamingModeController
import dev.ciel.windows.GamingPlanner
import dev.ciel.windows.GamingPreset
import dev.ciel.windows.GamingStateStore
import dev.ciel.windows.KnownLocations
import dev.ciel.windows.NvidiaSmi
import dev.ciel.windows.PowerScheme
import dev.ciel.windows.ProcessClass
import dev.ciel.windows.ProcessClassifier
import dev.ciel.windows.ProcessInfo
import dev.ciel.windows.ProtectionPolicy
import dev.ciel.windows.ScanOptions
import dev.ciel.windows.Shell
import dev.ciel.windows.ShellResult
import dev.ciel.windows.StorageCategory
import dev.ciel.windows.StorageScanner
import dev.ciel.windows.TypeperfParser
import dev.ciel.windows.WindowsPcSkill
import dev.ciel.windows.WindowsServices
import dev.ciel.windows.WindowsSystem
import dev.ciel.windows.WindowsSystemImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private class FakeShell(val handler: (List<String>) -> ShellResult = { ShellResult(0, "", "") }) : Shell {
    val calls = mutableListOf<List<String>>()
    override suspend fun run(command: List<String>, timeoutMs: Long): ShellResult { calls += command; return handler(command) }
}

private class FakeWindows(var procs: MutableList<ProcessInfo>, var schemes: MutableList<PowerScheme>, val refuseClose: Set<String> = emptySet()) : WindowsSystem {
    val started = mutableListOf<String>()
    override suspend fun processes() = procs.toList()
    override suspend fun closeGracefully(pid: Int): Boolean {
        val p = procs.firstOrNull { it.pid == pid } ?: return false
        if (p.exe in refuseClose) return true // sent the request, but the app stays (unsaved work prompt)
        procs.removeAll { it.pid == pid }; return true
    }
    override suspend fun isRunning(pid: Int) = procs.any { it.pid == pid }
    override suspend fun start(path: String): Boolean { started += path; return true }
    override suspend fun powerSchemes() = schemes.toList()
    override suspend fun setPowerScheme(guid: String): Boolean { schemes = schemes.map { it.copy(active = it.guid == guid) }.toMutableList(); return true }
    override suspend fun foregroundPid(): Int? = procs.firstOrNull { it.exe == "javaw" }?.pid
    override suspend fun gameModeEnabled() = false
    override suspend fun gpuSchedulingEnabled() = null
    override suspend fun openSettings(uri: String) = true
}

/** A fake Windows user profile on disk. */
private fun profile(): Pair<Path, KnownLocations> {
    val root = Files.createTempDirectory("ciel-win")
    val home = root.resolve("Users/Ana"); val pf = root.resolve("Program Files"); val win = root.resolve("Windows")
    val local = home.resolve("AppData/Local"); val roaming = home.resolve("AppData/Roaming")
    val temp = local.resolve("Temp")
    listOf(home.resolve("Downloads"), home.resolve("Videos/Captures"), home.resolve("Documents"), temp, pf.resolve("App"), win.resolve("System32"),
        local.resolve("Google/Chrome/User Data/Default/Cache"), roaming.resolve(".minecraft/saves/World1")).forEach { Files.createDirectories(it) }
    return root to KnownLocations(home, listOf(pf), win, listOf(temp), local, roaming, root.resolve("ProgramData"))
}

private fun file(p: Path, bytes: Int, daysOld: Int = 0, fill: Byte = 1): Path {
    Files.createDirectories(p.parent)
    Files.write(p, ByteArray(bytes) { fill })
    if (daysOld > 0) Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - daysOld * 86_400_000L))
    return p
}

private val schemes = mutableListOf(PowerScheme("381b4222-f694-41f0-9685-ff5bb260df2e", "Balanced", true), PowerScheme("8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c", "High performance", false))

fun windowsTests() = T.suite("windows") {
    T.test("storage scan: categories, largest files and folders, junction-style links skipped, nothing read but metadata") {
        val (root, loc) = profile()
        file(loc.downloads.resolve("setup-old.exe"), 3_000_000, daysOld = 60)
        file(loc.videos.resolve("Captures/clip.mp4"), 5_000_000)
        file(loc.tempDirs.first().resolve("junk.tmp"), 1_000_000, daysOld = 3)
        file(loc.localAppData!!.resolve("Google/Chrome/User Data/Default/Cache/data_1"), 2_000_000)
        file(root.resolve("Program Files/App/app.exe"), 4_000_000)
        file(loc.documents.resolve("thesis.docx"), 100_000)
        runCatching { Files.createSymbolicLink(loc.home.resolve("Link to PF"), root.resolve("Program Files")) }
        val r = StorageScanner(loc).scan(ScanOptions(listOf(loc.home, root.resolve("Program Files"))))
        fun cat(c: StorageCategory) = r.categories.first { it.category == c }.bytes
        eq(3_000_000L, cat(StorageCategory.DOWNLOADS)); eq(5_000_000L, cat(StorageCategory.VIDEOS)); eq(1_000_000L, cat(StorageCategory.TEMP))
        eq(2_000_000L, cat(StorageCategory.CACHE)); eq(4_000_000L, cat(StorageCategory.APPS)) // counted once: the link isn't followed
        eq("clip.mp4", r.largestFiles.first().path.substringAfterLast('/').substringAfterLast('\\'))
        check(r.largestFolders.any { it.path.endsWith("Videos") && it.size == 5_000_000L }) { r.largestFolders.toString() }
        eq(15_100_000L, r.totalBytes)
    }

    T.test("duplicates are confirmed by content, not just size") {
        val (_, loc) = profile()
        val a = file(loc.downloads.resolve("a.zip"), 2_000_000, fill = 7)
        file(loc.documents.resolve("copy of a.zip"), 2_000_000, fill = 7)
        file(loc.documents.resolve("same size different.zip"), 2_000_000, fill = 9)
        val scan = StorageScanner(loc).scan(ScanOptions(listOf(loc.home)))
        val (groups, partial) = DuplicateFinder.find(scan.largestFiles)
        eq(1, groups.size); eq(2, groups.single().paths.size); check(!partial)
        check(groups.single().paths.any { it == a.toString() })
    }

    T.test("protection: Windows, programs, saved games, password databases and browser profiles are never cleanup items") {
        val (root, loc) = profile()
        val p = ProtectionPolicy(loc)
        check(p.isProtected(root.resolve("Windows/System32/x.dll")))
        check(p.isProtected(root.resolve("Program Files/App/app.exe")))
        check(p.isProtected(loc.roamingAppData!!.resolve(".minecraft/saves/World1/level.dat")))
        check(p.isProtected(loc.documents.resolve("passwords.kdbx")))
        check(p.isProtected(loc.localAppData!!.resolve("Google/Chrome/User Data/Default/Login Data")))
        check(!p.isProtected(loc.localAppData!!.resolve("Google/Chrome/User Data/Default/Cache/data_1")))
        check(!p.isProtected(loc.downloads.resolve("setup.exe")))
        check(!p.isProtected(loc.tempDirs.first().resolve("x.tmp")))
    }

    T.test("cleanup plan: temp/cache low risk, old installer for review, nothing personal is low risk") {
        val (_, loc) = profile()
        file(loc.downloads.resolve("setup-old.exe"), 3_000_000, daysOld = 60)
        file(loc.downloads.resolve("setup-new.exe"), 3_000_000, daysOld = 2)
        file(loc.tempDirs.first().resolve("junk.tmp"), 1_000_000, daysOld = 3)
        file(loc.documents.resolve("big-but-mine.mkv"), 2_000_000)
        val scan = StorageScanner(loc).scan(ScanOptions(listOf(loc.home)))
        val plan = CleanupPlanner(loc).plan(scan, recycleBinBytes = 500)
        check(plan.low.any { it.path.endsWith("Temp") && it.method == CleanupMethod.DELETE }) { plan.low.toString() }
        check(plan.low.any { it.method == CleanupMethod.EMPTY_RECYCLE_BIN })
        check(plan.review.single { it.path.endsWith("setup-old.exe") }.method == CleanupMethod.RECYCLE)
        check(plan.review.none { it.path.endsWith("setup-new.exe") }) // recent installer: not suggested
        check((plan.low + plan.review).none { it.path.endsWith("big-but-mine.mkv") }) // large isn't a reason by itself
    }

    T.test("cleanup execution: only approved items; personal files to the Recycle Bin; temp keeps recent files; protected refused; logged") {
        val (root, loc) = profile()
        val old = file(loc.tempDirs.first().resolve("old.tmp"), 1000, daysOld = 3)
        val recent = file(loc.tempDirs.first().resolve("recent.tmp"), 1000)
        val installer = file(loc.downloads.resolve("setup-old.exe"), 3000, daysOld = 60)
        val trashed = mutableListOf<File>()
        val log = root.resolve("log.jsonl").toFile()
        val ex = CleanupExecutor(loc, FakeShell(), { f -> trashed += f; f.delete() }, log)
        val recs = ex.execute(listOf(
            dev.ciel.windows.CleanupCandidate(loc.tempDirs.first().toString(), 2000, "temp", CleanupRisk.LOW, CleanupMethod.DELETE, isFolder = true),
            dev.ciel.windows.CleanupCandidate(installer.toString(), 3000, "old", CleanupRisk.REVIEW, CleanupMethod.RECYCLE),
            dev.ciel.windows.CleanupCandidate(root.resolve("Windows/System32").toString(), 1, "x", CleanupRisk.LOW, CleanupMethod.DELETE),
        ))
        check(!Files.exists(old) && Files.exists(recent))
        eq(listOf(installer.toFile()), trashed)
        check(!recs[2].ok && "refused" in recs[2].note)
        eq(3, ex.history().size)
    }

    T.test("processes: Windows, security, drivers, launchers and the game are kept; updaters, sync and background apps can close") {
        fun c(n: String, path: String? = null, title: String? = null) = ProcessClassifier.classify(ProcessInfo(1, n, path, windowTitle = title))
        eq(ProcessClass.ESSENTIAL, c("svchost.exe")); eq(ProcessClass.ESSENTIAL, c("explorer.exe"))
        eq(ProcessClass.SECURITY, c("MsMpEng.exe")); eq(ProcessClass.HARDWARE, c("NVDisplay.Container.exe")); eq(ProcessClass.AUDIO, c("audiodg.exe"))
        eq(ProcessClass.LAUNCHER, c("steam.exe")); eq(ProcessClass.LAUNCHER, c("MinecraftLauncher.exe"))
        eq(ProcessClass.UPDATER, c("GoogleUpdate.exe")); eq(ProcessClass.SYNC, c("OneDrive.exe")); eq(ProcessClass.BROWSER, c("chrome.exe"))
        eq(ProcessClass.COMMUNICATION, c("Discord.exe")); eq(ProcessClass.BACKGROUND_APP, c("Spotify.exe"))
        check(!ProcessClass.ESSENTIAL.closable && !ProcessClass.SECURITY.closable && ProcessClass.UPDATER.closable)
    }

    T.test("Gaming Mode plan: balanced vs maximum; Minecraft profile keeps Java; browsers and Discord stay unless allowed") {
        val procs = listOf(ProcessInfo(10, "javaw.exe", "C:\\Program Files\\Java\\bin\\javaw.exe", 1_500_000_000, commandLine = "javaw -cp minecraft.jar net.minecraft.client.main.Main"),
            ProcessInfo(11, "OneDrive.exe", "C:\\OneDrive.exe", 200_000_000), ProcessInfo(12, "GoogleUpdate.exe", null, 20_000_000),
            ProcessInfo(13, "Spotify.exe", "C:\\Spotify.exe", 300_000_000), ProcessInfo(14, "chrome.exe", null, 900_000_000, windowTitle = "Docs"),
            ProcessInfo(15, "chrome.exe", null, 400_000_000), ProcessInfo(16, "Discord.exe", null, 350_000_000), ProcessInfo(17, "MsMpEng.exe", null, 150_000_000))
        val detected = GameDetector.detect(procs, 10, listOf(GameProfile.MINECRAFT))!!
        eq("Minecraft", detected.second!!.name)
        val bal = GamingPlanner.plan(procs, GamingPreset.BALANCED, detected.second, schemes, 10, emptySet(), false, null)
        eq(setOf("onedrive", "googleupdate"), bal.close.map { it.process.exe }.toSet()); eq(null, bal.powerScheme)
        check(bal.recommendations.any { "Game Mode is off" in it })
        val max = GamingPlanner.plan(procs, GamingPreset.MAXIMUM, detected.second, schemes, 10, emptySet(), true, null)
        eq(setOf("onedrive", "googleupdate", "spotify"), max.close.map { it.process.exe }.toSet())
        eq("High performance", max.powerScheme?.name)
        check(max.keep.any { it.first.exe == "javaw" && it.second == ProcessClass.GAME } && max.keep.any { it.first.exe == "chrome" } && max.keep.any { it.first.exe == "discord" })
        eq(1_300_000_000L, max.keep.first { it.first.exe == "chrome" }.first.memoryBytes) // one decision per program
        val custom = GamingPlanner.plan(procs, GamingPreset.CUSTOM, GameProfile.MINECRAFT.copy(allowClose = listOf("discord.exe", "javaw")), schemes, 10, emptySet(), true, null)
        eq(listOf("discord"), custom.close.map { it.process.exe }) // allowing the game itself is ignored: it's kept
    }

    T.test("Gaming Mode on/off: state saved before changes, graceful closes verified, everything restored — even after a restart of Ciel") {
        val dir = Files.createTempDirectory("ciel-gm").toFile()
        val procs = mutableListOf(ProcessInfo(11, "OneDrive.exe", "C:\\OneDrive.exe", 200_000_000), ProcessInfo(12, "GoogleUpdate.exe", null, 20_000_000),
            ProcessInfo(13, "Spotify.exe", "C:\\Spotify.exe", 300_000_000))
        val win = FakeWindows(procs, schemes.toMutableList(), refuseClose = setOf("spotify"))
        val plan = GamingPlanner.plan(procs, GamingPreset.MAXIMUM, null, win.schemes, null, emptySet(), true, null)
        val on = GamingModeController(win, GamingStateStore(File(dir, "gaming-state.json"))).activate(plan)
        check(on.lines.any { "High performance" in it }); check(on.lines.any { "Spotify" in it && "didn't close" in it })
        eq("High performance", win.schemes.single { it.active }.name)
        check(File(dir, "gaming-state.json").exists())
        // A new controller (Ciel restarted) still restores from the saved state.
        val off = GamingModeController(win, GamingStateStore(File(dir, "gaming-state.json"))).deactivate()
        eq("Balanced", win.schemes.single { it.active }.name)
        eq(listOf("C:\\OneDrive.exe"), win.started) // sync app reopened; the updater isn't restarted; Spotify was never closed
        check(off.ok && !File(dir, "gaming-state.json").exists())
    }

    T.test("Windows tool output parsers: powercfg, typeperf, nvidia-smi, battery report") {
        val ps = WindowsSystemImpl.parsePowerList("Existing Power Schemes (* Active)\n-----------------------------------\nPower Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced) *\nPower Scheme GUID: 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c  (High performance)\n")
        eq(2, ps.size); check(ps[0].active && !ps[1].active); eq("High performance", ps[1].name)
        val tp = TypeperfParser()
        eq(null, tp.line("\"(PDH-CSV 4.0)\",\"\\\\PC\\Processor(_Total)\\% Processor Time\",\"\\\\PC\\GPU Engine(pid_1_luid_0x0_phys_0_eng_0_engtype_3D)\\Utilization Percentage\",\"\\\\PC\\GPU Engine(pid_2_luid_0x0_phys_0_eng_0_engtype_3D)\\Utilization Percentage\",\"\\\\PC\\GPU Adapter Memory(luid_0x0_phys_0)\\Dedicated Usage\",\"\\\\PC\\PhysicalDisk(_Total)\\% Disk Time\""))
        val v = tp.line("\"11/03/2026 22:40:01.123\",\"12.5\",\"30.0\",\"25.5\",\"1073741824\",\"4.0\"")!!
        eq(12.5, v["cpu"]); eq(55.5, v["gpu"]); eq(1073741824.0, v["gpumem"]); eq(4.0, v["disk"])
        eq(Triple(61.0, 37.0, 2048L * 1024 * 1024), NvidiaSmi.parse("61, 37, 2048\n"))
        eq(82, BatteryReport.parse("<Batteries><Battery><DesignCapacity>50000</DesignCapacity><FullChargeCapacity>41000</FullChargeCapacity></Battery></Batteries>"))
    }

    T.test("downloads: HTTPS only, disguised programs blocked, known publishers recognised") {
        val dm = DownloadManager(FakeShell(), Files.createTempDirectory("dl").toFile())
        check(dm.inspect("http://example.com/file.zip").blocked)
        check(dm.inspect("not a url").blocked)
        eq("Mozilla", dm.publisherFor("download.mozilla.org")); eq(null, dm.publisherFor("mozilla.org.evil.example"))
        eq(dev.ciel.windows.DownloadCategory.APPLICATIONS, dm.categoryOf("msi"))
    }

    T.test("Ciel's Windows tools: suggestions never delete; cleanup and Gaming Mode ask first and do nothing when declined") {
        val (_, loc) = profile()
        val installer = file(loc.downloads.resolve("setup-old.exe"), 3_000_000, daysOld = 60)
        val data = Files.createTempDirectory("ciel-data").toFile()
        val procs = mutableListOf(ProcessInfo(11, "OneDrive.exe", "C:\\OneDrive.exe", 200_000_000))
        val win = FakeWindows(procs, schemes.toMutableList())
        val services = WindowsServices(FakeShell(), win, loc, data, { it.delete() }, { Autonomy.ASSIST }, { listOf(GameProfile.MINECRAFT) })
        val skill = WindowsPcSkill(services)
        var asked = 0
        val ctx = SkillContext(null, TaskManager(CoroutineScope(Dispatchers.Default), EmergencyStop()), ConfirmationGate { asked++; false }, { true }, generation = 0)
        check(skill.invoke("scan_storage", json { "scope" to "quick" }, ctx).success)
        val sugg = skill.invoke("cleanup_suggestions", json { }, ctx)
        check("setup-old.exe" in sugg.content && "Nothing has been removed" in sugg.content) { sugg.content }
        val clean = skill.invoke("clean_up", json { "paths" to installer.toString() }, ctx)
        check("declined" in clean.content && Files.exists(installer) && asked == 1) { clean.content }
        val gm = skill.invoke("gaming_mode", json { "action" to "on" }, ctx)
        check("declined" in gm.content && procs.isNotEmpty() && asked == 2)
        check(!services.gaming.isActive)
        check(skill.invoke("gaming_mode", json { "action" to "plan" }, ctx).content.contains("OneDrive"))
    }
}

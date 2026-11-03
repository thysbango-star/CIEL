package dev.ciel.testing

import dev.ciel.windows.CleanupCandidate
import dev.ciel.windows.CleanupMethod
import dev.ciel.windows.CleanupRisk
import dev.ciel.windows.Drives
import dev.ciel.windows.DuplicateFinder
import dev.ciel.windows.GamingPreset
import dev.ciel.windows.GamingPlanner
import dev.ciel.windows.KnownLocations
import dev.ciel.windows.ProcessClass
import dev.ciel.windows.ProcessClassifier
import dev.ciel.windows.RealShell
import dev.ciel.windows.ScanOptions
import dev.ciel.windows.WindowsServices
import dev.ciel.windows.WindowsSystemImpl
import dev.ciel.windows.human
import dev.ciel.windows.isWindows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Runs Ciel's Windows features against a REAL Windows machine (CI: windows-latest).
 * Read-only checks, plus two reversible actions: deleting a file Ciel itself created in TEMP,
 * and a power-plan round trip that must end on the original plan.
 *   ./gradlew :testing:windowsSmoke
 */
fun main() = runBlocking {
    if (!isWindows) { println("Not Windows — skipping the Windows smoke test."); return@runBlocking }
    val failures = mutableListOf<String>()
    suspend fun step(name: String, body: suspend () -> String) {
        val t = System.currentTimeMillis()
        try { println("  PASS  $name — ${body()} (${System.currentTimeMillis() - t}ms)") }
        catch (e: Throwable) { failures += name; println("  FAIL  $name — ${e::class.simpleName}: ${e.message}") }
    }
    fun check(c: Boolean, msg: String) { if (!c) error(msg) }

    val shell = RealShell()
    val sys = WindowsSystemImpl(shell)
    val loc = KnownLocations.fromEnvironment()
    val data = Files.createTempDirectory("ciel-smoke").toFile()
    val pc = WindowsServices(shell, sys, loc, data, { false }, { dev.ciel.windows.Autonomy.ASSIST }, { listOf(dev.ciel.windows.GameProfile.MINECRAFT) })

    step("PowerShell runs") { val r = shell.powershell("Write-Output 'ciel'"); check(r.out.trim() == "ciel", "got '${r.out}' / ${r.err}"); "ok" }
    step("drives") { val d = Drives.list(); check(d.any { it.root.startsWith("C") && it.totalBytes > 0 }, "no C: drive in $d"); d.joinToString { "${it.root} ${human(it.freeBytes)} free" } }
    step("known folders") { "home=${loc.home} downloads=${loc.downloads} temp=${loc.tempDirs}" }

    val work = File(loc.tempDirs.first().toFile(), "ciel-smoke-${System.nanoTime()}").apply { mkdirs() }
    step("storage scan + duplicates") {
        File(work, "a.bin").writeBytes(ByteArray(2_000_000) { (it % 7).toByte() })
        File(work, "b.bin").writeBytes(ByteArray(2_000_000) { (it % 7).toByte() })
        File(work, "c.bin").writeBytes(ByteArray(2_000_000) { (it % 5).toByte() })
        val r = pc.scanner.scan(ScanOptions(listOf(work.toPath())))
        check(r.totalFiles == 3L, "files=${r.totalFiles}")
        val dups = DuplicateFinder.find(r.largestFiles).first
        check(dups.size == 1 && dups[0].paths.size == 2, "duplicates=$dups")
        "${r.totalFiles} files, ${human(r.totalBytes)}, 1 duplicate pair"
    }
    step("quick scan of the user profile (read-only)") {
        val r = withTimeoutOrNull(240_000) { pc.scan(listOf(loc.home)) } ?: error("timed out")
        "${human(r.totalBytes)} in ${r.totalFiles} files, ${r.skipped} skipped, top: ${r.categories.take(3).joinToString { it.category.label + " " + human(it.bytes) }}"
    }
    step("cleanup plan never selects protected items") {
        val p = pc.plan() ?: error("no plan")
        val windir = loc.windowsDir?.toString()?.lowercase()
        check((p.low + p.review).none { windir != null && it.path.lowercase().startsWith(windir) && !it.path.lowercase().contains("temp") }, "Windows folder in plan")
        "low ${human(p.lowBytes)}, review ${human(p.reviewBytes)}, protected ${p.protected.size}"
    }
    step("cleanup deletes only Ciel's own old temp file") {
        val f = File(work, "old.tmp").apply { writeText("x".repeat(1000)); setLastModified(System.currentTimeMillis() - 3 * 86_400_000L) }
        val rec = pc.executor.execute(listOf(CleanupCandidate(f.absolutePath, f.length(), "smoke", CleanupRisk.LOW, CleanupMethod.DELETE)))
        check(rec.single().ok && !f.exists(), "record=$rec exists=${f.exists()}")
        "deleted, logged"
    }
    step("processes + classification") {
        val ps = sys.processes()
        check(ps.size > 10, "only ${ps.size} processes")
        val classes = ps.groupBy { ProcessClassifier.classify(it, pc.processPids()) }.mapValues { it.value.size }
        check(ps.filter { it.exe == "svchost" || it.exe == "csrss" }.all { ProcessClassifier.classify(it) == ProcessClass.ESSENTIAL }, "system process not essential")
        "${ps.size} processes; " + classes.entries.joinToString { "${it.key.name}=${it.value}" }
    }
    step("power plan round trip restores the original") {
        val before = sys.powerSchemes(); check(before.isNotEmpty(), "no power schemes")
        val original = before.first { it.active }
        val plan = GamingPlanner.plan(sys.processes(), GamingPreset.MAXIMUM, null, before, null, pc.processPids(), sys.gameModeEnabled(), sys.gpuSchedulingEnabled())
        val on = pc.gaming.activate(plan, approved = emptyList())  // closes nothing
        val during = sys.powerSchemes().first { it.active }
        val off = pc.gaming.deactivate()
        val after = sys.powerSchemes().first { it.active }
        check(after.guid.equals(original.guid, true), "ended on ${after.name}, expected ${original.name}")
        check(!pc.gaming.isActive, "state not cleared")
        "${original.name} → ${during.name} → ${after.name}; ${on.lines.size + off.lines.size} report lines"
    }
    step("game mode / GPU scheduling (read-only)") { "Game Mode=${sys.gameModeEnabled()} HAGS=${sys.gpuSchedulingEnabled()} foreground=${sys.foregroundPid()}" }
    step("performance sample") {
        val b = pc.monitor.basic()
        val live = withTimeoutOrNull(30_000) { pc.monitor.samples(1).first { it.cpuPercent != null } }
        "basic cpu=${b.cpuPercent} ram=${b.ramUsedBytes?.let(::human)}; typeperf cpu=${live?.cpuPercent} disk=${live?.diskPercent} gpu=${live?.gpuPercent}"
    }
    step("PC health") { val h = pc.health.check(); check(h.isNotEmpty(), "empty"); h.joinToString(" | ") { "${it.area}:${it.status}" } }
    step("download: inspect + verify a small file (never run)") {
        val p = pc.downloads.inspect("https://www.python.org/robots.txt")
        check(!p.blocked && p.knownPublisher != null, "proposal=$p")
        val r = pc.downloads.download(p)
        check(r.file.exists() && r.sha256.length == 64, "result=$r")
        r.file.delete()
        val blocked = pc.downloads.inspect("http://example.com/setup.exe")
        check(blocked.blocked, "plain http not blocked")
        "${p.fileName} ${human(r.bytes)} sha=${r.sha256.take(12)} defender=${r.defenderClean}; http blocked"
    }
    work.deleteRecursively(); data.deleteRecursively()
    println(if (failures.isEmpty()) "\nWindows smoke test passed." else "\nWindows smoke test FAILED: $failures")
    if (failures.isNotEmpty()) exitProcess(1)
}

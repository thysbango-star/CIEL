package dev.ciel.windows

import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

enum class CleanupRisk(val label: String) {
    LOW("Low risk"), REVIEW("Review recommended"), PROTECTED("Protected — Ciel won't remove"),
}

/** How an approved item is removed. */
enum class CleanupMethod(val reversible: Boolean, val label: String) {
    RECYCLE(true, "Move to the Recycle Bin (can be restored from there)"),
    DELETE(false, "Delete permanently (disposable temporary/cache data)"),
    EMPTY_RECYCLE_BIN(false, "Empty the Recycle Bin (cannot be undone)"),
}

data class CleanupCandidate(
    val path: String,
    val size: Long,
    val reason: String,
    val risk: CleanupRisk,
    val method: CleanupMethod,
    /** True when Windows or an app appears to have it open right now. */
    val inUse: Boolean = false,
    val isFolder: Boolean = false,
)

/**
 * What Ciel never removes in a cleanup: Windows and driver files, installed programs, program
 * data, app settings, browser profiles (only their cache folders), saved games, password
 * databases, mail stores and keys.
 */
class ProtectionPolicy(private val loc: KnownLocations) {
    private val protectedExt = setOf("sys", "dll", "kdbx", "kdb", "1pif", "opvault", "pst", "ost", "pfx", "p12", "pem", "key", "ppk", "wallet", "dat")
    private val protectedNames = setOf("pagefile.sys", "hiberfil.sys", "swapfile.sys", "ntuser.dat", "desktop.ini", "thumbs.db")
    private val savedGames = listOf("saved games", "my games", "\\.minecraft\\saves", "/.minecraft/saves", "\\saves\\", "/saves/", "savegames", "savedata")

    fun protectionReason(p: Path): String? {
        val s = p.toString().lowercase()
        val name = p.fileName?.toString()?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "")
        fun under(b: Path?) = b != null && p.startsWith(b)
        return when {
            under(loc.windowsDir) && loc.tempDirs.none { p.startsWith(it) } -> "Windows system files"
            loc.programDirs.any { under(it) } -> "Installed program files"
            under(loc.programData) && !Categorizer(loc).isCache(p) -> "Program data used by installed software"
            s.contains("system volume information") || s.contains("\$recycle.bin") || s.contains("\\windows.old\\") -> "System-managed folder"
            name in protectedNames -> "Windows-managed file"
            ext in protectedExt -> "Drivers, keys, password or mail databases"
            savedGames.any { s.contains(it) } -> "Saved game data"
            (s.contains("\\user data\\") || s.contains("/user data/") || s.contains("\\profiles\\") || s.contains("/profiles/")) && !Categorizer(loc).isCache(p) -> "Browser profile (passwords, history, settings)"
            under(loc.roamingAppData) && !Categorizer(loc).isCache(p) -> "App settings"
            else -> null
        }
    }

    fun isProtected(p: Path) = protectionReason(p) != null
}

/**
 * Finds cleanup opportunities in a scan. Nothing is called unnecessary just for being big or old:
 * only temporary and cache data is "low risk"; everything personal is "review"; protected items
 * are listed so you can see what Ciel leaves alone.
 */
class CleanupPlanner(private val loc: KnownLocations, private val now: () -> Long = System::currentTimeMillis) {
    private val cat = Categorizer(loc)
    private val policy = ProtectionPolicy(loc)
    private val day = 86_400_000L

    data class Plan(val low: List<CleanupCandidate>, val review: List<CleanupCandidate>, val protected: List<CleanupCandidate>) {
        val lowBytes: Long get() = low.sumOf { it.size }
        val reviewBytes: Long get() = review.sumOf { it.size }
    }

    fun plan(scan: ScanResult, recycleBinBytes: Long? = null, duplicates: List<DuplicateFinder.Group> = emptyList()): Plan {
        val low = mutableListOf<CleanupCandidate>()
        val review = mutableListOf<CleanupCandidate>()
        val prot = mutableListOf<CleanupCandidate>()
        val all = (scan.categories.flatMap { it.top } + scan.largestFiles).distinctBy { it.path }

        // Temporary and cache folders: low risk as folder totals (files older than a day).
        for (c in listOf(StorageCategory.TEMP, StorageCategory.CACHE)) {
            val total = scan.categories.firstOrNull { it.category == c } ?: continue
            if (total.bytes <= 0) continue
            val folders = scan.largestFolders.filter { f -> cat.categorize(Paths.get(f.path, "x")) == c && !policy.isProtected(Paths.get(f.path)) }
                .filter { f -> scan.largestFolders.none { o -> o !== f && Paths.get(f.path).startsWith(Paths.get(o.path)) && cat.categorize(Paths.get(o.path, "x")) == c } }
                .take(15)
            folders.forEach { f ->
                low += CleanupCandidate(f.path, f.size, if (c == StorageCategory.TEMP) "Temporary files apps leave behind; files in use or newer than a day are skipped."
                    else "Cache data apps rebuild automatically (it may make the first start a little slower).", CleanupRisk.LOW, CleanupMethod.DELETE, isFolder = true)
            }
        }
        if (recycleBinBytes != null && recycleBinBytes > 0) {
            low += CleanupCandidate("Recycle Bin", recycleBinBytes, "Files you already deleted.", CleanupRisk.LOW, CleanupMethod.EMPTY_RECYCLE_BIN)
        }
        for (f in all) {
            val p = Paths.get(f.path)
            policy.protectionReason(p)?.let { r -> if (f.size >= 200_000_000) prot += CleanupCandidate(f.path, f.size, r, CleanupRisk.PROTECTED, CleanupMethod.RECYCLE); return@let }
            if (policy.isProtected(p) || f.category == StorageCategory.TEMP || f.category == StorageCategory.CACHE) continue
            val ext = p.fileName.toString().substringAfterLast('.', "").lowercase()
            val ageDays = (now() - f.modified) / day
            val inDownloadsOrDesktop = p.startsWith(loc.downloads) || p.startsWith(loc.desktop)
            val reason = when {
                inDownloadsOrDesktop && ext in cat.installerExt && ageDays > 30 -> "Installer downloaded $ageDays days ago — usually not needed once the program is installed."
                p.startsWith(loc.downloads) && ageDays > 180 && f.size >= 50_000_000 -> "In Downloads, unchanged for $ageDays days."
                ext in cat.archiveExt && f.size >= 100_000_000 -> "Large archive (${human(f.size)}) — check whether it has been extracted."
                f.category == StorageCategory.VIDEOS && f.size >= 500_000_000 && ageDays > 90 && f.path.lowercase().let { "captures" in it || "recording" in it || "obs" in it } -> "Old screen recording ($ageDays days)."
                f.size >= 1_000_000_000 && f.category != StorageCategory.GAMES && f.category != StorageCategory.APPS -> "Very large file (${human(f.size)})."
                else -> null
            } ?: continue
            review += CleanupCandidate(f.path, f.size, reason, CleanupRisk.REVIEW, CleanupMethod.RECYCLE)
        }
        for (g in duplicates) {
            // Keep the first copy; offer the others.
            g.paths.drop(1).filter { !policy.isProtected(Paths.get(it)) }.forEach { d ->
                review += CleanupCandidate(d, g.size, "Identical copy of ${g.paths.first()} (same content, SHA-256).", CleanupRisk.REVIEW, CleanupMethod.RECYCLE)
            }
        }
        return Plan(low.distinctBy { it.path }.sortedByDescending { it.size }, review.distinctBy { it.path }.sortedByDescending { it.size },
            prot.distinctBy { it.path }.sortedByDescending { it.size }.take(25))
    }

    /** "May be in use": Windows refuses to open a file for writing while another program locks it. */
    fun inUse(p: Path): Boolean = runCatching { FileChannel.open(p, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch -> ch.tryLock()?.release() }; false }.getOrDefault(true)
}

/** Record of what a cleanup did (shown in the activity log; used for honest reporting). */
data class CleanupRecord(val at: Long, val path: String, val bytes: Long, val method: CleanupMethod, val ok: Boolean, val note: String)

/**
 * Executes approved cleanup items only. Each item is re-checked first (still there, not
 * protected); personal files go to the Recycle Bin; temp/cache folders are emptied of files that
 * are not in use and older than a day.
 */
class CleanupExecutor(
    private val loc: KnownLocations,
    private val shell: Shell,
    /** Moves a file to the Recycle Bin (java.awt.Desktop.moveToTrash on Windows). */
    private val moveToTrash: (File) -> Boolean,
    private val logFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val policy = ProtectionPolicy(loc)

    suspend fun execute(approved: List<CleanupCandidate>, onProgress: (String) -> Unit = {}): List<CleanupRecord> = withContext(Dispatchers.IO) {
        val out = mutableListOf<CleanupRecord>()
        for (c in approved) {
            onProgress(c.path)
            val rec = when (c.method) {
                CleanupMethod.EMPTY_RECYCLE_BIN -> {
                    val r = shell.powershell("Clear-RecycleBin -Force -ErrorAction SilentlyContinue")
                    CleanupRecord(now(), c.path, if (r.ok) c.size else 0, c.method, r.ok, if (r.ok) "Emptied" else r.err.take(200))
                }
                CleanupMethod.RECYCLE -> {
                    val p = Paths.get(c.path)
                    when {
                        c.risk == CleanupRisk.PROTECTED -> CleanupRecord(now(), c.path, 0, c.method, false, "Protected — not removed")
                        policy.protectionReason(p) != null -> CleanupRecord(now(), c.path, 0, c.method, false, "Protected (${policy.protectionReason(p)}) — not removed")
                        !Files.exists(p) -> CleanupRecord(now(), c.path, 0, c.method, false, "No longer there")
                        else -> {
                            val size = runCatching { Files.size(p) }.getOrDefault(0)
                            val ok = runCatching { moveToTrash(p.toFile()) }.getOrDefault(false)
                            CleanupRecord(now(), c.path, if (ok) size else 0, c.method, ok, if (ok) "Moved to the Recycle Bin" else "Windows refused (in use or no permission)")
                        }
                    }
                }
                CleanupMethod.DELETE -> emptyDisposableFolder(Paths.get(c.path))
            }
            out += rec
            append(rec)
        }
        out
    }

    /** Deletes files older than a day that aren't in use, inside a temp/cache folder only. */
    private fun emptyDisposableFolder(given: Path): CleanupRecord {
        val dir = runCatching { given.toRealPath() }.getOrDefault(given)
        val cat = Categorizer(loc)
        fun real(x: Path) = runCatching { x.toRealPath() }.getOrDefault(x)
        val disposable = loc.tempDirs.any { dir.startsWith(it) || dir.startsWith(real(it)) || given.startsWith(it) } || cat.isCache(dir.resolve("x"))
        if (!disposable || policy.protectionReason(dir.resolve("x")) != null) return CleanupRecord(now(), dir.toString(), 0, CleanupMethod.DELETE, false, "Not a temporary/cache folder — refused")
        if (!Files.isDirectory(dir)) return CleanupRecord(now(), dir.toString(), 0, CleanupMethod.DELETE, false, "No longer there")
        var freed = 0L; var skipped = 0
        val cutoff = now() - 86_400_000L
        runCatching {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { f ->
                    runCatching {
                        val size = Files.size(f)
                        if (Files.getLastModifiedTime(f).toMillis() < cutoff) { Files.delete(f); freed += size } else skipped++
                    }.onFailure { skipped++ } // in use → Windows refuses; that's fine
                }
            }
        }
        return CleanupRecord(now(), dir.toString(), freed, CleanupMethod.DELETE, true, "Freed ${human(freed)}" + if (skipped > 0) "; kept $skipped files that were in use or recent" else "")
    }

    private fun append(r: CleanupRecord) = runCatching {
        logFile.parentFile?.mkdirs()
        logFile.appendText(json { "at" to r.at; "path" to r.path; "bytes" to r.bytes; "method" to r.method.name; "ok" to r.ok; "note" to r.note }.toString() + "\n")
    }

    fun history(): List<CleanupRecord> = runCatching {
        logFile.readLines().mapNotNull { l -> Json.parseOrNull(l)?.let { o ->
            CleanupRecord(o["at"]?.long ?: 0, o["path"]?.string ?: "", o["bytes"]?.long ?: 0, runCatching { CleanupMethod.valueOf(o["method"]?.string ?: "") }.getOrDefault(CleanupMethod.RECYCLE), o["ok"]?.bool ?: false, o["note"]?.string ?: "")
        } }
    }.getOrDefault(emptyList())

    companion object {
        /** Size of the Recycle Bin for all drives (Windows Shell), or null if unknown. */
        suspend fun recycleBinBytes(shell: Shell): Long? {
            val r = shell.powershell("(New-Object -ComObject Shell.Application).NameSpace(10).Items() | Measure-Object -Property Size -Sum | Select-Object -ExpandProperty Sum")
            return r.out.trim().toDoubleOrNull()?.toLong()
        }
    }
}

package dev.ciel.windows

import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext

data class DriveInfo(val root: String, val label: String?, val fileSystem: String?, val type: String?, val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val usedPercent: Int get() = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0
}

object Drives {
    /** Every accessible drive with capacity (local file metadata only). */
    fun list(): List<DriveInfo> = File.listRoots().mapNotNull { root ->
        runCatching {
            val total = root.totalSpace
            if (total <= 0) return@runCatching null
            val store = runCatching { Files.getFileStore(root.toPath()) }.getOrNull()
            DriveInfo(root.path, store?.name()?.takeIf { it.isNotBlank() }, store?.type(), null, total, root.usableSpace)
        }.getOrNull()
    }
}

enum class StorageCategory(val label: String) {
    APPS("Installed applications"), GAMES("Games"), DOWNLOADS("Downloads"), DOCUMENTS("Documents"), VIDEOS("Videos"),
    IMAGES("Images"), MUSIC("Music"), DESKTOP("Desktop"), TEMP("Temporary files"), CACHE("Cache files"),
    ARCHIVES("Archives"), SYSTEM("Windows & system"), OTHER("Other files"),
}

/** Known folders, from the environment on Windows (or given explicitly in tests). */
data class KnownLocations(
    val home: Path,
    val programDirs: List<Path>,
    val windowsDir: Path?,
    val tempDirs: List<Path>,
    val localAppData: Path?,
    val roamingAppData: Path?,
    val programData: Path?,
) {
    val downloads: Path get() = home.resolve("Downloads")
    val documents: Path get() = home.resolve("Documents")
    val videos: Path get() = home.resolve("Videos")
    val pictures: Path get() = home.resolve("Pictures")
    val music: Path get() = home.resolve("Music")
    val desktop: Path get() = home.resolve("Desktop")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): KnownLocations {
            // Real (long) names: Windows often reports TEMP as C:\Users\RUNNER~1\…, while files are found under the long name.
            fun p(k: String) = env[k]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.let { runCatching { it.toRealPath() }.getOrDefault(it) }
            val home = p("USERPROFILE") ?: Paths.get(System.getProperty("user.home"))
            return KnownLocations(
                home = home,
                programDirs = listOfNotNull(p("ProgramFiles"), p("ProgramFiles(x86)"), p("LOCALAPPDATA")?.resolve("Programs")).distinct(),
                windowsDir = p("SystemRoot") ?: p("windir"),
                tempDirs = listOfNotNull(p("TEMP"), p("TMP"), (p("SystemRoot") ?: p("windir"))?.resolve("Temp")).distinct(),
                localAppData = p("LOCALAPPDATA"), roamingAppData = p("APPDATA"), programData = p("ProgramData"),
            )
        }
    }
}

/** Assigns a file to a category from where it is and what it is. Path rules win over extensions. */
class Categorizer(private val loc: KnownLocations) {
    private val gameMarkers = listOf("steamapps", "epic games", "riot games", ".minecraft", "xboxgames", "gog galaxy", "ea games", "ubisoft game launcher", "battle.net", "games")
    private val cacheMarkers = listOf("\\cache\\", "/cache/", "\\cache2\\", "/cache2/", "code cache", "gpucache", "\\npm-cache\\", "/npm-cache/", "\\pip\\cache", "shadercache", "dxcache", "\\.gradle\\caches", "/.gradle/caches", "crashpad")
    val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "cab")
    val installerExt = setOf("exe", "msi", "msix", "msixbundle", "appx")
    private val videoExt = setOf("mp4", "mkv", "mov", "avi", "wmv", "webm", "flv", "m4v")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "bmp", "heic", "webp", "raw", "cr2", "nef", "tif", "tiff", "psd")
    private val musicExt = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma")

    private fun under(p: Path, base: Path?) = base != null && p.startsWith(base)
    fun isCache(p: Path) = cacheMarkers.any { p.toString().lowercase().contains(it) }
    fun isGame(p: Path) = p.toString().lowercase().let { s -> gameMarkers.any { m -> s.contains("\\$m\\") || s.contains("/$m/") } }

    fun categorize(p: Path): StorageCategory {
        val ext = p.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            loc.tempDirs.any { under(p, it) } -> StorageCategory.TEMP
            isCache(p) -> StorageCategory.CACHE
            under(p, loc.windowsDir) -> StorageCategory.SYSTEM
            isGame(p) -> StorageCategory.GAMES
            loc.programDirs.any { under(p, it) } -> StorageCategory.APPS
            under(p, loc.downloads) -> StorageCategory.DOWNLOADS
            under(p, loc.desktop) -> StorageCategory.DESKTOP
            under(p, loc.documents) -> StorageCategory.DOCUMENTS
            under(p, loc.videos) || ext in videoExt -> StorageCategory.VIDEOS
            under(p, loc.pictures) || ext in imageExt -> StorageCategory.IMAGES
            under(p, loc.music) || ext in musicExt -> StorageCategory.MUSIC
            ext in archiveExt -> StorageCategory.ARCHIVES
            under(p, loc.programData) -> StorageCategory.APPS
            else -> StorageCategory.OTHER
        }
    }
}

data class FileEntry(val path: String, val size: Long, val modified: Long, val category: StorageCategory)
data class FolderEntry(val path: String, val size: Long, val files: Int)
data class CategoryTotal(val category: StorageCategory, val bytes: Long, val files: Int, val top: List<FileEntry>)

data class ScanOptions(
    val roots: List<Path>,
    val exclusions: List<Path> = emptyList(),
    val topFiles: Int = 300,
    val topFolders: Int = 150,
    /** Folders deeper than this below a root are added to their ancestor at this depth. */
    val folderDepth: Int = 4,
)

data class ScanResult(
    val roots: List<String>,
    val startedAt: Long,
    val durationMs: Long,
    val totalBytes: Long,
    val totalFiles: Long,
    val categories: List<CategoryTotal>,
    val largestFiles: List<FileEntry>,
    val largestFolders: List<FolderEntry>,
    /** Files Ciel could not read (permissions, in use) — skipped, never an error. */
    val skipped: Int,
    val cancelled: Boolean,
) {
    fun summary(): String = buildString {
        append("Scanned ${roots.joinToString()} — ${human(totalBytes)} in $totalFiles files")
        if (cancelled) append(" (cancelled part-way)")
        append(".\n")
        categories.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.forEach { append("- ${it.category.label}: ${human(it.bytes)} (${it.files} files)\n") }
        append("Largest files: ").append(largestFiles.take(5).joinToString { "${it.path.substringAfterLast('\\').substringAfterLast('/')} ${human(it.size)}" })
        if (skipped > 0) append("\nSkipped $skipped items Ciel isn't allowed to read.")
    }
}

fun human(bytes: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, u[i])
}

/**
 * Walks the chosen folders with file metadata only (no file contents are read or uploaded).
 * Skips links and junctions, inaccessible folders and excluded paths; can be cancelled.
 */
class StorageScanner(private val loc: KnownLocations) {
    val categorizer = Categorizer(loc)
    /** Legacy junctions in a Windows profile that point back into it (would double-count). */
    private val junctionNames = setOf("application data", "cookies", "local settings", "my documents", "nethood", "printhood", "recent", "sendto",
        "start menu", "templates", "documents and settings", "my music", "my pictures", "my videos", "\$recycle.bin", "system volume information")

    suspend fun scan(options: ScanOptions, onProgress: (files: Long, bytes: Long, current: String) -> Unit = { _, _, _ -> }): ScanResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val ctx = coroutineContext
        val totals = HashMap<StorageCategory, LongArray>() // bytes, files
        val topPerCat = HashMap<StorageCategory, PriorityQueue<FileEntry>>()
        val largest = PriorityQueue<FileEntry>(compareBy { it.size })
        val folderSizes = HashMap<Path, LongArray>()
        var files = 0L; var bytes = 0L; var skipped = 0; var cancelled = false
        var lastReport = 0L

        for (root in options.roots) {
            if (!Files.isDirectory(root)) continue
            try {
                Files.walkFileTree(root, emptySet<FileVisitOption>(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!isActive(ctx)) { cancelled = true; return FileVisitResult.TERMINATE }
                        if (dir != root && (attrs.isSymbolicLink || attrs.isOther || dir.fileName?.toString()?.lowercase() in junctionNames)) return FileVisitResult.SKIP_SUBTREE
                        if (options.exclusions.any { dir.startsWith(it) }) return FileVisitResult.SKIP_SUBTREE
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val size = attrs.size()
                        val cat = categorizer.categorize(file)
                        val e = FileEntry(file.toString(), size, attrs.lastModifiedTime().toMillis(), cat)
                        files++; bytes += size
                        totals.getOrPut(cat) { LongArray(2) }.let { it[0] += size; it[1]++ }
                        topPerCat.getOrPut(cat) { PriorityQueue(compareBy { it.size }) }.offerBounded(e, 50)
                        largest.offerBounded(e, options.topFiles)
                        // Count the size in every folder from the root down to the configured depth.
                        val rel = root.relativize(file.parent ?: root)
                        if (rel.toString().isNotEmpty()) {
                            var folder = root
                            for (i in 0 until minOf(rel.nameCount, options.folderDepth)) {
                                folder = folder.resolve(rel.getName(i))
                                folderSizes.getOrPut(folder) { LongArray(2) }.let { it[0] += size; it[1]++ }
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) { lastReport = now; onProgress(files, bytes, file.parent?.toString().orEmpty()) }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult { skipped++; return FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) skipped++; return FileVisitResult.CONTINUE }
                })
            } catch (e: IOException) { skipped++ }
            if (cancelled) break
        }
        ScanResult(
            roots = options.roots.map { it.toString() }, startedAt = started, durationMs = System.currentTimeMillis() - started,
            totalBytes = bytes, totalFiles = files,
            categories = StorageCategory.values().map { c -> CategoryTotal(c, totals[c]?.get(0) ?: 0, (totals[c]?.get(1) ?: 0).toInt(), topPerCat[c]?.sortedByDescending { it.size } ?: emptyList()) },
            largestFiles = largest.sortedByDescending { it.size },
            largestFolders = folderSizes.entries.map { FolderEntry(it.key.toString(), it.value[0], it.value[1].toInt()) }.sortedByDescending { it.size }.take(options.topFolders),
            skipped = skipped, cancelled = cancelled,
        )
    }

    private fun isActive(ctx: kotlin.coroutines.CoroutineContext) = runCatching { ctx.ensureActive(); true }.getOrDefault(false)
}

private fun <T> PriorityQueue<T>.offerBounded(e: T, max: Int) {
    if (size < max) { offer(e); return }
    val smallest = peek() ?: return
    if (comparator().compare(e, smallest) > 0) { poll(); offer(e) }
}

/** Duplicate files, confirmed by content (size → quick hash → full SHA-256). */
object DuplicateFinder {
    data class Group(val size: Long, val sha256: String, val paths: List<String>) {
        val wasted: Long get() = size * (paths.size - 1)
    }

    suspend fun find(candidates: List<FileEntry>, minSize: Long = 1_000_000, maxBytesHashed: Long = 30L shl 30): Pair<List<Group>, Boolean> = withContext(Dispatchers.IO) {
        var hashed = 0L; var partial = false
        val groups = mutableListOf<Group>()
        val bySize = candidates.filter { it.size >= minSize }.groupBy { it.size }.filterValues { it.size > 1 }
        for ((size, same) in bySize.entries.sortedByDescending { it.key }) {
            coroutineContext.ensureActive()
            if (hashed + size * same.size > maxBytesHashed) { partial = true; continue }
            val quick = same.groupBy { runCatching { quickHash(Paths.get(it.path), size) }.getOrNull() }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (q in quick) {
                val full = q.groupBy { e -> runCatching { sha256(Paths.get(e.path)) }.getOrNull().also { hashed += size } }.filterKeys { it != null }
                full.forEach { (h, list) -> if (list.size > 1) groups += Group(size, h!!, list.map { it.path }) }
            }
        }
        groups.sortedByDescending { it.wasted } to partial
    }

    private fun quickHash(p: Path, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(p).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(65536)
            ch.read(buf); md.update(buf.array(), 0, buf.position())
            if (size > 131072) { buf.clear(); ch.position(size - 65536); ch.read(buf); md.update(buf.array(), 0, buf.position()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Kept scan summaries, to compare storage over time. */
class ScanHistory(private val file: File) {
    data class Entry(val at: Long, val roots: String, val totalBytes: Long, val byCategory: Map<String, Long>, val freeByDrive: Map<String, Long>)

    fun add(r: ScanResult, drives: List<DriveInfo>) {
        val list = (all() + Entry(r.startedAt, r.roots.joinToString(), r.totalBytes, r.categories.associate { it.category.name to it.bytes },
            drives.associate { it.root to it.freeBytes })).takeLast(50)
        file.parentFile?.mkdirs()
        file.writeText(json { "entries" to list.map { e -> json { "at" to e.at; "roots" to e.roots; "total" to e.totalBytes; "cats" to e.byCategory; "free" to e.freeByDrive } } }.toString())
    }

    fun all(): List<Entry> = runCatching {
        Json.parse(file.readText())["entries"]?.array.orEmpty().map { e ->
            Entry(e["at"]?.long ?: 0, e["roots"]?.string ?: "", e["total"]?.long ?: 0,
                e["cats"]?.obj?.fields?.mapValues { it.value.long ?: 0 } ?: emptyMap(), e["free"]?.obj?.fields?.mapValues { it.value.long ?: 0 } ?: emptyMap())
        }
    }.getOrDefault(emptyList())
}

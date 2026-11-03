package dev.ciel.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class DownloadCategory(val folder: String) { APPLICATIONS("Applications"), GAMES("Games"), DOCUMENTS("Documents"), IMAGES("Images"), VIDEOS("Videos"), ARCHIVES("Archives"), OTHER("Other") }

/** What Ciel shows you before downloading anything. */
data class DownloadProposal(
    val url: String,
    val finalUrl: String,
    val host: String,
    val fileName: String,
    val sizeBytes: Long?,
    val contentType: String?,
    val category: DownloadCategory,
    val executable: Boolean,
    val knownPublisher: String?,
    val warnings: List<String>,
) {
    val blocked: Boolean get() = warnings.any { it.startsWith("BLOCKED") }
}

data class DownloadResult(
    val file: File,
    val bytes: Long,
    val sha256: String,
    val hashMatches: Boolean?,
    /** "Valid" / "NotSigned" / "HashMismatch" … from Windows, with the signer. */
    val signature: String?,
    val signer: String?,
    /** Microsoft Defender scan: true = clean, false = threat found, null = not available. */
    val defenderClean: Boolean?,
    val notes: List<String>,
)

/**
 * Permission-based downloads. Ciel inspects the link (HTTPS, real file name, size, type, host),
 * shows a proposal, and downloads only after approval. Afterwards: SHA-256 (compared with a hash
 * you give), Windows signature check for programs, Defender scan, and the "downloaded from the
 * internet" mark so SmartScreen still protects you. Downloaded files are never opened or run.
 */
class DownloadManager(private val shell: Shell, private val baseFolder: File) {
    private val publishers = mapOf(
        "microsoft.com" to "Microsoft", "mozilla.org" to "Mozilla", "google.com" to "Google", "dl.google.com" to "Google", "python.org" to "Python Software Foundation",
        "nodejs.org" to "Node.js", "videolan.org" to "VideoLAN", "7-zip.org" to "7-Zip", "minecraft.net" to "Mojang / Microsoft", "mojang.com" to "Mojang",
        "steampowered.com" to "Valve", "steamstatic.com" to "Valve", "epicgames.com" to "Epic Games", "nvidia.com" to "NVIDIA", "amd.com" to "AMD", "intel.com" to "Intel",
        "adoptium.net" to "Eclipse Adoptium", "github.com" to "GitHub (check the project owner)", "githubusercontent.com" to "GitHub (check the project owner)",
        "discord.com" to "Discord", "spotify.com" to "Spotify", "obsproject.com" to "OBS Project", "blender.org" to "Blender Foundation", "libreoffice.org" to "The Document Foundation",
        "ollama.com" to "Ollama", "brave.com" to "Brave", "riotgames.com" to "Riot Games", "ea.com" to "Electronic Arts", "ubisoft.com" to "Ubisoft", "battle.net" to "Blizzard",
    )
    private val execExt = setOf("exe", "msi", "msix", "msixbundle", "appx", "bat", "cmd", "ps1", "vbs", "js", "jar", "scr", "com", "reg", "lnk", "hta")

    fun publisherFor(host: String): String? = publishers.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value

    suspend fun inspect(url: String): DownloadProposal = withContext(Dispatchers.IO) {
        val u = runCatching { URI(url.trim()) }.getOrNull()
        val warnings = mutableListOf<String>()
        if (u == null || u.host.isNullOrBlank()) return@withContext DownloadProposal(url, url, "", "?", null, null, DownloadCategory.OTHER, false, null, listOf("BLOCKED: that isn't a valid web address."))
        if (u.scheme?.lowercase() != "https") warnings += "BLOCKED: not an encrypted (https) link — Ciel only downloads over HTTPS."
        var finalUrl = url; var size: Long? = null; var type: String? = null; var disposition: String? = null
        if (warnings.isEmpty()) runCatching {
            var current = URL(url); var hops = 0
            while (true) {
                val c = (current.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"; instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("User-Agent", "CielAssistant/0.1 (Windows)")
                }
                val code = c.responseCode
                if (code in 300..399 && hops < 8) {
                    val loc = c.getHeaderField("Location") ?: break
                    current = URL(current, loc); hops++
                    if (current.protocol != "https") { warnings += "BLOCKED: the link redirects to an unencrypted (http) address."; break }
                    continue
                }
                if (code >= 400) warnings += "The server answered HTTP $code — the file may not exist or needs a login."
                size = c.contentLengthLong.takeIf { it > 0 }; type = c.contentType; disposition = c.getHeaderField("Content-Disposition")
                break
            }
            finalUrl = current.toString()
        }.onFailure { warnings += "Couldn't reach the server: ${it.message}" }
        val host = runCatching { URI(finalUrl).host.lowercase() }.getOrDefault(u.host.lowercase())
        val name = sanitize(disposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
            ?: finalUrl.substringBefore('?').substringAfterLast('/').ifBlank { "download" })
        val ext = name.substringAfterLast('.', "").lowercase()
        val exec = ext in execExt
        val publisher = publisherFor(host)
        if (Regex("\\.(pdf|docx?|jpe?g|png|mp4|txt|zip)\\.(exe|scr|bat|cmd|js|vbs)$", RegexOption.IGNORE_CASE).containsMatchIn(name)) warnings += "BLOCKED: the name hides a program behind a document extension ($name)."
        if (exec && publisher == null) warnings += "This is a program from $host, which isn't a publisher Ciel recognises. Only continue if you trust this site."
        if (type != null && exec && type!!.startsWith("text/html")) warnings += "The link returns a web page, not a file — it's probably not the real download link."
        if (ext in setOf("bat", "cmd", "ps1", "vbs", "js", "scr", "hta", "reg")) warnings += "Script files can change your PC when opened. Ciel won't open it."
        DownloadProposal(url, finalUrl, host, name, size, type, categoryOf(ext), exec, publisher, warnings)
    }

    suspend fun download(p: DownloadProposal, expectedSha256: String? = null, onProgress: (Long, Long?) -> Unit = { _, _ -> }): DownloadResult = withContext(Dispatchers.IO) {
        require(!p.blocked) { "This download was blocked: ${p.warnings.first { it.startsWith("BLOCKED") }}" }
        val dir = File(baseFolder, p.category.folder).apply { mkdirs() }
        var target = File(dir, p.fileName)
        var n = 1
        while (target.exists()) target = File(dir, p.fileName.substringBeforeLast('.') + " ($n)" + p.fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }).also { n++ }
        val part = File(target.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val c = (URL(p.finalUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("User-Agent", "CielAssistant/0.1 (Windows)") }
        try {
            if (c.responseCode >= 400) throw IllegalStateException("Server answered HTTP ${c.responseCode}.")
            if (c.url.protocol != "https") throw IllegalStateException("Redirected to an unencrypted address; stopped.")
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val r = input.read(buf); if (r < 0) break
                    out.write(buf, 0, r); md.update(buf, 0, r); total += r
                    onProgress(total, p.sizeBytes)
                }
            } }
        } catch (e: Exception) { part.delete(); throw e } finally { c.disconnect() }
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = false); part.delete() }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val notes = mutableListOf<String>()
        // Mark of the Web: Windows treats the file as downloaded (SmartScreen, Office protected view).
        if (isWindows) runCatching { File(target.path + ":Zone.Identifier").writeText("[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=${p.finalUrl}\r\n") }
            .onFailure { notes += "Couldn't add the \"downloaded from the internet\" mark." }
        var sig: String? = null; var signer: String? = null; var clean: Boolean? = null
        if (isWindows) {
            if (p.executable) shell.psJson("Get-AuthenticodeSignature -LiteralPath ${psQuote(target.path)} | Select-Object @{n='S';e={\$_.Status.ToString()}},@{n='Signer';e={\$_.SignerCertificate.Subject}}")?.let { o ->
                sig = o["S"]?.string; signer = o["Signer"]?.string?.let { s -> Regex("CN=\"?([^,\"]+)").find(s)?.groupValues?.get(1) ?: s }
            }
            clean = defenderScan(target)
        }
        val hashOk = expectedSha256?.let { it.trim().equals(sha, ignoreCase = true) }
        if (hashOk == false) notes += "The file's SHA-256 doesn't match the hash you gave — don't open it."
        if (p.executable && sig != null && sig != "Valid") notes += "Windows says the program's signature is \"$sig\" — be careful before installing it."
        if (clean == false) notes += "Microsoft Defender found a threat in this file. Don't open it; Defender will handle it."
        DownloadResult(target, total, sha, hashOk, sig, signer, clean, notes)
    }

    /** Scans one file with Microsoft Defender's command-line scanner (exit 0 = clean, 2 = threat). */
    private suspend fun defenderScan(f: File): Boolean? {
        val exe = listOf(System.getenv("ProgramFiles") + "\\Windows Defender\\MpCmdRun.exe").firstOrNull { File(it).exists() } ?: return null
        val r = shell.run(listOf(exe, "-Scan", "-ScanType", "3", "-File", f.absolutePath, "-DisableRemediation"), 180_000)
        return when (r.exit) { 0 -> true; 2 -> false; else -> null }
    }

    private fun sanitize(n: String) = java.net.URLDecoder.decode(n, Charsets.UTF_8).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(150).ifBlank { "download" }

    fun categoryOf(ext: String) = when (ext) {
        "exe", "msi", "msix", "msixbundle", "appx" -> DownloadCategory.APPLICATIONS
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "epub" -> DownloadCategory.DOCUMENTS
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> DownloadCategory.IMAGES
        "mp4", "mkv", "mov", "avi", "webm" -> DownloadCategory.VIDEOS
        "zip", "rar", "7z", "tar", "gz", "iso" -> DownloadCategory.ARCHIVES
        "jar", "mcpack", "mcworld", "mrpack" -> DownloadCategory.GAMES
        else -> DownloadCategory.OTHER
    }
}

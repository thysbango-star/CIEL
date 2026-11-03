package dev.ciel.windows

import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

data class ShellResult(val exit: Int, val out: String, val err: String) {
    val ok: Boolean get() = exit == 0
}

/** Runs programs. Everything Ciel does on Windows goes through here, so tests can fake it. */
interface Shell {
    suspend fun run(command: List<String>, timeoutMs: Long = 30_000): ShellResult

    /** Runs a PowerShell script (no profile, no prompts) and returns its output. */
    suspend fun powershell(script: String, timeoutMs: Long = 30_000): ShellResult =
        run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $script"), timeoutMs)

    /** PowerShell → JSON (`| ConvertTo-Json -Compress -Depth 4` is appended). Null on failure. */
    suspend fun psJson(script: String, timeoutMs: Long = 30_000): JsonValue? {
        val r = powershell("$script | ConvertTo-Json -Compress -Depth 4", timeoutMs)
        if (!r.ok || r.out.isBlank()) return null
        return Json.parseOrNull(r.out.trim())
    }
}

class RealShell : Shell {
    override suspend fun run(command: List<String>, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(command).redirectErrorStream(false).start()
            p.outputStream.close()
            val out = StringBuilder(); val err = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader(Charsets.UTF_8).use { r -> r.lineSequence().forEach { out.appendLine(it) } } }.apply { isDaemon = true; start() }
            val t2 = Thread { p.errorStream.bufferedReader(Charsets.UTF_8).use { r -> r.lineSequence().forEach { err.appendLine(it) } } }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return@withContext ShellResult(-1, out.toString(), "Timed out after ${timeoutMs / 1000} s") }
            t1.join(2000); t2.join(2000)
            ShellResult(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            ShellResult(-2, "", e.message ?: e::class.simpleName.orEmpty())
        }
    }
}

/** JSON helpers for PowerShell output, which is an object for one item and an array for many. */
fun JsonValue?.asList(): List<JsonValue> = when {
    this == null -> emptyList()
    this.array != null -> this.array!!
    this.obj != null -> listOf(this)
    else -> emptyList()
}

fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

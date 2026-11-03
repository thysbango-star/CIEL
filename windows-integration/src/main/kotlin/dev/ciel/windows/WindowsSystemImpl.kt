package dev.ciel.windows

import dev.ciel.core.json.json
import dev.ciel.core.json.Json
import java.io.File

/** Real Windows: PowerShell, powercfg, reg and taskkill — documented tools, no registry writes. */
class WindowsSystemImpl(private val shell: Shell) : WindowsSystem {

    override suspend fun processes(): List<ProcessInfo> {
        val list = shell.psJson("Get-Process | Select-Object Id,ProcessName,Path,WorkingSet64,@{n='Cpu';e={[double]\$_.CPU}},MainWindowTitle", 45_000).asList()
        // Command lines only for Java (Minecraft runs as javaw.exe).
        val java = shell.psJson("Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" | Select-Object ProcessId,CommandLine").asList()
            .associate { (it["ProcessId"]?.int ?: -1) to it["CommandLine"]?.string }
        return list.mapNotNull { o ->
            val pid = o["Id"]?.int ?: return@mapNotNull null
            ProcessInfo(pid, (o["ProcessName"]?.string ?: return@mapNotNull null) + ".exe", o["Path"]?.string, o["WorkingSet64"]?.long ?: 0,
                o["Cpu"]?.number ?: 0.0, o["MainWindowTitle"]?.string?.takeIf { it.isNotBlank() }, java[pid])
        }
    }

    override suspend fun closeGracefully(pid: Int): Boolean {
        // Like clicking × : the program may ask to save first. Never /F.
        val r = shell.powershell("\$p = Get-Process -Id $pid -ErrorAction SilentlyContinue; if (\$p -and \$p.MainWindowHandle -ne 0) { [void]\$p.CloseMainWindow(); 'window' } else { 'none' }")
        if (r.out.contains("window")) return true
        return shell.run(listOf("taskkill", "/PID", pid.toString())).ok
    }

    override suspend fun isRunning(pid: Int): Boolean = ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)

    override suspend fun start(path: String): Boolean = shell.run(listOf("cmd", "/c", "start", "", path), 15_000).ok

    override suspend fun powerSchemes(): List<PowerScheme> = parsePowerList(shell.run(listOf("powercfg", "/list")).out)

    override suspend fun setPowerScheme(guid: String): Boolean = shell.run(listOf("powercfg", "/setactive", guid)).ok

    override suspend fun foregroundPid(): Int? {
        val r = shell.powershell("""
Add-Type -Namespace Ciel -Name W -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr h, out uint p);'
${'$'}p = 0; [void][Ciel.W]::GetWindowThreadProcessId([Ciel.W]::GetForegroundWindow(), [ref]${'$'}p); ${'$'}p""".trimIndent())
        return r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    override suspend fun gameModeEnabled(): Boolean? = regDword("HKCU\\Software\\Microsoft\\GameBar", "AutoGameModeEnabled")?.let { it == 1 }
        ?: true.takeIf { isWindows } // Windows' default is on when the value has never been changed

    override suspend fun gpuSchedulingEnabled(): Boolean? = regDword("HKLM\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers", "HwSchMode")?.let { it == 2 }

    override suspend fun openSettings(uri: String): Boolean = shell.run(listOf("cmd", "/c", "start", "", uri)).ok

    suspend fun openFolder(path: String): Boolean {
        val f = File(path)
        return if (f.isFile) shell.run(listOf("explorer.exe", "/select,", f.absolutePath)).let { true } // explorer exits 1 even on success
        else shell.run(listOf("explorer.exe", f.absolutePath)).let { true }
    }

    private suspend fun regDword(key: String, value: String): Int? {
        val r = shell.run(listOf("reg", "query", key, "/v", value))
        return Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)").find(r.out)?.groupValues?.get(1)?.toInt(16)
    }

    companion object {
        /** "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced) *" */
        fun parsePowerList(out: String): List<PowerScheme> = out.lines().mapNotNull { l ->
            val m = Regex("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s+\\((.+?)\\)\\s*(\\*)?").find(l) ?: return@mapNotNull null
            PowerScheme(m.groupValues[1].lowercase(), m.groupValues[2], m.groupValues[3] == "*")
        }
    }
}

/** What Ciel did on this PC: when, why, under which permission, and whether it worked. */
class ActivityLog(private val file: File) {
    data class Entry(val at: Long, val what: String, val why: String, val permission: String, val ok: Boolean)

    fun add(what: String, why: String, permission: String, ok: Boolean) = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(json { "at" to System.currentTimeMillis(); "what" to what; "why" to why; "perm" to permission; "ok" to ok }.toString() + "\n")
    }

    fun recent(n: Int = 200): List<Entry> = runCatching {
        file.readLines().takeLast(n).mapNotNull { l -> Json.parseOrNull(l)?.let { o ->
            Entry(o["at"]?.long ?: 0, o["what"]?.string ?: "", o["why"]?.string ?: "", o["perm"]?.string ?: "", o["ok"]?.bool ?: false)
        } }.reversed()
    }.getOrDefault(emptyList())
}

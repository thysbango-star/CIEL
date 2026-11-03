package dev.ciel.windows

import dev.ciel.core.json.JsonValue

enum class HealthStatus { OK, ATTENTION, INFO, UNKNOWN }

data class HealthItem(val area: String, val status: HealthStatus, val detail: String, val recommendation: String? = null)

/**
 * PC Health: read-only checks explained in plain language. Nothing is changed or installed;
 * hardware problems are reported, never "repaired" by software.
 */
class PcHealth(private val shell: Shell, private val monitor: PerfMonitor) {

    suspend fun check(): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        Drives.list().forEach { d ->
            val freePct = if (d.totalBytes > 0) d.freeBytes * 100 / d.totalBytes else 0
            items += if (freePct < 10) HealthItem("Storage ${d.root}", HealthStatus.ATTENTION, "${human(d.freeBytes)} free of ${human(d.totalBytes)} ($freePct%).",
                "Windows slows down and can't update when a drive is nearly full. Open Storage → Find What's Taking Up Space.")
            else HealthItem("Storage ${d.root}", HealthStatus.OK, "${human(d.freeBytes)} free of ${human(d.totalBytes)} ($freePct%).")
        }
        monitor.basic().let { s ->
            if (s.ramTotalBytes != null && s.ramUsedBytes != null) {
                val pct = s.ramUsedBytes * 100 / s.ramTotalBytes
                items += HealthItem("Memory", if (pct > 85) HealthStatus.ATTENTION else HealthStatus.OK, "$pct% in use (${human(s.ramUsedBytes)} of ${human(s.ramTotalBytes)}).",
                    if (pct > 85) "Close programs you aren't using; Gaming Mode can do this for you." else null)
            }
        }
        if (!isWindows) return items + HealthItem("Windows checks", HealthStatus.UNKNOWN, "Not running on Windows.")
        items += startupApps()
        items += battery()
        items += power()
        items += updates()
        items += graphics()
        items += appErrors()
        return items
    }

    suspend fun startupAppList(): List<Pair<String, String>> =
        shell.psJson("Get-CimInstance Win32_StartupCommand | Select-Object Name,Command").asList().mapNotNull { o ->
            (o["Name"]?.string ?: return@mapNotNull null) to (o["Command"]?.string ?: "")
        }

    private suspend fun startupApps(): HealthItem {
        val list = startupAppList()
        return HealthItem("Startup apps", if (list.size > 12) HealthStatus.ATTENTION else HealthStatus.INFO, "${list.size} programs start with Windows: ${list.take(8).joinToString { it.first }}${if (list.size > 8) "…" else ""}",
            if (list.size > 12) "Turn off ones you don't need in Task Manager → Startup apps (Ciel doesn't change startup settings for you)." else null)
    }

    private suspend fun battery(): List<HealthItem> {
        val b = shell.psJson("Get-CimInstance Win32_Battery | Select-Object EstimatedChargeRemaining,BatteryStatus").asList().firstOrNull() ?: return emptyList()
        val charge = b["EstimatedChargeRemaining"]?.int
        val status = when (b["BatteryStatus"]?.int) { 2 -> "plugged in"; 1 -> "on battery"; 6, 7, 8, 9 -> "charging"; else -> null }
        val wear = BatteryReport.wear(shell)
        return listOf(HealthItem("Battery", if (wear != null && wear < 70) HealthStatus.ATTENTION else HealthStatus.INFO,
            listOfNotNull(charge?.let { "$it% charged" }, status, wear?.let { "holds $it% of its original capacity" }).joinToString(", ").ifBlank { "Battery present." },
            if (wear != null && wear < 70) "The battery has worn down; that's normal with age and can only be fixed by replacing it." else null))
    }

    private suspend fun power(): HealthItem {
        val r = shell.run(listOf("powercfg", "/getactivescheme"))
        val name = Regex("\\((.+)\\)").find(r.out)?.groupValues?.get(1)
        return HealthItem("Power plan", HealthStatus.INFO, name?.let { "Active: $it." } ?: "Unknown.")
    }

    private suspend fun updates(): HealthItem {
        val last = shell.psJson("Get-HotFix | Where-Object InstalledOn | Sort-Object InstalledOn -Descending | Select-Object -First 1 HotFixID,@{n='On';e={\$_.InstalledOn.ToString('yyyy-MM-dd')}}").asList().firstOrNull()
        val on = last?.get("On")?.string ?: return HealthItem("Windows Update", HealthStatus.UNKNOWN, "Couldn't read the update history.")
        val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(on), java.time.LocalDate.now()) }.getOrNull()
        return HealthItem("Windows Update", if (days != null && days > 45) HealthStatus.ATTENTION else HealthStatus.OK, "Last update installed $on${days?.let { " ($it days ago)" } ?: ""}.",
            if (days != null && days > 45) "Check Settings → Windows Update. Ciel doesn't install updates or drivers for you." else null)
    }

    private suspend fun graphics(): List<HealthItem> =
        shell.psJson("Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,@{n='Date';e={if(\$_.DriverDate){\$_.DriverDate.ToString('yyyy-MM-dd')}}}").asList().map { g ->
            HealthItem("Graphics", HealthStatus.INFO, "${g["Name"]?.string ?: "GPU"} — driver ${g["DriverVersion"]?.string ?: "?"}${g["Date"]?.string?.let { " ($it)" } ?: ""}.",
                "Driver updates come from your GPU maker or laptop maker; Ciel never installs drivers automatically.")
        }

    private suspend fun appErrors(): HealthItem {
        val list = shell.psJson("Get-WinEvent -FilterHashtable @{LogName='Application';Level=2;StartTime=(Get-Date).AddDays(-7)} -MaxEvents 200 -ErrorAction SilentlyContinue | Group-Object ProviderName | Sort-Object Count -Descending | Select-Object -First 5 Name,Count", 45_000).asList()
        if (list.isEmpty()) return HealthItem("App errors (7 days)", HealthStatus.OK, "No application errors recorded.")
        val total = list.sumOf { it["Count"]?.int ?: 0 }
        return HealthItem("App errors (7 days)", if (total > 50) HealthStatus.ATTENTION else HealthStatus.INFO,
            "Most frequent: ${list.joinToString { "${it["Name"]?.string} ×${it["Count"]?.int}" }}.", "Repeated errors from one app usually mean it needs updating or reinstalling.")
    }
}

/** Battery wear from Windows' own battery report (design vs full-charge capacity). */
object BatteryReport {
    suspend fun wear(shell: Shell): Int? {
        val file = java.io.File(System.getProperty("java.io.tmpdir"), "ciel-battery.xml")
        val r = shell.run(listOf("powercfg", "/batteryreport", "/xml", "/output", file.absolutePath), 30_000)
        if (!r.ok || !file.exists()) return null
        return runCatching { parse(file.readText()) }.getOrNull().also { file.delete() }
    }

    fun parse(xml: String): Int? {
        val design = Regex("<DesignCapacity>(\\d+)</DesignCapacity>").find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val full = Regex("<FullChargeCapacity>(\\d+)</FullChargeCapacity>").find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (design > 0) (full * 100 / design).toInt() else null
    }
}

@Suppress("unused") private fun JsonValue.str(k: String) = this[k]?.string

package dev.ciel.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory

/** One measurement. Fields are null when Windows doesn't provide them on this PC. */
data class PerfSample(
    val at: Long,
    val cpuPercent: Double?,
    val ramUsedBytes: Long?,
    val ramTotalBytes: Long?,
    val gpuPercent: Double?,
    val gpuMemoryBytes: Long?,
    val gpuTempC: Double?,
    val diskPercent: Double?,
)

/**
 * Parses typeperf's CSV stream. The first line names the counters (instances expanded from
 * wildcards); each further line has one value per counter. GPU engine utilisation is summed per
 * sample (capped at 100); adapter memory is summed.
 */
class TypeperfParser {
    private var header: List<String>? = null

    fun line(raw: String): Map<String, Double>? {
        val cells = splitCsv(raw)
        if (cells.isEmpty()) return null
        if (cells.first().startsWith("(PDH-CSV")) { header = cells; return null }
        val h = header ?: return null
        if (cells.size != h.size) return null
        val out = HashMap<String, Double>()
        for (i in 1 until cells.size) {
            val v = cells[i].trim().toDoubleOrNull() ?: continue
            val name = h[i].lowercase()
            val key = when {
                "processor(_total)" in name -> "cpu"
                "gpu engine" in name && "engtype_3d" in name -> "gpu"
                "gpu adapter memory" in name && "dedicated usage" in name -> "gpumem"
                "physicaldisk(_total)" in name -> "disk"
                else -> continue
            }
            out[key] = (out[key] ?: 0.0) + v
        }
        out["gpu"]?.let { out["gpu"] = it.coerceAtMost(100.0) }
        return out
    }

    companion object {
        fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
            for (ch in line) when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            if (line.isNotEmpty()) out += sb.toString()
            return out
        }

        val COUNTERS = listOf("\\Processor(_Total)\\% Processor Time", "\\GPU Engine(*engtype_3D)\\Utilization Percentage",
            "\\GPU Adapter Memory(*)\\Dedicated Usage", "\\PhysicalDisk(_Total)\\% Disk Time")
    }
}

/** nvidia-smi CSV (temperature, utilisation, memory used MiB) — only present on NVIDIA PCs. */
object NvidiaSmi {
    fun parse(out: String): Triple<Double?, Double?, Long?>? {
        val cells = out.lineSequence().firstOrNull { it.isNotBlank() }?.split(',')?.map { it.trim() } ?: return null
        if (cells.size < 3) return null
        return Triple(cells[0].toDoubleOrNull(), cells[1].toDoubleOrNull(), cells[2].toDoubleOrNull()?.let { (it * 1024 * 1024).toLong() })
    }
}

/**
 * Low-cost live monitor: one long-running typeperf process (2-second samples) plus the JVM's view
 * of CPU and memory; GPU temperature from nvidia-smi every 10 s when it exists.
 */
class PerfMonitor(private val shell: Shell) {
    @Suppress("DEPRECATION")
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    fun basic(): PerfSample = PerfSample(System.currentTimeMillis(),
        os?.cpuLoad?.takeIf { it >= 0 }?.times(100), os?.let { it.totalMemorySize - it.freeMemorySize }, os?.totalMemorySize, null, null, null, null)

    fun samples(intervalSeconds: Int = 2): Flow<PerfSample> = callbackFlow {
        val parser = TypeperfParser()
        var proc: Process? = null
        var temp: Double? = null
        if (isWindows) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    val r = shell.run(listOf("nvidia-smi", "--query-gpu=temperature.gpu,utilization.gpu,memory.used", "--format=csv,noheader,nounits"), 5000)
                    temp = if (r.ok) NvidiaSmi.parse(r.out)?.first else null
                    if (!r.ok) break // no NVIDIA tool: stop asking
                    kotlinx.coroutines.delay(10_000)
                }
            }
            launch(Dispatchers.IO) {
                runCatching {
                    proc = ProcessBuilder(listOf("typeperf") + TypeperfParser.COUNTERS + listOf("-si", intervalSeconds.toString())).redirectErrorStream(true).start()
                    proc!!.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) {
                            if (!isActive) break
                            val v = parser.line(l) ?: continue
                            val b = basic()
                            trySend(PerfSample(System.currentTimeMillis(), v["cpu"] ?: b.cpuPercent, b.ramUsedBytes, b.ramTotalBytes, v["gpu"], v["gpumem"]?.toLong(), temp, v["disk"]))
                        }
                    }
                }
            }
        } else {
            launch { while (isActive) { trySend(basic()); kotlinx.coroutines.delay(intervalSeconds * 1000L) } }
        }
        awaitClose { proc?.destroy() }
    }.flowOn(Dispatchers.IO)
}

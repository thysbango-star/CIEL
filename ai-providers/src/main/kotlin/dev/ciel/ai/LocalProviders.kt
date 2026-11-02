package dev.ciel.ai

import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpTransport
import dev.ciel.core.http.NetworkError
import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import kotlinx.coroutines.async

/** Where a model actually runs. Ollama on a PC is "your network", not "on this phone". */
enum class InferenceLocation(val label: String) {
    ON_DEVICE("on this phone"),
    LOCAL_NETWORK("on your network"),
    CLOUD("in the cloud");

    companion object {
        fun of(baseUrl: String): InferenceLocation {
            val host = hostOf(baseUrl)
            return when {
                host == "localhost" || host == "127.0.0.1" || host == "::1" -> ON_DEVICE
                isPrivateHost(host) -> LOCAL_NETWORK
                else -> CLOUD
            }
        }

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').let { h ->
            if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
        }.lowercase()

        /** RFC 1918 / link-local / .local / emulator host. Plain HTTP is only ever allowed to these. */
        fun isPrivateHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return host.startsWith("fd") || host.startsWith("fe80") // IPv6 ULA / link-local
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254) ||
                (p[0] == 100 && p[1] in 64..127) // carrier-grade NAT range used by Tailscale
        }

        /** Keys and prompts must never travel unencrypted across the internet. */
        fun plainHttpAllowed(baseUrl: String) = !baseUrl.startsWith("http://") || isPrivateHost(hostOf(baseUrl))
    }
}

/** A model installed in an Ollama server, with the capabilities the server reports. */
data class OllamaModel(
    val name: String,
    val sizeBytes: Long?,
    val parameterSize: String?,
    val families: List<String>,
    /** From /api/show "capabilities" (Ollama 0.6+): completion, vision, tools, embedding, thinking… null if not reported. */
    val capabilities: List<String>?,
    val contextLength: Int?,
) {
    val isChat: Boolean get() = capabilities?.contains("completion") ?: families.none { it.contains("bert") || it.contains("embed") }
    val vision: Boolean get() = capabilities?.contains("vision") ?: families.any { it == "clip" || it.contains("mllama") || it.contains("llava") }
    /** Only true when the server says so: sending tools to a model without support is an HTTP 400. */
    val tools: Boolean get() = capabilities?.contains("tools") == true
    val billions: Double? get() = parameterSize?.lowercase()?.removeSuffix("b")?.toDoubleOrNull()
        ?: ModelPicker.paramsBillions(name)
    val sizeLabel: String get() = sizeBytes?.let { "%.1f GB".format(it / 1e9) } ?: "size unknown"
}

/** Ollama's native API (documented at github.com/ollama/ollama/blob/main/docs/api.md). */
class OllamaClient(private val transport: HttpTransport) {

    /** "192.168.1.5", "192.168.1.5:11434", "http://pc.local:11434/v1" → "http://host:port". */
    fun normalise(input: String): String {
        var s = input.trim().removeSuffix("/")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.removeSuffix("/v1").removeSuffix("/api").removeSuffix("/")
        val afterScheme = s.substringAfter("://")
        val hostPort = afterScheme.substringBefore('/')
        val hasPort = if (hostPort.startsWith("[")) hostPort.substringAfter("]").startsWith(":") else hostPort.contains(':')
        return s.substringBefore("://") + "://" + (if (hasPort) hostPort else "$hostPort:11434")
    }

    suspend fun version(base: String, timeoutMs: Int = 4000): String? = try {
        val r = transport.execute(HttpRequest("$base/api/version", "GET", timeoutMillis = timeoutMs))
        if (r.isSuccess) Json.parseOrNull(r.body)?.get("version")?.string ?: "unknown" else null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    suspend fun models(base: String): List<OllamaModel> {
        val r = transport.execute(HttpRequest("$base/api/tags", "GET", timeoutMillis = 10_000))
        if (!r.isSuccess) throw ProviderException("Ollama returned HTTP ${r.code} for its model list.", r.code)
        val list = Json.parseOrNull(r.body)?.get("models")?.array ?: return emptyList()
        return list.mapNotNull { m ->
            val name = m["name"]?.string ?: m["model"]?.string ?: return@mapNotNull null
            val d = m["details"]
            OllamaModel(name, m["size"]?.long, d?.get("parameter_size")?.string,
                (d?.get("families")?.array?.mapNotNull { it.string } ?: listOfNotNull(d?.get("family")?.string)).map { it.lowercase() }, null, null)
        }
    }

    /** Adds the capabilities and context length the server reports for one model. */
    suspend fun describe(base: String, model: OllamaModel): OllamaModel = try {
        val r = transport.execute(HttpRequest("$base/api/show", "POST", body = json { "model" to model.name }.toString(), timeoutMillis = 10_000))
        val o = if (r.isSuccess) Json.parseOrNull(r.body) else null
        val caps = o?.get("capabilities")?.array?.mapNotNull { it.string }
        val ctx = o?.get("model_info")?.obj?.fields?.entries?.firstOrNull { it.key.endsWith(".context_length") }?.value?.int
        model.copy(capabilities = caps, contextLength = ctx)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        model
    }

    /** Models currently loaded in memory (fast to answer). */
    suspend fun loaded(base: String): List<String> = try {
        val r = transport.execute(HttpRequest("$base/api/ps", "GET", timeoutMillis = 4000))
        Json.parseOrNull(r.body)?.get("models")?.array?.mapNotNull { it["name"]?.string } ?: emptyList()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }
}

/**
 * Finds Ollama servers: the given address, this phone (e.g. Termux), the emulator host, then the
 * phone's own /24 Wi-Fi subnet on port 11434. Each probe is a quick HTTP request to /api/version.
 */
object OllamaDiscovery {
    fun candidates(phoneIpv4: String?, extra: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        extra.filter { it.isNotBlank() }.forEach { out += it }
        out += "127.0.0.1"; out += "10.0.2.2"
        phoneIpv4?.split('.')?.takeIf { it.size == 4 }?.let { p ->
            val prefix = "${p[0]}.${p[1]}.${p[2]}"
            val own = p[3].toIntOrNull()
            // Typical PC addresses first, then the rest of the subnet.
            val order = (listOf(1, 2, 10, 100, 101, 102, 50) + (2..254)).distinct().filter { it != own }
            order.forEach { out += "$prefix.$it" }
        }
        return out.toList()
    }

    /** Probes [hosts] [parallel] at a time; returns base URLs of servers that answered. */
    suspend fun scan(client: OllamaClient, hosts: List<String>, parallel: Int = 32, timeoutMs: Int = 700, stopAfter: Int = 3,
                     onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> = kotlinx.coroutines.coroutineScope {
        val found = java.util.concurrent.CopyOnWriteArrayList<String>()
        var done = 0
        for (batch in hosts.chunked(parallel)) {
            if (found.size >= stopAfter) break
            batch.map { h ->
                async {
                    val base = client.normalise(h)
                    if (client.version(base, timeoutMs) != null) found += base
                }
            }.forEach { it.await() }
            done += batch.size
            onProgress(done, hosts.size)
        }
        found.toList()
    }
}

/** NVIDIA's hosted API catalog (build.nvidia.com) and self-hosted NIM speak the OpenAI chat protocol. */
object Nvidia {
    const val KEY_PREFIX = "nvapi-"
    private val vision = listOf("vision", "vila", "neva", "vl", "llava", "paligemma", "phi-3.5-vision", "phi-4-multimodal", "llama-4", "kosmos")
    private val nonChat = listOf("retriever", "nemoretriever", "parse", "deplot", "cosmos", "stable-diffusion", "sdxl", "flux", "riva", "ocdr", "paddle",
        "nemoguard", "safety", "reward", "grounding", "fuyu", "bge", "nv-embed", "arctic-embed", "e5-", "clip", "nvclip", "-mt", "translate", "parakeet",
        "canary", "fastpitch", "audio2face", "esm", "molmim", "diffdock", "genmol", "proteinmpnn", "evo2", "kosmos")
    private val toolFamilies = listOf("llama-3.1", "llama-3.2", "llama-3.3", "llama-4", "llama3", "mistral", "mixtral", "nemotron", "qwen", "deepseek", "gpt-oss", "granite", "kimi")

    fun isChat(id: String): Boolean { val n = id.lowercase(); return nonChat.none { n.contains(it) } }
    fun visionOf(id: String): Boolean { val n = id.lowercase(); return vision.any { ModelPicker.hasWord(n, it) || n.contains(it) } && !n.contains("embed") }
    fun toolsOf(id: String): Boolean { val n = id.lowercase(); return toolFamilies.any { n.contains(it) } && !visionOf(n) }
}

package dev.ciel.ai

import dev.ciel.core.http.HttpTransport

/**
 * "Paste a key, press Connect": detects the provider from the key format, validates the key,
 * discovers models, chooses suitable ones and tests them. The user never sees URLs, headers
 * or model ids unless they open the developer section.
 */
object KeyDetector {
    /**
     * Presets whose documented key format matches. A key is only ever sent to a provider whose
     * format it matches (never "tried" against unrelated services). Empty = ask the user.
     */
    fun candidates(apiKey: String): List<ProviderPreset> {
        val k = apiKey.trim()
        val key = when {
            k.startsWith(Nvidia.KEY_PREFIX) -> "nvidia"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("AIza") -> "gemini"
            k.startsWith("sk-") -> "openai"
            else -> null
        }
        return listOfNotNull(key?.let { ProviderPresets.byKey(it) })
    }
}

/** Which models Ciel uses for which job. */
data class ModelPlan(
    val fastCandidates: List<ModelInfo>,
    val strongCandidates: List<ModelInfo>,
    val visionCandidates: List<ModelInfo>,
)

/**
 * Heuristic model selection from a provider's live model list. Heuristics are deliberately
 * simple and documented: filter out non-chat models, then rank by keywords, parameter size and
 * version number. The developer section lets the user override any choice.
 */
object ModelPicker {
    private val nonChat = listOf(
        "embed", "embedding", "whisper", "tts", "audio", "transcribe", "dall-e", "image-gen", "imagen", "moderation",
        "realtime", "search", "guard", "aqa", "veo", "native-audio", "live", "playai", "rerank", "babbage", "davinci",
        "computer-use", "omni-moderation", "-image", "lyria", "learnlm", "robotics", "distil",
    )
    private val fastWords = listOf("flash", "mini", "nano", "lite", "instant", "small", "haiku", "fast", "8b", "7b", "9b", "4b", "3b", "1b")
    private val strongWords = listOf("pro", "large", "versatile", "ultra", "opus", "sonnet", "maverick", "405b", "235b", "120b", "70b", "72b")

    /** Whole-token match, so "mini" doesn't match "gemini". */
    fun hasWord(id: String, word: String): Boolean =
        Regex("(^|[-_/:. ])${Regex.escape(word)}($|[-_/:. ])").containsMatchIn(id.lowercase())

    fun isChatModel(presetKey: String?, id: String): Boolean {
        val n = id.lowercase()
        if (nonChat.any { n.contains(it) }) return false
        return when (presetKey) {
            "gemini" -> n.startsWith("gemini")
            "nvidia" -> Nvidia.isChat(n)
            "openai" -> (n.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(n) || n.startsWith("chatgpt")) && !n.contains("instruct")
            else -> true
        }
    }

    /** Fills capability fields the provider's list doesn't report, from documented model families. */
    fun withCapabilities(presetKey: String?, m: ModelInfo): ModelInfo {
        val n = m.id.lowercase()
        val vision = m.supportsVision ?: when (presetKey) {
            "gemini" -> true
            "openai" -> n.startsWith("gpt-4o") || n.startsWith("gpt-4.1") || n.startsWith("gpt-5") || Regex("^o[34]").containsMatchIn(n)
            "groq" -> n.contains("vision") || n.contains("llama-4") || n.contains("scout")
            "nvidia" -> Nvidia.visionOf(n)
            else -> null
        }
        val tools = m.supportsTools ?: when (presetKey) {
            "gemini", "openai", "groq" -> true
            "nvidia" -> Nvidia.toolsOf(n)
            else -> null
        }
        return m.copy(supportsVision = vision, supportsTools = tools)
    }

    fun paramsBillions(id: String): Double? =
        Regex("(\\d+(?:\\.\\d+)?)b(?![a-z])").findAll(id.lowercase()).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()

    fun version(id: String): Double {
        // First number that is not a parameter count, e.g. gemini-2.5-flash → 2.5, gpt-4.1-mini → 4.1
        val m = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)(?![\\d.]*b(?![a-z]))").find(id.lowercase().replace(Regex("\\d{6,}"), ""))
        return m?.groupValues?.get(1)?.toDoubleOrNull()?.coerceAtMost(50.0) ?: 0.0
    }

    private fun unstable(id: String) = Regex("preview|exp|experimental|beta|\\d{4}-\\d{2}-\\d{2}|-\\d{4}$").containsMatchIn(id.lowercase())

    fun strength(id: String): Double {
        val n = id.lowercase()
        var s = version(n) * 2
        s += paramsBillions(n)?.let { kotlin.math.ln(it + 1) * 2 } ?: 0.0
        if (strongWords.any { hasWord(n, it) }) s += 4
        if (fastWords.any { hasWord(n, it) }) s -= 4
        if (unstable(n)) s -= 1.5
        return s
    }

    fun plan(presetKey: String?, models: List<ModelInfo>): ModelPlan {
        var usable = models.filter { isChatModel(presetKey, it.id) }.map { withCapabilities(presetKey, it) }
        // Prefer free models when the provider publishes pricing and offers free tool-capable ones.
        val free = usable.filter { it.freeTier == FreeTier.FREE && it.supportsTools != false }
        if (free.isNotEmpty()) usable = free
        val tooled = usable.filter { it.supportsTools != false }.ifEmpty { usable }

        val isFast = { m: ModelInfo -> fastWords.any { hasWord(m.id, it) } }
        val fastPool = tooled.filter(isFast).ifEmpty { tooled }
        val fast = fastPool.sortedWith(compareBy<ModelInfo> { unstable(it.id) }.thenByDescending { version(it.id) }.thenBy { paramsBillions(it.id) ?: 0.0 }) +
            // Fallbacks if every "fast" model is unavailable: the rest, lightest first.
            tooled.filter { it !in fastPool }.sortedBy { strength(it.id) }
        val strong = tooled.sortedWith(compareBy<ModelInfo> { unstable(it.id) }.thenByDescending { strength(it.id) })
        val vision = usable.filter { it.supportsVision == true }
            .sortedWith(compareBy<ModelInfo> { unstable(it.id) }.thenByDescending { strength(it.id) })
        return ModelPlan(fast, strong, vision)
    }
}

sealed class ConnectResult {
    data class Success(val configs: List<ProviderConfig>, val message: String, val details: List<String>) : ConnectResult()
    /** The key format didn't identify the provider: show the simple provider picker. */
    data class NeedsProvider(val reason: String) : ConnectResult()
    data class Failure(val message: String, val howToFix: String) : ConnectResult()
}

class ConnectionWizard(
    private val transport: HttpTransport,
    private val factory: (ProviderConfig, String?, HttpTransport) -> AiProvider = ProviderFactory::create,
) {
    suspend fun connect(
        apiKey: String,
        connectionId: String,
        presetKey: String? = null,
        customBaseUrl: String? = null,
        onStatus: (String) -> Unit = {},
    ): ConnectResult {
        val key = apiKey.trim()
        val preset = when {
            presetKey != null -> ProviderPresets.byKey(presetKey) ?: return ConnectResult.Failure("Unknown provider.", "Choose a provider from the list.")
            else -> KeyDetector.candidates(key).firstOrNull()
                ?: return ConnectResult.NeedsProvider("I couldn't tell which service this key belongs to. Please choose the provider.")
        }
        val local = preset.key == "custom" || preset.key == "nim" || preset.key == "ollama"
        if (preset.key == "ollama") return connectOllama(customBaseUrl ?: "", connectionId, onStatus)
        if (key.isEmpty() && !local) return ConnectResult.Failure("No API key entered.", "Paste the key you copied from ${preset.displayName}.")
        val baseUrl = (if (local) customBaseUrl?.trim()?.takeIf { it.startsWith("http") } else null) ?: preset.baseUrl
        if (local && customBaseUrl.isNullOrBlank()) return ConnectResult.Failure("This service needs its address.", "Enter the server address, e.g. ${preset.baseUrl}")
        if (!InferenceLocation.plainHttpAllowed(baseUrl)) return ConnectResult.Failure("Refusing an unencrypted internet address.",
            "Plain http:// is only allowed on your own network (e.g. 192.168.x.x). Use https:// for servers on the internet.")

        val base = ProviderConfig(
            id = "$connectionId:probe", displayName = preset.displayName, protocol = preset.protocol, baseUrl = baseUrl,
            model = preset.suggestedModel,
            authStyle = if (local && key.isEmpty()) AuthStyle.NONE else if (local) AuthStyle.BEARER else preset.authStyle,
            authHeaderName = if (preset.protocol == ProtocolKind.GEMINI_GENERATE_CONTENT) "x-goog-api-key" else null,
            supportsTools = true, dataPolicyAcknowledged = true,
        )

        onStatus("Checking your key with ${preset.displayName}…")
        val models = try {
            factory(base, key, transport).listModels()
        } catch (e: ProviderException) {
            return failure(e, preset)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ConnectResult.Failure("Couldn't reach ${preset.displayName}.", e.message ?: e::class.simpleName ?: "Unknown error")
        }
        if (models.isEmpty()) return ConnectResult.Failure("${preset.displayName} returned no models for this key.", "Check that the key is active and has access to models in your ${preset.displayName} account.")

        onStatus("Found ${models.size} models. Choosing the best ones…")
        val plan = ModelPicker.plan(preset.key, models)
        if (plan.fastCandidates.isEmpty()) return ConnectResult.Failure("No chat models are available for this key.", "Your ${preset.displayName} account may need chat models enabled.")

        val details = mutableListOf<String>()
        onStatus("Testing a fast model…")
        val fast = firstWorking(base, key, plan.fastCandidates.take(4), details)
            ?: return ConnectResult.Failure("The key works, but no model answered a test message.", details.lastOrNull() ?: "Check your account's quota or billing.")
        onStatus("Testing a reasoning model…")
        val strong = if (plan.strongCandidates.firstOrNull()?.id == fast.id) fast
            else firstWorking(base, key, plan.strongCandidates.filter { it.id != fast.id }.take(3), details) ?: fast

        fun cfg(role: String, m: ModelInfo, tier: CapabilityTier, label: String) = base.copy(
            id = "$connectionId:$role", displayName = "${preset.displayName} · $label", model = m.id, tier = tier,
            contextWindow = m.contextWindow, supportsVision = m.supportsVision == true, supportsTools = m.supportsTools != false,
            freeTier = m.freeTier,
        )
        val configs = mutableListOf(cfg("fast", fast, CapabilityTier.ECONOMY, "fast"))
        if (strong.id != fast.id) configs += cfg("strong", strong, CapabilityTier.ADVANCED, "reasoning")
        else configs[0] = configs[0].copy(tier = CapabilityTier.STANDARD)
        if (configs.none { it.supportsVision }) {
            plan.visionCandidates.firstOrNull()?.let { configs += cfg("vision", it, CapabilityTier.STANDARD, "vision") }
        }
        details.add(0, "Fast: ${fast.id}")
        if (strong.id != fast.id) details.add(1, "Reasoning: ${strong.id}")
        configs.firstOrNull { it.id.endsWith(":vision") }?.let { details += "Images: ${it.model}" }
        if (configs.none { it.supportsVision }) details += "No image-capable model found for this key."
        return ConnectResult.Success(configs, "Connection successful. Ciel is ready.", details)
    }

    private suspend fun firstWorking(base: ProviderConfig, key: String, candidates: List<ModelInfo>, log: MutableList<String>): ModelInfo? {
        for (m in candidates) {
            val p = factory(base.copy(model = m.id, supportsStreaming = false), key, transport)
            try {
                p.chat(ChatRequest(listOf(ChatMessage.user("Reply with one word: ready")), maxOutputTokens = 16))
                return m
            } catch (e: ProviderException) {
                log += "${m.id} unavailable: ${e.message?.take(120)}"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log += "${m.id} unavailable: ${e.message?.take(120)}"
            }
        }
        return null
    }

    private fun failure(e: ProviderException, preset: ProviderPreset): ConnectResult.Failure = when {
        e.isAuthError || e.httpCode == 400 && preset.key == "gemini" ->
            ConnectResult.Failure("${preset.displayName} rejected this key.", "Copy the key again from ${preset.keyUrl.ifBlank { "your provider" }} — make sure nothing is missing at the start or end.")
        e.isRateLimit -> ConnectResult.Failure("${preset.displayName} says you've hit a usage limit.", "Wait a minute and try again, or check your plan's limits.")
        e.httpCode == 404 -> ConnectResult.Failure("The service address wasn't found.", "For a custom service, check the address (it usually ends in /v1).")
        e.httpCode == null && (e.cause?.message ?: e.message ?: "").contains("plain HTTP") ->
            ConnectResult.Failure("Android blocked the connection to ${preset.displayName}.", "Update Ciel (0.5+ allows plain HTTP on your own network).")
        e.httpCode == null -> ConnectResult.Failure("Couldn't reach ${preset.displayName}.", e.message ?: "Check your internet connection and try again.")
        else -> ConnectResult.Failure("${preset.displayName} returned an error.", e.message ?: "Try again in a moment.")
    }

    /**
     * Ollama: find the server, list installed models with the capabilities the server reports,
     * pick fast / reasoning / vision models, test one, and configure tool use only where the
     * model supports it (otherwise the server answers HTTP 400).
     */
    suspend fun connectOllama(address: String, connectionId: String, onStatus: (String) -> Unit = {}, client: OllamaClient = OllamaClient(transport)): ConnectResult {
        if (address.isBlank()) return ConnectResult.Failure("Ollama's address is needed.", "Use \"Find Ollama\" or enter the PC's address, e.g. 192.168.1.10")
        val host = client.normalise(address)
        if (!InferenceLocation.plainHttpAllowed(host)) return ConnectResult.Failure("Refusing an unencrypted internet address.", "Use an address on your own network, or https:// with a secure proxy.")
        onStatus("Looking for Ollama at $host…")
        val version = client.version(host, 6000) ?: return ConnectResult.Failure("Ollama didn't answer at $host.",
            "On the PC: make sure Ollama is running and listening on the network — set OLLAMA_HOST=0.0.0.0 and restart Ollama, allow port 11434 in the firewall, and keep the phone on the same Wi-Fi.")
        onStatus("Ollama $version found. Reading installed models…")
        val installed = try { client.models(host) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ConnectResult.Failure("Ollama answered but its model list failed.", e.message ?: "")
        }
        if (installed.isEmpty()) return ConnectResult.Failure("No models are installed in Ollama.", "On the PC run, for example: ollama pull llama3.2  (≈2 GB) — then connect again.")
        val described = installed.take(16).map { client.describe(host, it) }
        val chat = described.filter { it.isChat }
        if (chat.isEmpty()) return ConnectResult.Failure("Only embedding models are installed.", "Install a chat model, e.g. ollama pull llama3.2")
        val bySize = chat.sortedBy { it.billions ?: 99.0 }
        val loaded = client.loaded(host).toSet()
        // Fast: a loaded model if there is one (no load delay), else the smallest; prefer tool support.
        val fastPool = bySize.filter { it.tools }.ifEmpty { bySize }
        val fast = fastPool.firstOrNull { it.name in loaded } ?: fastPool.first()
        val strong = chat.filter { it.tools }.ifEmpty { chat }.maxByOrNull { it.billions ?: 0.0 }!!
        val vision = chat.filter { it.vision }.maxByOrNull { it.billions ?: 0.0 }

        val base = ProviderConfig(
            id = "$connectionId:probe", displayName = "Ollama", protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "$host/v1", model = fast.name, authStyle = AuthStyle.NONE, supportsStreaming = true, dataPolicyAcknowledged = true,
            freeTier = FreeTier.FREE,
        )
        onStatus("Testing ${fast.name} (the first answer can take a minute while the model loads)…")
        try {
            factory(base.copy(supportsStreaming = false, supportsTools = false), null, transport)
                .chat(ChatRequest(listOf(ChatMessage.user("Reply with one word: ready")), maxOutputTokens = 16))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ConnectResult.Failure("${fast.name} didn't answer a test message.", (e.message ?: "") +
                " — the PC may need more memory for this model; try a smaller one (e.g. ollama pull llama3.2:1b).")
        }
        val where = InferenceLocation.of(host).label
        fun cfg(role: String, m: OllamaModel, tier: CapabilityTier, label: String) = base.copy(
            id = "$connectionId:$role", displayName = "Ollama · $label", model = m.name, tier = tier,
            contextWindow = m.contextLength, supportsVision = m.vision, supportsTools = m.tools,
        )
        val configs = mutableListOf(cfg("fast", fast, if (strong.name == fast.name) CapabilityTier.STANDARD else CapabilityTier.ECONOMY, "fast"))
        if (strong.name != fast.name) configs += cfg("strong", strong, CapabilityTier.ADVANCED, "reasoning")
        if (vision != null && configs.none { it.supportsVision }) configs += cfg("vision", vision, CapabilityTier.STANDARD, "vision")
        val details = mutableListOf("Runs $where ($host), Ollama $version", "Fast: ${fast.name} (${fast.sizeLabel})")
        if (strong.name != fast.name) details += "Reasoning: ${strong.name} (${strong.sizeLabel})"
        details += vision?.let { "Images: ${it.name}" } ?: "No image-capable model installed (e.g. ollama pull llava or gemma3)."
        val noTools = configs.filter { !it.supportsTools }.map { it.model }
        if (noTools.isNotEmpty()) details += "Without tool support (chat only, no skills): ${noTools.joinToString()}"
        if (described.any { it.capabilities == null }) details += "This Ollama version doesn't report capabilities; tools were left off to be safe. Updating Ollama enables them."
        return ConnectResult.Success(configs, "Connected to Ollama. Ciel is ready.", details)
    }
}

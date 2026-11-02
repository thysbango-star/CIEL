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
            else -> null
        }
        val tools = m.supportsTools ?: when (presetKey) {
            "gemini", "openai", "groq" -> true
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
        if (key.isEmpty() && preset.key != "custom") return ConnectResult.Failure("No API key entered.", "Paste the key you copied from ${preset.displayName}.")
        val baseUrl = (if (preset.key == "custom") customBaseUrl?.trim()?.takeIf { it.startsWith("http") } else null) ?: preset.baseUrl
        if (preset.key == "custom" && customBaseUrl.isNullOrBlank()) return ConnectResult.Failure("A custom service needs its address.", "Enter the server address, e.g. http://192.168.1.10:11434/v1")

        val base = ProviderConfig(
            id = "$connectionId:probe", displayName = preset.displayName, protocol = preset.protocol, baseUrl = baseUrl,
            model = preset.suggestedModel,
            authStyle = if (preset.key == "custom" && key.isEmpty()) AuthStyle.NONE else preset.authStyle,
            authHeaderName = if (preset.protocol == ProtocolKind.GEMINI_GENERATE_CONTENT) "x-goog-api-key" else null,
            supportsTools = true, dataPolicyAcknowledged = true,
        )

        onStatus("Checking your key with ${preset.displayName}…")
        val models = try {
            factory(base, key, transport).listModels()
        } catch (e: ProviderException) {
            return failure(e, preset)
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
            }
        }
        return null
    }

    private fun failure(e: ProviderException, preset: ProviderPreset): ConnectResult.Failure = when {
        e.isAuthError || e.httpCode == 400 && preset.key == "gemini" ->
            ConnectResult.Failure("${preset.displayName} rejected this key.", "Copy the key again from ${preset.keyUrl.ifBlank { "your provider" }} — make sure nothing is missing at the start or end.")
        e.isRateLimit -> ConnectResult.Failure("${preset.displayName} says you've hit a usage limit.", "Wait a minute and try again, or check your plan's limits.")
        e.httpCode == 404 -> ConnectResult.Failure("The service address wasn't found.", "For a custom service, check the address (it usually ends in /v1).")
        e.httpCode == null -> ConnectResult.Failure("Couldn't reach ${preset.displayName}.", "Check your internet connection and try again.")
        else -> ConnectResult.Failure("${preset.displayName} returned an error.", e.message ?: "Try again in a moment.")
    }
}

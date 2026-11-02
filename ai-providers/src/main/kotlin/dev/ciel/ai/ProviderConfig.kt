package dev.ciel.ai

/** Which wire protocol an adapter speaks. Different protocols get different adapters. */
enum class ProtocolKind { OPENAI_CHAT_COMPLETIONS, GEMINI_GENERATE_CONTENT }

enum class AuthStyle {
    /** `Authorization: Bearer <key>` */
    BEARER,
    /** A custom header named by [ProviderConfig.authHeaderName], value = key. */
    CUSTOM_HEADER,
    /** No authentication (e.g. a local server). */
    NONE,
}

/** Relative cost/capability tier the user assigns (or accepts from a preset) for automatic selection. */
enum class CapabilityTier { ECONOMY, STANDARD, ADVANCED }

/**
 * A user-configured provider + model. The API key is NOT stored here: it lives in the
 * encrypted key store and is referenced by [id].
 */
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val protocol: ProtocolKind,
    val baseUrl: String,
    val model: String,
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val authHeaderName: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = true,
    val tier: CapabilityTier = CapabilityTier.STANDARD,
    val freeTier: FreeTier = FreeTier.UNKNOWN,
    /** Free text the user enters, e.g. "30 req/min". Never auto-filled with invented numbers. */
    val rateLimitNote: String? = null,
    /** Optional user-defined daily request budget used by the selector. */
    val dailyRequestLimit: Int? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val enabled: Boolean = true,
    /** Shown before first use: providers process the data you send them. */
    val dataPolicyAcknowledged: Boolean = false,
)

/**
 * Known endpoints. Only facts that are stable and documented are included (base URLs and
 * protocol). Model names are suggestions the user can change; capabilities and free-tier
 * status are left for the live model list or the user to fill in.
 */
data class ProviderPreset(
    val key: String,
    val displayName: String,
    val protocol: ProtocolKind,
    val baseUrl: String,
    val suggestedModel: String,
    val keyUrl: String,
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val notes: String,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset(
            key = "gemini", displayName = "Google Gemini API",
            protocol = ProtocolKind.GEMINI_GENERATE_CONTENT,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            suggestedModel = "gemini-2.5-flash",
            keyUrl = "https://aistudio.google.com/apikey",
            authStyle = AuthStyle.CUSTOM_HEADER,
            notes = "Uses Gemini's native generateContent protocol. Check Google's current terms for free-tier limits and data use.",
        ),
        ProviderPreset(
            key = "openrouter", displayName = "OpenRouter",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://openrouter.ai/api/v1",
            suggestedModel = "openrouter/auto",
            keyUrl = "https://openrouter.ai/keys",
            notes = "OpenAI-compatible. The model list reports per-model pricing, so free models are detected live.",
        ),
        ProviderPreset(
            key = "groq", displayName = "Groq",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.groq.com/openai/v1",
            suggestedModel = "llama-3.3-70b-versatile",
            keyUrl = "https://console.groq.com/keys",
            notes = "OpenAI-compatible. Check Groq's console for your current rate limits.",
        ),
        ProviderPreset(
            key = "openai", displayName = "OpenAI",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.openai.com/v1",
            suggestedModel = "gpt-4o-mini",
            keyUrl = "https://platform.openai.com/api-keys",
            notes = "Paid API.",
        ),
        ProviderPreset(
            key = "nvidia", displayName = "NVIDIA API catalog",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://integrate.api.nvidia.com/v1",
            suggestedModel = "meta/llama-3.1-8b-instruct",
            keyUrl = "https://build.nvidia.com",
            notes = "NVIDIA-hosted models (build.nvidia.com), OpenAI-compatible. Keys start with nvapi-. Check NVIDIA's current limits for your account.",
        ),
        ProviderPreset(
            key = "ollama", displayName = "Ollama",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://192.168.1.10:11434/v1",
            suggestedModel = "llama3.2",
            keyUrl = "",
            authStyle = AuthStyle.NONE,
            notes = "Your own Ollama server (on a PC on your network, or on this phone). No key needed. Ciel finds it and its installed models.",
        ),
        ProviderPreset(
            key = "nim", displayName = "Self-hosted NVIDIA NIM",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://192.168.1.10:8000/v1",
            suggestedModel = "",
            keyUrl = "",
            authStyle = AuthStyle.NONE,
            notes = "A NIM container you run yourself exposes an OpenAI-compatible API (default port 8000).",
        ),
        ProviderPreset(
            key = "custom", displayName = "Custom OpenAI-compatible endpoint",
            protocol = ProtocolKind.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://192.168.1.10:11434/v1",
            suggestedModel = "llama3.2",
            keyUrl = "",
            authStyle = AuthStyle.NONE,
            notes = "Any server exposing /chat/completions (Ollama, LM Studio, vLLM, LiteLLM...). Edit URL and auth.",
        ),
    )

    fun byKey(key: String) = all.firstOrNull { it.key == key }
}

/** Builds the right adapter for a config. New protocols are added here. */
object ProviderFactory {
    fun create(config: ProviderConfig, apiKey: String?, transport: dev.ciel.core.http.HttpTransport): AiProvider =
        when (config.protocol) {
            ProtocolKind.OPENAI_CHAT_COMPLETIONS -> OpenAiCompatibleProvider(config, apiKey, transport)
            ProtocolKind.GEMINI_GENERATE_CONTENT -> GeminiProvider(config, apiKey, transport)
        }
}

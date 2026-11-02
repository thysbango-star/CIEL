package dev.ciel.engine

import dev.ciel.ai.AiProvider
import dev.ciel.ai.ChatRequest
import dev.ciel.ai.ChatResult
import dev.ciel.ai.ProviderConfig
import dev.ciel.ai.ProviderException
import dev.ciel.ai.ProviderFactory
import dev.ciel.ai.StreamEvent
import dev.ciel.core.http.HttpTransport
import kotlinx.coroutines.CancellationException

/** Supplies configured providers and their keys (from encrypted storage in the app). */
interface ProviderSource {
    fun configs(): List<ProviderConfig>
    fun apiKey(providerId: String): String?
}

/**
 * Executes a model call on a ranked list of providers, falling back when one fails with a
 * transient error (rate limit, server error, network) and reporting which one answered.
 */
class LlmGateway(
    private val source: ProviderSource,
    private val transport: HttpTransport,
    private val usage: UsageTracker,
    private val factory: (ProviderConfig, String?, HttpTransport) -> AiProvider = ProviderFactory::create,
) {
    class AllProvidersFailed(val errors: List<String>) : Exception("All providers failed:\n" + errors.joinToString("\n"))

    fun provider(config: ProviderConfig): AiProvider = factory(config, source.apiKey(config.id), transport)

    /**
     * Calls the providers in [ordered] until one succeeds. [onDelta] receives streamed text
     * (only for the provider that ends up answering; partial output from a failed provider is
     * discarded by [onReset]).
     */
    suspend fun call(
        ordered: List<ProviderConfig>,
        request: ChatRequest,
        stream: Boolean,
        onProvider: suspend (ProviderConfig) -> Unit = {},
        onDelta: suspend (String) -> Unit = {},
        onReset: suspend () -> Unit = {},
    ): Pair<ProviderConfig, ChatResult> {
        val errors = mutableListOf<String>()
        for (config in ordered) {
            var streamedAny = false
            onProvider(config)
            val p = provider(config)
            try {
                val result = if (stream && config.supportsStreaming) {
                    var completed: ChatResult? = null
                    p.stream(request).collect { ev ->
                        when (ev) {
                            is StreamEvent.TextDelta -> { streamedAny = true; onDelta(ev.text) }
                            is StreamEvent.Completed -> completed = ev.result
                        }
                    }
                    completed ?: throw ProviderException("Stream ended without a result.")
                } else p.chat(request)
                usage.record(config.id, success = true, rateLimited = false)
                return config to result
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                usage.record(config.id, success = false, rateLimited = e.isRateLimit)
                errors += "${config.displayName}: ${e.message}"
                if (streamedAny) onReset()
                // Request-shape errors (400) would fail identically elsewhere only if the issue is
                // ours; still try the next provider since formats differ between providers.
            } catch (e: Exception) {
                usage.record(config.id, success = false, rateLimited = false)
                errors += "${config.displayName}: ${e.message ?: e::class.simpleName}"
                if (streamedAny) onReset()
            }
        }
        throw AllProvidersFailed(errors)
    }
}

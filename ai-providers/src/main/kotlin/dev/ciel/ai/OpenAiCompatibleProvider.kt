package dev.ciel.ai

import dev.ciel.core.Redaction
import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpTransport
import dev.ciel.core.http.NetworkError
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonArray
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * Adapter for the OpenAI Chat Completions protocol, used by OpenAI, OpenRouter, Groq,
 * and most self-hosted servers. Provider-specific differences (model list fields,
 * pricing) are handled defensively rather than assumed.
 */
class OpenAiCompatibleProvider(
    override val config: ProviderConfig,
    private val apiKey: String?,
    private val transport: HttpTransport,
) : AiProvider {

    private val base = config.baseUrl.trimEnd('/')

    internal fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        when (config.authStyle) {
            AuthStyle.BEARER -> if (!apiKey.isNullOrBlank()) h["Authorization"] = "Bearer $apiKey"
            AuthStyle.CUSTOM_HEADER -> if (!apiKey.isNullOrBlank()) h[config.authHeaderName ?: "api-key"] = apiKey
            AuthStyle.NONE -> Unit
        }
        h.putAll(config.extraHeaders)
        return h
    }

    internal fun buildBody(request: ChatRequest, stream: Boolean): JsonObject = json {
        "model" to (request.model ?: config.model)
        "messages" to JsonArray(request.messages.map(::encodeMessage))
        if (request.tools.isNotEmpty() && config.supportsTools) {
            "tools" to JsonArray(request.tools.map { t ->
                json {
                    "type" to "function"
                    "function" to json {
                        "name" to t.name
                        "description" to t.description
                        "parameters" to t.parameters
                    }
                }
            })
        }
        opt("temperature", request.temperature ?: config.temperature)
        opt("max_tokens", request.maxOutputTokens ?: config.maxOutputTokens)
        if (stream) "stream" to true
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = json {
        "role" to when (m.role) {
            Role.SYSTEM -> "system"; Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.TOOL -> "tool"
        }
        if (m.images.isNotEmpty() && config.supportsVision) {
            val parts = mutableListOf<JsonValue>()
            if (m.content.isNotEmpty()) parts += json { "type" to "text"; "text" to m.content }
            m.images.forEach { img ->
                parts += json {
                    "type" to "image_url"
                    "image_url" to json { "url" to "data:${img.mimeType};base64,${img.base64Data}" }
                }
            }
            "content" to JsonArray(parts)
        } else {
            "content" to m.content
        }
        if (m.toolCalls.isNotEmpty()) {
            "tool_calls" to JsonArray(m.toolCalls.map { c ->
                json {
                    "id" to c.id
                    "type" to "function"
                    "function" to json { "name" to c.name; "arguments" to c.argumentsJson }
                }
            })
        }
        opt("tool_call_id", m.toolCallId)
    }

    internal fun parseResult(body: JsonValue): ChatResult {
        val choice = body["choices"]?.array?.firstOrNull()
            ?: throw ProviderException("Provider returned no choices: ${Redaction.redact(body.toString().take(300))}")
        val message = choice["message"]
        val text = message?.get("content")?.let { c ->
            c.string ?: c.array?.mapNotNull { it["text"]?.string }?.joinToString("")
        } ?: ""
        val calls = message?.get("tool_calls")?.array?.mapIndexed { i, tc ->
            ToolCall(
                id = tc["id"]?.string ?: "call_$i",
                name = tc["function"]?.get("name")?.string ?: "",
                argumentsJson = tc["function"]?.get("arguments")?.let { it.string ?: it.toString() } ?: "{}",
            )
        } ?: emptyList()
        val usage = body["usage"]?.let { TokenUsage(it["prompt_tokens"]?.int, it["completion_tokens"]?.int) }
        return ChatResult(text, calls, choice["finish_reason"]?.string, usage, body["model"]?.string)
    }

    override suspend fun chat(request: ChatRequest): ChatResult {
        val response = try {
            transport.execute(HttpRequest("$base/chat/completions", "POST", headers(), buildBody(request, false).toString(), 120_000))
        } catch (e: NetworkError) {
            throw ProviderException(e.message ?: "Network error", cause = e)
        }
        if (!response.isSuccess) throw httpError(response.code, response.body)
        val parsed = Json.parseOrNull(response.body) ?: throw ProviderException("Provider returned invalid JSON.")
        parsed["error"]?.let { throw ProviderException("Provider error: ${errorMessage(it)}") }
        return parseResult(parsed)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val text = StringBuilder()
        // Tool-call fragments arrive split across chunks, keyed by index.
        val callIds = sortedMapOf<Int, String>()
        val callNames = sortedMapOf<Int, StringBuilder>()
        val callArgs = sortedMapOf<Int, StringBuilder>()
        var finish: String? = null
        var usage: TokenUsage? = null
        var model: String? = null

        val req = HttpRequest("$base/chat/completions", "POST", headers() + ("Accept" to "text/event-stream"), buildBody(request, true).toString(), 120_000)
        transport.streamLines(req).collect { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@collect
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]" || data.isEmpty()) return@collect
            val chunk = Json.parseOrNull(data) ?: return@collect
            chunk["error"]?.let { throw ProviderException("Provider error: ${errorMessage(it)}") }
            model = chunk["model"]?.string ?: model
            chunk["usage"]?.obj?.let { usage = TokenUsage(it["prompt_tokens"]?.int, it["completion_tokens"]?.int) }
            val choice = chunk["choices"]?.array?.firstOrNull() ?: return@collect
            choice["finish_reason"]?.string?.let { finish = it }
            val delta = choice["delta"] ?: return@collect
            delta["content"]?.string?.let { if (it.isNotEmpty()) { text.append(it); emit(StreamEvent.TextDelta(it)) } }
            delta["tool_calls"]?.array?.forEach { tc ->
                val idx = tc["index"]?.int ?: 0
                tc["id"]?.string?.let { callIds[idx] = it }
                tc["function"]?.get("name")?.string?.let { callNames.getOrPut(idx) { StringBuilder() }.append(it) }
                tc["function"]?.get("arguments")?.string?.let { callArgs.getOrPut(idx) { StringBuilder() }.append(it) }
            }
        }
        val indices = (callIds.keys + callNames.keys + callArgs.keys).toSortedSet()
        val calls = indices.map { i ->
            ToolCall(callIds[i] ?: "call_$i", callNames[i]?.toString() ?: "", callArgs[i]?.toString()?.ifBlank { "{}" } ?: "{}")
        }
        emit(StreamEvent.Completed(ChatResult(text.toString(), calls, finish, usage, model)))
    }.catch { e ->
        throw when (e) {
            is ProviderException -> e
            is NetworkError.Http -> httpError(e.code, e.responseBody)
            is NetworkError -> ProviderException(e.message ?: "Network error", cause = e)
            else -> e
        }
    }

    override suspend fun listModels(): List<ModelInfo> {
        val response = try {
            transport.execute(HttpRequest("$base/models", "GET", headers() - "Content-Type", null, 30_000))
        } catch (e: NetworkError) {
            throw ProviderException(e.message ?: "Network error", cause = e)
        }
        if (!response.isSuccess) throw httpError(response.code, response.body)
        val parsed = Json.parseOrNull(response.body) ?: throw ProviderException("Model list was not valid JSON.")
        val items = parsed["data"]?.array ?: parsed["models"]?.array ?: JsonArray(emptyList())
        return items.mapNotNull { parseModel(it) }.sortedBy { it.id }
    }

    internal fun parseModel(m: JsonValue): ModelInfo? {
        val id = m["id"]?.string ?: m["name"]?.string ?: return null
        val ctx = m["context_length"]?.int ?: m["context_window"]?.int
            ?: m["top_provider"]?.get("context_length")?.int
        // OpenRouter publishes pricing per model; only then do we state free/paid.
        val pricing = m["pricing"]
        val (free, source) = if (pricing != null) {
            val p = pricing["prompt"]?.let { it.string?.toDoubleOrNull() ?: it.number }
            val c = pricing["completion"]?.let { it.string?.toDoubleOrNull() ?: it.number }
            if (p != null && c != null) (if (p == 0.0 && c == 0.0) FreeTier.FREE else FreeTier.PAID) to "Provider pricing data"
            else FreeTier.UNKNOWN to null
        } else FreeTier.UNKNOWN to null
        val modalities = m["architecture"]?.get("input_modalities")?.array?.mapNotNull { it.string }
        val vision = modalities?.contains("image")
        val tools = m["supported_parameters"]?.array?.mapNotNull { it.string }?.contains("tools")
        return ModelInfo(id, m["name"]?.string ?: id, ctx, vision, tools, free, source)
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val r = chat(ChatRequest(listOf(ChatMessage.user("Reply with the single word: ready")), maxOutputTokens = 16))
            ConnectionTestResult.Success(System.currentTimeMillis() - start, "Model ${r.model ?: config.model} replied: ${r.text.take(40)}")
        } catch (e: ProviderException) {
            ConnectionTestResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun httpError(code: Int, body: String): ProviderException {
        val msg = Json.parseOrNull(body)?.get("error")?.let(::errorMessage) ?: body.take(200)
        val hint = when (code) {
            401, 403 -> "Authentication failed — check the API key."
            404 -> "Endpoint or model not found — check the base URL and model name."
            429 -> "Rate limit or quota reached."
            in 500..599 -> "The provider is having problems."
            else -> "Request failed."
        }
        return ProviderException("$hint (HTTP $code) ${Redaction.redact(msg, listOfNotNull(apiKey))}".trim(), code)
    }

    private fun errorMessage(err: JsonValue): String = err.string ?: err["message"]?.string ?: err.toString().take(200)
}

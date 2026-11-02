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
 * Adapter for Google's native Gemini API (generateContent / streamGenerateContent).
 * Gemini differs from the OpenAI format: roles are "user"/"model", the system prompt is a
 * separate `systemInstruction`, images are `inline_data` parts, tool calls are
 * `functionCall` parts and tool results are `functionResponse` parts keyed by name.
 */
class GeminiProvider(
    override val config: ProviderConfig,
    private val apiKey: String?,
    private val transport: HttpTransport,
) : AiProvider {

    private val base = config.baseUrl.trimEnd('/')

    private fun modelPath(model: String) = if (model.startsWith("models/")) model else "models/$model"

    private fun headers(): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) put(config.authHeaderName ?: "x-goog-api-key", apiKey)
        putAll(config.extraHeaders)
    }

    internal fun buildBody(request: ChatRequest): JsonObject {
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val contents = mutableListOf<JsonValue>()
        for (m in request.messages) {
            if (m.role == Role.SYSTEM) continue
            val parts = mutableListOf<JsonValue>()
            when (m.role) {
                Role.TOOL -> parts += json {
                    "functionResponse" to json {
                        "name" to (m.toolName ?: "tool")
                        "response" to json { "result" to (Json.parseOrNull(m.content) ?: m.content) }
                    }
                }
                else -> {
                    if (m.content.isNotEmpty()) parts += json { "text" to m.content }
                    if (config.supportsVision) m.images.forEach { img ->
                        parts += json { "inline_data" to json { "mime_type" to img.mimeType; "data" to img.base64Data } }
                    }
                    m.toolCalls.forEach { c ->
                        parts += json {
                            "functionCall" to json {
                                "name" to c.name
                                "args" to (Json.parseOrNull(c.argumentsJson) as? JsonObject ?: JsonObject(emptyMap()))
                            }
                        }
                    }
                }
            }
            if (parts.isEmpty()) continue
            val role = when (m.role) { Role.ASSISTANT -> "model"; Role.TOOL -> "user"; else -> "user" }
            // Gemini requires alternating roles; merge consecutive same-role turns.
            val last = contents.lastOrNull() as? JsonObject
            if (last != null && last["role"]?.string == role) {
                val merged = (last["parts"]?.array?.items ?: emptyList()) + parts
                contents[contents.lastIndex] = json { "role" to role; "parts" to JsonArray(merged) }
            } else {
                contents += json { "role" to role; "parts" to JsonArray(parts) }
            }
        }
        return json {
            "contents" to JsonArray(contents)
            if (system.isNotBlank()) "systemInstruction" to json { "parts" to JsonArray(listOf(json { "text" to system })) }
            if (request.tools.isNotEmpty() && config.supportsTools) {
                "tools" to JsonArray(listOf(json {
                    "functionDeclarations" to JsonArray(request.tools.map { t ->
                        json { "name" to t.name; "description" to t.description; "parameters" to t.parameters }
                    })
                }))
            }
            val temp = request.temperature ?: config.temperature
            val max = request.maxOutputTokens ?: config.maxOutputTokens
            if (temp != null || max != null) {
                "generationConfig" to json { opt("temperature", temp); opt("maxOutputTokens", max) }
            }
        }
    }

    /** Parses one generateContent response (or one streamed chunk). */
    internal fun parseChunk(body: JsonValue, callOffset: Int = 0): ChatResult {
        body["error"]?.let { throw ProviderException("Gemini error: ${it["message"]?.string ?: it}") }
        val candidate = body["candidates"]?.array?.firstOrNull()
        if (candidate == null) {
            val block = body["promptFeedback"]?.get("blockReason")?.string
            if (block != null) throw ProviderException("Gemini blocked the request ($block).")
            return ChatResult("", usage = usage(body))
        }
        val parts = candidate["content"]?.get("parts")?.array ?: JsonArray(emptyList())
        val text = parts.mapNotNull { p -> if (p["thought"]?.bool == true) null else p["text"]?.string }.joinToString("")
        val calls = parts.mapNotNull { it["functionCall"] }.mapIndexed { i, fc ->
            ToolCall("gemini_call_${callOffset + i}", fc["name"]?.string ?: "", fc["args"]?.toString() ?: "{}")
        }
        return ChatResult(text, calls, candidate["finishReason"]?.string, usage(body), body["modelVersion"]?.string)
    }

    private fun usage(body: JsonValue) = body["usageMetadata"]?.let {
        TokenUsage(it["promptTokenCount"]?.int, it["candidatesTokenCount"]?.int)
    }

    override suspend fun chat(request: ChatRequest): ChatResult {
        val model = request.model ?: config.model
        val response = try {
            transport.execute(HttpRequest("$base/${modelPath(model)}:generateContent", "POST", headers(), buildBody(request).toString(), 120_000))
        } catch (e: NetworkError) {
            throw ProviderException(e.message ?: "Network error", cause = e)
        }
        if (!response.isSuccess) throw httpError(response.code, response.body)
        return parseChunk(Json.parseOrNull(response.body) ?: throw ProviderException("Gemini returned invalid JSON."))
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val model = request.model ?: config.model
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var finish: String? = null
        var usage: TokenUsage? = null
        val req = HttpRequest("$base/${modelPath(model)}:streamGenerateContent?alt=sse", "POST", headers(), buildBody(request).toString(), 120_000)
        transport.streamLines(req).collect { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@collect
            val chunk = Json.parseOrNull(line.removePrefix("data:").trim()) ?: return@collect
            val r = parseChunk(chunk, calls.size)
            if (r.text.isNotEmpty()) { text.append(r.text); emit(StreamEvent.TextDelta(r.text)) }
            calls += r.toolCalls
            r.finishReason?.let { finish = it }
            r.usage?.let { usage = it }
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
            transport.execute(HttpRequest("$base/models?pageSize=200", "GET", headers() - "Content-Type", null, 30_000))
        } catch (e: NetworkError) {
            throw ProviderException(e.message ?: "Network error", cause = e)
        }
        if (!response.isSuccess) throw httpError(response.code, response.body)
        val parsed = Json.parseOrNull(response.body) ?: throw ProviderException("Model list was not valid JSON.")
        return parsed["models"]?.array.orEmpty().mapNotNull { m ->
            val methods = m["supportedGenerationMethods"]?.array?.mapNotNull { it.string } ?: emptyList()
            if ("generateContent" !in methods) return@mapNotNull null
            val name = m["name"]?.string ?: return@mapNotNull null
            ModelInfo(
                id = name.removePrefix("models/"),
                displayName = m["displayName"]?.string ?: name,
                contextWindow = m["inputTokenLimit"]?.int,
                // Gemini's model list does not report vision/tool support or pricing; leave unknown.
            )
        }
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val r = chat(ChatRequest(listOf(ChatMessage.user("Reply with the single word: ready")), maxOutputTokens = 32))
            ConnectionTestResult.Success(System.currentTimeMillis() - start, "Gemini ${config.model} replied: ${r.text.take(40)}")
        } catch (e: ProviderException) {
            ConnectionTestResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun httpError(code: Int, body: String): ProviderException {
        val msg = Json.parseOrNull(body)?.get("error")?.get("message")?.string ?: body.take(200)
        val hint = when (code) {
            400 -> "Gemini rejected the request."
            401, 403 -> "Authentication failed — check the Gemini API key."
            404 -> "Model not found — check the model name."
            429 -> "Rate limit or quota reached."
            else -> "Request failed."
        }
        return ProviderException("$hint (HTTP $code) ${Redaction.redact(msg, listOfNotNull(apiKey))}".trim(), code)
    }
}

package dev.ciel.ai

import dev.ciel.core.json.JsonObject
import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ImageInput(val mimeType: String, val base64Data: String)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ChatMessage(
    val role: Role,
    val content: String,
    val images: List<ImageInput> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    /** For [Role.TOOL] messages: the id of the call this result answers. */
    val toolCallId: String? = null,
    /** For [Role.TOOL] messages: the tool name (required by some providers, e.g. Gemini). */
    val toolName: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String, images: List<ImageInput> = emptyList()) = ChatMessage(Role.USER, text, images)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) = ChatMessage(Role.ASSISTANT, text, toolCalls = toolCalls)
        fun toolResult(callId: String, toolName: String, result: String) =
            ChatMessage(Role.TOOL, result, toolCallId = callId, toolName = toolName)
    }
}

/** A tool the model may call. [parameters] is a JSON-Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String? = null,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

data class TokenUsage(val inputTokens: Int?, val outputTokens: Int?)

data class ChatResult(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
    val model: String? = null,
)

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    /** Emitted once at the end, carrying the fully assembled result (text + tool calls). */
    data class Completed(val result: ChatResult) : StreamEvent()
}

/** Free-tier status is never guessed: UNKNOWN unless the provider's live data or the user says otherwise. */
enum class FreeTier { FREE, PAID, UNKNOWN }

data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val freeTier: FreeTier = FreeTier.UNKNOWN,
    /** Where the free-tier value came from, e.g. "OpenRouter pricing API". */
    val freeTierSource: String? = null,
)

sealed class ConnectionTestResult {
    data class Success(val latencyMillis: Long, val detail: String) : ConnectionTestResult()
    data class Failure(val reason: String) : ConnectionTestResult()
}

/** Thrown with a user-presentable message; never contains credentials. */
class ProviderException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
    val isRateLimit: Boolean get() = httpCode == 429
    val isAuthError: Boolean get() = httpCode == 401 || httpCode == 403
}

interface AiProvider {
    val config: ProviderConfig
    suspend fun chat(request: ChatRequest): ChatResult
    fun stream(request: ChatRequest): Flow<StreamEvent>
    suspend fun listModels(): List<ModelInfo>
    suspend fun testConnection(): ConnectionTestResult
}

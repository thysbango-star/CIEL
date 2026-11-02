package dev.ciel.engine

import dev.ciel.ai.ChatMessage
import dev.ciel.ai.ChatRequest
import dev.ciel.ai.ImageInput
import dev.ciel.ai.Role
import dev.ciel.ai.ToolCall
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.memory.MemoryManager
import dev.ciel.skills.SkillContext
import dev.ciel.skills.SkillRegistry
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.GuardedDevice
import dev.ciel.task.TaskManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Visual/interaction states shown by the interface (Part 3). */
enum class CielState { IDLE, LISTENING, PROCESSING, ANALYSING, RESEARCHING, SPEAKING, EXECUTING, COMPLETED, WARNING, ERROR }

data class EngineSettings(
    val persona: PersonaSettings = PersonaSettings(),
    val autoSelect: Boolean = true,
    val preferredProviderId: String? = null,
    /** Per-conversation manual override; null = automatic. */
    val overrideProviderId: String? = null,
    val maxToolRounds: Int = 6,
    val streaming: Boolean = true,
    /** If false, memories are never sent to cloud providers. */
    val shareMemoryWithProviders: Boolean = true,
    val historyMessages: Int = 24,
)

sealed class EngineEvent {
    data class State(val state: CielState, val label: String? = null) : EngineEvent()
    data class ProviderChosen(val name: String, val model: String, val reason: String) : EngineEvent()
    data class TextDelta(val text: String) : EngineEvent()
    /** Discard streamed text so far (a provider failed mid-stream and another is retrying). */
    object ResetText : EngineEvent()
    data class ToolStarted(val tool: String, val skill: String) : EngineEvent()
    data class ToolFinished(val tool: String, val success: Boolean, val verified: Boolean, val summary: String) : EngineEvent()
    data class Final(val text: String, val offline: Boolean = false) : EngineEvent()
    data class Failure(val message: String) : EngineEvent()
}

/**
 * Ciel's central intelligence: understands a request, chooses a model, offers it the enabled
 * skills as tools, runs the tool loop, and reports only what tool results actually confirm.
 */
class CielEngine(
    private val providers: ProviderSource,
    private val gateway: LlmGateway,
    private val selector: ProviderSelector,
    private val registry: SkillRegistry,
    private val memory: MemoryManager?,
    private val tasks: TaskManager,
    private val device: () -> GuardedDevice?,
    private val confirm: ConfirmationGate,
    private val isOnline: () -> Boolean,
    private val settings: () -> EngineSettings,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    fun respond(
        history: List<ChatMessage>,
        userText: String,
        images: List<ImageInput> = emptyList(),
        mode: ReasoningMode = ReasoningMode.CONVERSE,
    ): Flow<EngineEvent> = channelFlow {
        val job = launch {
            try { run(history, userText, images, mode) { send(it) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { send(EngineEvent.State(CielState.ERROR)); send(EngineEvent.Failure(e.message ?: e.toString())) }
        }
        tasks.emergencyStop.register("engine-" + System.nanoTime(), job)
        job.join()
    }

    private suspend fun run(history: List<ChatMessage>, userText: String, images: List<ImageInput>, mode: ReasoningMode, emit: suspend (EngineEvent) -> Unit) {
        val s = settings()
        val generation = tasks.emergencyStop.currentGeneration()

        // 1. The stop command never goes through a model.
        if (LocalIntentParser.isStopCommand(userText)) {
            tasks.emergencyStop.trigger("voice/text command")
            emit(EngineEvent.State(CielState.WARNING, "Stopped"))
            emit(EngineEvent.Final(marker(s, "Affirmative.") + "All operations have been halted. Control is yours, ${s.persona.formOfAddress}."))
            return
        }

        val online = isOnline()
        val configured = providers.configs().filter { it.enabled }
        val ctx = SkillContext(device(), tasks, confirm, isOnline, "chat", generation)

        // 2. Offline or no provider: deterministic local intents only.
        if (!online || configured.isEmpty()) {
            emit(EngineEvent.State(CielState.PROCESSING))
            val intent = LocalIntentParser.parse(userText)
            if (intent is LocalIntent.Tool && registry.skillForTool(intent.tool) != null) {
                emit(EngineEvent.ToolStarted(intent.tool, registry.skillForTool(intent.tool)?.displayName ?: ""))
                val out = registry.dispatch(intent.tool, intent.args, ctx)
                emit(EngineEvent.ToolFinished(intent.tool, out.success, out.verified, out.content.take(120)))
                emit(EngineEvent.State(if (out.success) CielState.COMPLETED else CielState.WARNING))
                emit(EngineEvent.Final(if (out.success) (intent.spoken.ifBlank { marker(s, "Answer.") + out.content }) else marker(s, "Notice.") + out.content, offline = true))
            } else {
                emit(EngineEvent.State(CielState.WARNING))
                val why = if (!online) "I am offline" else "no AI provider is configured yet"
                emit(EngineEvent.Final(marker(s, "Notice.") + "$why, so I can only handle basic commands: open apps, calculations, reminders, memory and \"stop\"." +
                    if (configured.isEmpty()) " Add a provider in Settings → AI Providers." else "", offline = true))
            }
            return
        }

        // 3. Choose a model.
        val historyChars = history.sumOf { it.content.length }
        val profile = ComplexityClassifier.classify(userText, images.isNotEmpty(), historyChars, mode)
        val selection = selector.select(configured, profile, s.preferredProviderId, s.overrideProviderId, s.autoSelect)
        if (selection.ordered.isEmpty()) {
            emit(EngineEvent.State(CielState.WARNING)); emit(EngineEvent.Final(marker(s, "Notice.") + selection.reason)); return
        }
        emit(EngineEvent.State(if (mode == ReasoningMode.ANALYSE || profile.complexity == Complexity.COMPLEX) CielState.ANALYSING else CielState.PROCESSING))

        // 4. Build the conversation.
        val tools = registry.availableTools(online)
        val memoryContext = if (s.shareMemoryWithProviders) memory?.contextFor(userText) else null
        val active = tasks.activeTasks().joinToString("\n") { "- ${it.id.take(8)}: ${it.objective} (${it.status}, ${it.currentStep ?: ""})" }
        val system = CielPersona.systemPrompt(s.persona, mode, online, tools.map { it.name },
            now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm z")), memoryContext, active)
        val messages = mutableListOf(ChatMessage.system(system))
        messages += history.filter { it.role == Role.USER || it.role == Role.ASSISTANT }.takeLast(s.historyMessages).map { it.copy(images = emptyList()) }
        messages += ChatMessage.user(userText, images)

        // 5. Tool loop.
        var ordered = selection.ordered
        for (round in 0..s.maxToolRounds) {
            tasks.emergencyStop.checkNotStopped(generation)
            val offerTools = round < s.maxToolRounds // last round: force a textual answer
            val request = ChatRequest(messages.toList(), tools = if (offerTools) tools else emptyList())
            var streamedText = false
            val (used, result) = gateway.call(ordered, request, stream = s.streaming,
                onProvider = { cfg -> if (round == 0) emit(EngineEvent.ProviderChosen(cfg.displayName, cfg.model, selection.reason)) },
                onDelta = { d -> streamedText = true; emit(EngineEvent.TextDelta(d)) },
                onReset = { emit(EngineEvent.ResetText) })
            // Stick with the provider that answered for the rest of this turn.
            ordered = listOf(used) + ordered.filter { it.id != used.id }

            val calls = result.toolCalls.filter { it.name.isNotBlank() }
            if (calls.isEmpty()) {
                emit(EngineEvent.State(CielState.COMPLETED))
                emit(EngineEvent.Final(result.text.trim().ifEmpty { "…" }))
                return
            }
            if (streamedText) emit(EngineEvent.ResetText) // interim text before tool use is not the final answer
            messages += ChatMessage.assistant(result.text, calls)
            for (call in calls) {
                tasks.emergencyStop.checkNotStopped(generation)
                messages += executeTool(call, ctx, emit)
            }
        }
        emit(EngineEvent.State(CielState.WARNING))
        emit(EngineEvent.Final(marker(s, "Notice.") + "I reached my tool-use limit for one request before finishing. Ask me to continue if needed."))
    }

    private suspend fun executeTool(call: ToolCall, ctx: SkillContext, emit: suspend (EngineEvent) -> Unit): ChatMessage {
        val skill = registry.skillForTool(call.name)
        emit(EngineEvent.State(when (skill?.id) {
            "research" -> CielState.RESEARCHING
            "execute", "game_master", "system", "communication" -> CielState.EXECUTING
            else -> CielState.PROCESSING
        }, skill?.displayName))
        emit(EngineEvent.ToolStarted(call.name, skill?.displayName ?: call.name))
        val args = (Json.parseOrNull(call.argumentsJson.ifBlank { "{}" }) as? JsonObject) ?: JsonObject(emptyMap())
        val outcome = registry.dispatch(call.name, args, ctx)
        emit(EngineEvent.ToolFinished(call.name, outcome.success, outcome.verified, outcome.content.take(160)))
        val body = buildString {
            append(if (outcome.success) "SUCCESS" else "FAILED")
            if (outcome.success) append(if (outcome.verified) " (verified)" else " (not independently verified)")
            append(": ")
            append(if (outcome.untrusted) PromptGuard.wrap(outcome.sourceLabel ?: call.name, outcome.content) else outcome.content)
        }
        return ChatMessage.toolResult(call.id, call.name, body.take(16_000))
    }

    private fun marker(s: EngineSettings, m: String) = if (s.persona.useSystemMarkers) "$m " else ""
}

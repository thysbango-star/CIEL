package dev.ciel.task

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class StoppedException(val reason: String) : kotlinx.coroutines.CancellationException("Emergency stop: $reason")

/**
 * The single, global kill switch. It takes priority over everything:
 *  - every registered job (tasks, game automation, agent loops) is cancelled,
 *  - every device action checks [checkNotStopped] immediately before touching the screen,
 *  - listeners (accessibility service, overlay, TTS) release control of the device.
 *
 * Stopping increments a generation counter. Work that started in an older generation can
 * never perform another action, even if a coroutine fails to observe cancellation promptly.
 */
class EmergencyStop {
    data class State(val generation: Long, val lastReason: String?, val lastStopAt: Long?)

    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow(State(0, null, null))
    val state: StateFlow<State> = _state.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun currentGeneration(): Long = generation.get()

    /** Register work so it can be cancelled. Returns a token describing the generation it belongs to. */
    fun register(id: String, job: Job): Long {
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
        return generation.get()
    }

    fun addListener(listener: (reason: String) -> Unit) { listeners += listener }
    fun removeListener(listener: (reason: String) -> Unit) { listeners -= listener }

    /** Trigger the stop. Safe to call from any thread, any number of times. */
    fun trigger(reason: String) {
        val gen = generation.incrementAndGet()
        _state.value = State(gen, reason, System.currentTimeMillis())
        val snapshot = jobs.values.toList()
        snapshot.forEach { it.cancel(StoppedException(reason)) }
        listeners.forEach { l -> runCatching { l(reason) } }
    }

    /** Throws if a stop happened after [startedInGeneration]. Called before every device action. */
    fun checkNotStopped(startedInGeneration: Long) {
        if (generation.get() != startedInGeneration) throw StoppedException(_state.value.lastReason ?: "stopped")
    }

    fun activeJobCount(): Int = jobs.count { it.value.isActive }
}

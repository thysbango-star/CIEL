package dev.ciel.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class TaskStatus { PLANNING, RUNNING, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED, STOPPED }

data class TaskStep(val description: String, val done: Boolean = false)

data class TaskState(
    val id: String,
    val objective: String,
    val kind: String,
    val status: TaskStatus = TaskStatus.PLANNING,
    val plan: List<TaskStep> = emptyList(),
    val currentStep: String? = null,
    val currentApp: String? = null,
    /** 0..1, or null when progress cannot be measured honestly. */
    val progress: Float? = null,
    val completionCondition: String? = null,
    val errors: List<String> = emptyList(),
    val report: String? = null,
    /** True only when completion was checked against the device or a tool result. */
    val verified: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val isActive: Boolean get() = status == TaskStatus.PLANNING || status == TaskStatus.RUNNING || status == TaskStatus.WAITING_FOR_USER
}

/** Handle given to running work so it can report progress. */
class TaskHandle internal constructor(
    val id: String,
    val generation: Long,
    private val manager: TaskManager,
) {
    fun update(transform: (TaskState) -> TaskState) = manager.mutate(id, transform)
    fun step(description: String, app: String? = null) = update { it.copy(status = TaskStatus.RUNNING, currentStep = description, currentApp = app ?: it.currentApp) }
    fun plan(steps: List<String>, completionCondition: String?) =
        update { it.copy(plan = steps.map { s -> TaskStep(s) }, completionCondition = completionCondition) }
    fun markStepDone(index: Int) = update { s ->
        val plan = s.plan.mapIndexed { i, st -> if (i == index) st.copy(done = true) else st }
        s.copy(plan = plan, progress = if (plan.isEmpty()) s.progress else plan.count { it.done }.toFloat() / plan.size)
    }
    fun error(message: String) = update { it.copy(errors = it.errors + message) }
}

data class TaskResult(val success: Boolean, val report: String, val verified: Boolean)

/** Persists finished tasks for the task-history screen. */
interface TaskHistoryStore {
    suspend fun save(task: TaskState)
    suspend fun recent(limit: Int): List<TaskState>
}

/**
 * Runs tasks concurrently, tracks their state for the monitor UI, and wires every task to
 * the emergency stop. Tasks that need the screen must acquire [ResourceLocks.Resource.SCREEN].
 */
class TaskManager(
    private val scope: CoroutineScope,
    val emergencyStop: EmergencyStop,
    private val history: TaskHistoryStore? = null,
) {
    private val _tasks = MutableStateFlow<List<TaskState>>(emptyList())
    val tasks: StateFlow<List<TaskState>> = _tasks.asStateFlow()
    private val jobs = HashMap<String, Job>()

    internal fun mutate(id: String, transform: (TaskState) -> TaskState) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun get(id: String): TaskState? = _tasks.value.firstOrNull { it.id == id }

    fun start(objective: String, kind: String, body: suspend (TaskHandle) -> TaskResult): String {
        val id = UUID.randomUUID().toString()
        _tasks.update { (it + TaskState(id, objective, kind)).takeLast(50) }
        val generation = emergencyStop.currentGeneration()
        val handle = TaskHandle(id, generation, this)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                emergencyStop.checkNotStopped(generation)
                val result = body(handle)
                mutate(id) {
                    it.copy(
                        status = if (result.success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                        report = result.report, verified = result.verified,
                        progress = if (result.success) 1f else it.progress,
                        finishedAt = System.currentTimeMillis(), currentStep = null,
                    )
                }
            } catch (e: StoppedException) {
                mutate(id) { it.copy(status = TaskStatus.STOPPED, report = "Stopped by emergency stop (${e.reason}).", finishedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                mutate(id) { it.copy(status = TaskStatus.CANCELLED, report = "Cancelled.", finishedAt = System.currentTimeMillis()) }
            } catch (e: Exception) {
                mutate(id) { it.copy(status = TaskStatus.FAILED, errors = it.errors + (e.message ?: e.toString()), report = "Failed: ${e.message}", finishedAt = System.currentTimeMillis()) }
            } finally {
                synchronized(jobs) { jobs.remove(id) }
                get(id)?.let { finished -> history?.let { h -> withContext(NonCancellable) { runCatching { h.save(finished) } } } }
            }
        }
        synchronized(jobs) { jobs[id] = job }
        emergencyStop.register(id, job)
        job.start()
        return id
    }

    fun cancel(id: String) { synchronized(jobs) { jobs[id] }?.cancel() }

    fun activeTasks(): List<TaskState> = _tasks.value.filter { it.isActive }
}

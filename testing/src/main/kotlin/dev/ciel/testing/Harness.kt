package dev.ciel.testing

import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpResponse
import dev.ciel.core.http.HttpTransport
import dev.ciel.task.ActionOutcome
import dev.ciel.task.DeviceAction
import dev.ciel.task.DeviceController
import dev.ciel.task.ScreenSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking

/**
 * Minimal test harness so the pure-Kotlin modules can be tested with only the Kotlin
 * compiler (no JUnit download needed). Under Gradle they run with `./gradlew :testing:run`.
 */
object T {
    data class Case(val suite: String, val name: String, val body: suspend () -> Unit)
    val cases = mutableListOf<Case>()
    private var currentSuite = ""

    fun suite(name: String, block: () -> Unit) { currentSuite = name; block() }
    fun test(name: String, body: suspend () -> Unit) { cases += Case(currentSuite, name, body) }

    fun runAll(): Int {
        var failed = 0
        for (c in cases) {
            val start = System.nanoTime()
            try {
                runBlocking { c.body() }
                println("  PASS  ${c.suite} › ${c.name} (${(System.nanoTime() - start) / 1_000_000}ms)")
            } catch (e: Throwable) {
                failed++
                println("  FAIL  ${c.suite} › ${c.name}: ${e::class.simpleName}: ${e.message}")
                e.stackTrace.take(4).forEach { println("          at $it") }
            }
        }
        println("\n${cases.size - failed}/${cases.size} tests passed")
        return failed
    }
}

fun check(condition: Boolean, message: () -> String = { "check failed" }) { if (!condition) throw AssertionError(message()) }
fun <T> eq(expected: T, actual: T, what: String = "") { if (expected != actual) throw AssertionError("$what expected <$expected> but was <$actual>") }
inline fun <reified E : Throwable> throws(block: () -> Unit): E {
    try { block() } catch (e: Throwable) { if (e is E) return e; throw AssertionError("Expected ${E::class.simpleName} but got ${e::class.simpleName}: ${e.message}") }
    throw AssertionError("Expected ${E::class.simpleName} but nothing was thrown")
}

/** Scriptable fake HTTP transport. */
class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var streamHandler: ((HttpRequest) -> List<String>)? = null
    override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return handler(request) }
    override fun streamLines(request: HttpRequest): Flow<String> { requests += request; return (streamHandler ?: { listOf<String>() })(request).asFlow() }
}

/** A fake phone: a sequence of screens; actions move between them according to [transitions]. */
class FakeDevice(
    var screen: ScreenSnapshot,
    val apps: Map<String, String> = mapOf("Settings" to "com.android.settings"),
    val transitions: (DeviceAction, ScreenSnapshot) -> ScreenSnapshot? = { _, _ -> null },
) : DeviceController {
    val performed = mutableListOf<DeviceAction>()
    override val isAvailable = true
    override suspend fun observe(includeScreenshot: Boolean) = screen
    override suspend fun perform(action: DeviceAction): ActionOutcome {
        performed += action
        transitions(action, screen)?.let { screen = it }
        return ActionOutcome.Done
    }
    override suspend fun installedApps() = apps
}

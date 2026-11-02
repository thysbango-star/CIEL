package dev.ciel.game

import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json
import java.util.UUID

enum class TaskCategory(val title: String) {
    EVENTS("Events"),
    SEASONAL("Seasonal challenges"),
    DAILY("Daily quests"),
    WEEKLY("Weekly quests"),
    STORY("Story progression"),
    FARMING("Resource farming"),
    KINGDOM("Kingdom resource collection"),
    PRODUCTION("Production management"),
    COOKIES("Cookie development"),
    OTHER("Other activities"),
}

/** How long a task keeps going. Every task has one; nothing repeats without a limit. */
enum class StopKind(val label: String, val needsNumber: Boolean = false) {
    ONCE("Once"),
    RUNS("Number of runs", needsNumber = true),
    UNTIL_STAMINA_OUT("Until stamina runs out"),
    UNTIL_DAILY_LIMIT("Until daily attempts are used"),
    UNTIL_DONE("Until everything available is done"),
}

/** The task library: what the dashboard offers. Availability is decided from what Ciel has discovered. */
enum class TaskType(
    val category: TaskCategory,
    val title: String,
    val description: String,
    /** Systems (knowledge) that show the activity exists in this game version. */
    val systems: List<String>,
    val stops: List<StopKind>,
    val repeating: Boolean = false,
) {
    EVENT_SCAN(TaskCategory.EVENTS, "Check active events", "Read every active event: time left, objectives, daily attempts, claimable rewards.", listOf("Events"), listOf(StopKind.ONCE)),
    EVENT_COMPLETE(TaskCategory.EVENTS, "Complete event objectives", "Do the event missions that can be done now, most urgent first, and claim free event rewards.", listOf("Events"), listOf(StopKind.UNTIL_DONE, StopKind.UNTIL_DAILY_LIMIT)),
    SEASONAL_CHALLENGES(TaskCategory.SEASONAL, "Complete seasonal challenges", "Seasonal / limited-time challenges, ending-soon first.", listOf("Events"), listOf(StopKind.UNTIL_DONE)),
    DAILY_QUESTS(TaskCategory.DAILY, "Complete all available daily quests", "Do what can be done automatically; claim the rewards at the end so other tasks count toward them.", listOf("Quests"), listOf(StopKind.UNTIL_DONE)),
    WEEKLY_QUESTS(TaskCategory.WEEKLY, "Complete all available weekly quests", "Like daily quests, for weekly objectives.", listOf("Quests"), listOf(StopKind.UNTIL_DONE)),
    STORY_CONTINUE(TaskCategory.STORY, "Continue the story", "Next available story stage, then repeat while the stop rule allows. Stops on a defeat (team too weak).", listOf("Battles", "World exploration"),
        listOf(StopKind.ONCE, StopKind.RUNS, StopKind.UNTIL_STAMINA_OUT), repeating = true),
    FARM(TaskCategory.FARMING, "Farm a resource", "Repeat a stage you choose for a resource. Results are recorded as observed, not assumed.", listOf("Battles", "World exploration"),
        listOf(StopKind.RUNS, StopKind.UNTIL_STAMINA_OUT, StopKind.UNTIL_DAILY_LIMIT), repeating = true),
    KINGDOM_COLLECT(TaskCategory.KINGDOM, "Collect all available kingdom resources", "Finished production and building rewards around the kingdom.", listOf("Production", "Main"), listOf(StopKind.ONCE)),
    PRODUCTION_MANAGE(TaskCategory.PRODUCTION, "Manage production automatically", "Collect, then fill free production slots with useful goods within your reserves.", listOf("Production"), listOf(StopKind.ONCE)),
    COOKIE_REVIEW(TaskCategory.COOKIES, "Review cookie upgrades", "Find available level/skill upgrades and recommend them; does routine ones only if your spending rules allow.", listOf("Characters", "Cookies"), listOf(StopKind.ONCE)),
    DISCOVER(TaskCategory.OTHER, "Discover other activities", "Explore menus to find towers, guild activities, research and more (read-only).", listOf("Main"), listOf(StopKind.ONCE)),
}

data class QueueTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: TaskType,
    val stop: StopKind = type.stops.first(),
    val number: Int = 3,
    /** For farming: which resource ("skill powder"). */
    val resource: String? = null,
    val paused: Boolean = false,
) {
    val label: String get() = type.title + (resource?.takeIf { type == TaskType.FARM }?.let { ": $it" } ?: "")
    val maxRuns: Int get() = when (stop) {
        StopKind.ONCE, StopKind.UNTIL_DONE -> 1
        StopKind.RUNS -> number.coerceIn(1, 50)
        StopKind.UNTIL_STAMINA_OUT, StopKind.UNTIL_DAILY_LIMIT -> 50 // hard cap: never endless
    }
}

data class GameQueue(
    val tasks: List<QueueTask> = emptyList(),
    /** Follow the user's order strictly; Ciel only recommends changes. */
    val lockedOrder: Boolean = false,
    /** Time-limited events first (default on). */
    val prioritiseEvents: Boolean = true,
    /** Use limited daily event attempts before they reset (default on). */
    val protectDailyAttempts: Boolean = true,
)

data class QueuePreset(val name: String, val queue: GameQueue)

/** An active event as read from the game's screens. Deadlines are only what the game displayed. */
data class EventInfo(
    val name: String,
    /** Absolute end time computed from the displayed remaining time; null if none was shown. */
    val endsAt: Long?,
    val shownText: String?,
    val observedAt: Long = System.currentTimeMillis(),
    val dailyAttempts: String? = null,
    val claimable: Boolean = false,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()) = endsAt?.let { it - now }
    fun urgency(now: Long = System.currentTimeMillis()): Urgency {
        val r = remainingMs(now) ?: return Urgency.UNKNOWN
        return when { r <= 0 -> Urgency.ENDED; r < 24 * HOUR -> Urgency.HIGH; r < 72 * HOUR -> Urgency.MEDIUM; else -> Urgency.LOW }
    }
    companion object { const val HOUR = 3_600_000L }
}

enum class Urgency(val label: String) { HIGH("Ending soon"), MEDIUM("Ends within 3 days"), LOW("Plenty of time"), UNKNOWN("Time left not shown"), ENDED("Ended") }

/** Reads remaining-time text as games show it: "2d 5h", "05:12:33", "Ends in 3 days", "12h 30m left". */
object EventDeadlines {
    fun parseRemaining(text: String): Long? {
        val t = text.lowercase()
        Regex("(\\d{1,3}):(\\d{2}):(\\d{2})").find(t)?.let { m ->
            val (h, mi, s) = m.destructured; return (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000
        }
        var total = 0L; var any = false
        for (m in Regex("(\\d+)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m)\\b").findAll(t)) {
            val n = m.groupValues[1].toLong(); any = true
            total += n * when (m.groupValues[2].first()) { 'd' -> 86_400_000L; 'h' -> 3_600_000L; else -> 60_000L }
        }
        return if (any) total else null
    }
}

data class OrderedQueue(val tasks: List<QueueTask>, val reasons: List<String>, val recommendations: List<String>)

/**
 * Orders the queue. The user's order is the starting point. Unless the order is locked:
 *  1. Event tasks move first when events are prioritised (ending-soon events always first).
 *  2. Kingdom collection runs before production management (collect before using resources).
 *  3. Quest claiming happens after story/farming, so those runs count toward the quests.
 * When locked, the same findings are returned as recommendations instead.
 */
object QueuePlanner {
    private val eventTypes = setOf(TaskType.EVENT_SCAN, TaskType.EVENT_COMPLETE, TaskType.SEASONAL_CHALLENGES)

    fun order(q: GameQueue, events: List<EventInfo>, now: Long = System.currentTimeMillis()): OrderedQueue {
        val tasks = q.tasks.filter { !it.paused }
        var list = tasks
        val notes = mutableListOf<String>()
        val urgent = events.filter { it.urgency(now) == Urgency.HIGH }

        if (q.prioritiseEvents && list.any { it.type in eventTypes }) {
            val ev = list.filter { it.type in eventTypes }.sortedBy { if (it.type == TaskType.EVENT_SCAN) 0 else 1 }
            val moved = ev + list.filter { it.type !in eventTypes }
            if (moved != list) notes += if (urgent.isNotEmpty()) "Events first: ${urgent.joinToString { it.name }} ends in under 24 hours."
                else "Events first: limited-time rewards are protected before unlimited activities."
            list = moved
        }
        val collect = list.indexOfFirst { it.type == TaskType.KINGDOM_COLLECT }
        val produce = list.indexOfFirst { it.type == TaskType.PRODUCTION_MANAGE }
        if (collect > produce && produce >= 0) {
            list = list.toMutableList().apply { val c = removeAt(collect); add(produce, c) }
            notes += "Kingdom collection moved before production management: finished goods are collected before new production uses resources."
        }
        val quests = list.filter { it.type == TaskType.DAILY_QUESTS || it.type == TaskType.WEEKLY_QUESTS }
        val lastWork = list.indexOfLast { it.type == TaskType.STORY_CONTINUE || it.type == TaskType.FARM }
        if (quests.isNotEmpty() && lastWork >= 0 && list.indexOf(quests.first()) < lastWork) {
            list = list.filter { it !in quests } + quests
            notes += "Quests moved after story/farming: those runs count toward quest objectives, so nothing is done twice."
        }
        return if (q.lockedOrder) OrderedQueue(tasks, emptyList(), notes.map { "Suggestion (order is locked): $it" })
        else OrderedQueue(list, notes, emptyList())
    }

    /** The plan for one queue task. Repeating tasks get one step per run (the runner loops). */
    fun steps(t: QueueTask, kb: GameKnowledgeBase): List<GamePlanStep> {
        val proof = "Finish with \"done\" only when the current screen proves it; describe what you saw."
        val ro = "Only open menus, read and go back. Do NOT confirm, collect, upgrade, start, buy or spend anything."
        return when (t.type) {
            TaskType.EVENT_SCAN -> listOf(GamePlanStep("Check active events", "Events", StepKind.INSPECT,
                "Open the events menu. For each active event read: its name, the time remaining exactly as shown, incomplete objectives, daily attempts left, and claimable rewards. $ro $proof Summary: one line per event: name | time left as shown | objectives left | attempts | claimable.", readOnly = true, weight = 3))
            TaskType.EVENT_COMPLETE, TaskType.SEASONAL_CHALLENGES -> listOf(
                GamePlanStep("Claim free event rewards", "Events", StepKind.CLAIM, "In the events menu, claim every free reward that is ready. Never spend premium currency. $proof", weight = 2),
                GamePlanStep(t.type.title, "Events", StepKind.QUEST, "Work on the event objectives that can be done now with ordinary actions, the event ending soonest first. Use available daily attempts. Skip anything needing purchases. $proof", weight = 5))
            TaskType.DAILY_QUESTS, TaskType.WEEKLY_QUESTS -> {
                val which = if (t.type == TaskType.DAILY_QUESTS) "daily" else "weekly"
                listOf(GamePlanStep("Do $which quest objectives", "Quests", StepKind.QUEST, "Open the quests menu, $which tab. Do the incomplete objectives that can be done quickly with ordinary actions; skip ones needing purchases or battles you would likely lose. $proof", weight = 4),
                    GamePlanStep("Claim $which quest rewards", "Quests", StepKind.CLAIM, "In the quests menu, $which tab, claim every completed reward. $proof", weight = 2))
            }
            TaskType.STORY_CONTINUE -> listOf(GamePlanStep("Story stage", "Battles", StepKind.QUEST,
                "Go to the story / world exploration map, select the next uncleared stage, start it and let the battle finish, then collect the result. If stamina is insufficient, or the stage is lost, fail and say so. Never refill stamina with premium currency. $proof", weight = 4))
            TaskType.FARM -> listOf(GamePlanStep("Farm ${t.resource ?: "resources"}", "Battles", StepKind.QUEST,
                "Run one farming stage for ${t.resource ?: "the selected resource"} (a stage you have cleared that drops it; prefer a learned one), let it finish, collect the result and note what was obtained. If stamina or attempts are insufficient, fail and say so. Never buy refills. $proof", weight = 4))
            TaskType.KINGDOM_COLLECT -> listOf(GamePlanStep("Collect kingdom resources", "Production", StepKind.COLLECT, "Collect finished goods and building rewards around the kingdom (ready indicators). Verify each collection. Do not start new production. $proof", weight = 3))
            TaskType.PRODUCTION_MANAGE -> listOf(GamePlanStep("Manage production", "Production", StepKind.PRODUCE, "In production buildings with free slots, start goods that are needed, keeping the resource reserve. Verify a timer appears. $proof", weight = 3))
            TaskType.COOKIE_REVIEW -> listOf(GamePlanStep("Review cookie upgrades", "Characters", StepKind.INSPECT, "Open the cookie list. Find cookies with level or skill upgrades available and what they cost. $ro $proof Summary: recommended upgrades with costs.", readOnly = true, weight = 3))
            TaskType.DISCOVER -> ObjectivePlanner.plan("explore and learn", kb).steps
        }
    }

    /** Whether the game shows an activity, based on what Ciel has learned so far. */
    fun availability(type: TaskType, kb: GameKnowledgeBase): String {
        val known = kb.entries.filter { e -> type.systems.any { it.equals(e.system, true) } && e.kind != KnowledgeKind.UNRESOLVED }
        return when {
            known.any { it.confidence != Confidence.LOW && !it.needsRevalidation } -> "Found in your game"
            known.isNotEmpty() -> "Seen before — will re-check"
            else -> "Not seen yet — Ciel will look for it"
        }
    }

    fun encode(q: GameQueue) = json {
        "locked" to q.lockedOrder; "events" to q.prioritiseEvents; "daily" to q.protectDailyAttempts
        "tasks" to q.tasks.map { t -> json { "id" to t.id; "type" to t.type.name; "stop" to t.stop.name; "n" to t.number; opt("res", t.resource); "paused" to t.paused } }
    }
    fun decode(o: JsonValue?): GameQueue {
        if (o == null) return GameQueue()
        return GameQueue(
            tasks = o["tasks"]?.array?.mapNotNull { t ->
                val type = runCatching { TaskType.valueOf(t["type"]?.string ?: "") }.getOrNull() ?: return@mapNotNull null
                QueueTask(t["id"]?.string ?: UUID.randomUUID().toString().take(8), type,
                    runCatching { StopKind.valueOf(t["stop"]?.string ?: "") }.getOrDefault(type.stops.first()), t["n"]?.int ?: 3, t["res"]?.string, t["paused"]?.bool ?: false)
            } ?: emptyList(),
            lockedOrder = o["locked"]?.bool ?: false, prioritiseEvents = o["events"]?.bool ?: true, protectDailyAttempts = o["daily"]?.bool ?: true,
        )
    }
    fun encodeEvent(e: EventInfo) = json { "name" to e.name; opt("endsAt", e.endsAt); opt("shown", e.shownText); "at" to e.observedAt; opt("daily", e.dailyAttempts); "claim" to e.claimable }
    fun decodeEvent(o: JsonValue) = EventInfo(o["name"]?.string ?: "Event", o["endsAt"]?.long, o["shown"]?.string, o["at"]?.long ?: 0, o["daily"]?.string, o["claim"]?.bool ?: false)

    val DEFAULT_PRESETS = listOf(
        QueuePreset("Daily routine", GameQueue(listOf(QueueTask(type = TaskType.KINGDOM_COLLECT), QueueTask(type = TaskType.DAILY_QUESTS), QueueTask(type = TaskType.EVENT_COMPLETE), QueueTask(type = TaskType.PRODUCTION_MANAGE)))),
        QueuePreset("Story progression", GameQueue(listOf(QueueTask(type = TaskType.KINGDOM_COLLECT), QueueTask(type = TaskType.STORY_CONTINUE, stop = StopKind.UNTIL_STAMINA_OUT)))),
        QueuePreset("Farming session", GameQueue(listOf(QueueTask(type = TaskType.KINGDOM_COLLECT), QueueTask(type = TaskType.FARM, stop = StopKind.RUNS, number = 5, resource = "skill powder")))),
        QueuePreset("Event completion", GameQueue(listOf(QueueTask(type = TaskType.EVENT_SCAN), QueueTask(type = TaskType.EVENT_COMPLETE), QueueTask(type = TaskType.SEASONAL_CHALLENGES)))),
    )
}

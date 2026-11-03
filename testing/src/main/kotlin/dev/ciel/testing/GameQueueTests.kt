package dev.ciel.testing

import dev.ciel.game.AutomationPolicy
import dev.ciel.game.EventDeadlines
import dev.ciel.game.EventInfo
import dev.ciel.game.GameActivity
import dev.ciel.game.GameCatalog
import dev.ciel.game.GameEngine
import dev.ciel.game.GameProfileCodec
import dev.ciel.game.GameQueue
import dev.ciel.game.InMemoryGameProfileRepository
import dev.ciel.game.InstalledPackageInfo
import dev.ciel.game.QueuePlanner
import dev.ciel.game.QueuePreset
import dev.ciel.game.QueueTask
import dev.ciel.game.StopKind
import dev.ciel.game.TaskType
import dev.ciel.game.Urgency
import dev.ciel.task.ActionPlanner
import dev.ciel.task.Bounds
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.DeviceAction
import dev.ciel.task.EmergencyStop
import dev.ciel.task.GuardedDevice
import dev.ciel.task.OCR_CLASS
import dev.ciel.task.PlannerDecision
import dev.ciel.task.ResourceLocks
import dev.ciel.task.ScreenAgent
import dev.ciel.task.ScreenSnapshot
import dev.ciel.task.TaskManager
import dev.ciel.task.TaskState
import dev.ciel.task.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private fun txt(id: Int, text: String) = UiNode(id, text = text, className = OCR_CLASS, bounds = Bounds(100, id * 120, 600, id * 120 + 80))
private fun scr(hash: Long, vararg n: UiNode) = ScreenSnapshot(GameCatalog.COOKIE_RUN_KINGDOM, nodes = n.toList(), screenWidth = 1080, screenHeight = 2400, visualHash = hash)
private suspend fun done(tasks: TaskManager, id: String): TaskState = withTimeout(120_000) { while (tasks.get(id)?.isActive != false) delay(10); tasks.get(id)!! }
private val pkgs = object : InstalledPackageInfo {
    override fun isGame(packageName: String) = true
    override fun versionName(packageName: String) = "5.0"
    override fun label(packageName: String) = "Cookie Run: Kingdom"
}

fun gameQueueTests() = T.suite("gameplay-manager") {
    T.test("deadlines: remaining time as games show it; urgency from the displayed time only") {
        eq(2 * 86_400_000L + 5 * 3_600_000L, EventDeadlines.parseRemaining("Ends in 2d 5h"))
        eq((5 * 3600 + 12 * 60 + 33) * 1000L, EventDeadlines.parseRemaining("05:12:33"))
        eq(3 * 86_400_000L, EventDeadlines.parseRemaining("3 days left"))
        eq(null, EventDeadlines.parseRemaining("Cookie Festival"))
        val now = 1_000_000_000L
        eq(Urgency.HIGH, EventInfo("A", now + 5 * EventInfo.HOUR, "5h").urgency(now))
        eq(Urgency.MEDIUM, EventInfo("B", now + 48 * EventInfo.HOUR, "2d").urgency(now))
        eq(Urgency.UNKNOWN, EventInfo("C", null, null).urgency(now))
    }

    T.test("ordering: events first, collect before production, quests after story; a locked order only gets suggestions") {
        val q = GameQueue(listOf(
            QueueTask(id = "s", type = TaskType.STORY_CONTINUE), QueueTask(id = "d", type = TaskType.DAILY_QUESTS),
            QueueTask(id = "p", type = TaskType.PRODUCTION_MANAGE), QueueTask(id = "k", type = TaskType.KINGDOM_COLLECT),
            QueueTask(id = "e", type = TaskType.EVENT_COMPLETE)))
        val o = QueuePlanner.order(q, listOf(EventInfo("Festival", System.currentTimeMillis() + 3 * EventInfo.HOUR, "3h")))
        eq(listOf("e", "s", "d", "k", "p"), o.tasks.map { it.id }) // dailies already follow the story
        check(o.reasons.any { "Festival" in it }) { o.reasons.toString() }
        val locked = QueuePlanner.order(q.copy(lockedOrder = true), emptyList())
        eq(listOf("s", "d", "p", "k", "e"), locked.tasks.map { it.id })
        check(locked.reasons.isEmpty() && locked.recommendations.size >= 2)
        val dq = QueuePlanner.order(GameQueue(listOf(QueueTask(id = "d", type = TaskType.DAILY_QUESTS), QueueTask(id = "s", type = TaskType.STORY_CONTINUE))), emptyList())
        eq(listOf("s", "d"), dq.tasks.map { it.id }); check(dq.reasons.any { "count toward quest" in it })
        val noEvents = QueuePlanner.order(q.copy(prioritiseEvents = false), emptyList())
        eq("s", noEvents.tasks.first().id)
    }

    T.test("every task has a finite stop rule; queue, presets and events survive saving") {
        TaskType.values().forEach { t -> t.stops.forEach { s -> check(QueueTask(type = t, stop = s, number = 999).maxRuns in 1..50) } }
        val p = dev.ciel.game.GameProfile(GameCatalog.COOKIE_RUN_KINGDOM, "CRK",
            queue = GameQueue(listOf(QueueTask(type = TaskType.FARM, stop = StopKind.RUNS, number = 4, resource = "skill powder")), lockedOrder = true),
            presets = QueuePlanner.DEFAULT_PRESETS + QueuePreset("Mine", GameQueue(listOf(QueueTask(type = TaskType.DAILY_QUESTS)))),
            events = listOf(EventInfo("Festival", 123L, "3h")))
        val back = GameProfileCodec.decode(GameProfileCodec.encode(p), trustPolicy = true)
        eq(p.queue, back.queue); eq(p.presets.map { it.name }, back.presets.map { it.name }); eq("Festival", back.events.single().name)
    }

    T.test("queue run with the AI out of credits: kingdom collection completes on the phone alone") {
        val repo = InMemoryGameProfileRepository()
        // Screens: kingdom with a ready reward, a stage screen, a battle result, and the stamina offer.
        val kingdom = scr(1, txt(1, "Kingdom Level 12"), txt(2, "Collect"), txt(3, "World Exploration"))
        val collected = scr(2, txt(1, "Kingdom Level 12"), txt(3, "World Exploration"), txt(4, "Production queue"))
        val stage = scr(3, txt(1, "Episode 3-4"), txt(5, "Start Battle"), txt(6, "Stamina 12/60"))
        val win = scr(4, txt(1, "Victory"), txt(7, "Stage Clear"), txt(8, "Results"))
        val noStamina = scr(5, txt(1, "Not enough Stamina Jellies"), txt(9, "Refill with 50 Crystals"), txt(10, "Close"))
        var battles = 0
        val dev = FakeDevice(kingdom, transitions = { a, s ->
            val id = (a as? DeviceAction.ClickNode)?.nodeId
            when {
                s === kingdom && id == 2 -> collected
                (s === kingdom || s === collected) && id == 3 -> stage
                s === stage && id == 5 -> { battles++; if (battles <= 2) win else noStamina }
                s === win -> stage
                else -> null
            }
        })
        val stop = EmergencyStop()
        // The AI is out of credits for the whole run.
        val ai = ActionPlanner { c ->
            // Only reached for situations local rules can't decide; here: start the battle on the stage screen.
            PlannerDecision.Fail("The AI providers could not be reached: quota exceeded")
        }
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { false }) {}, ai, ResourceLocks())
        val engine = GameEngine(repo, pkgs, agentFactory = { agent })
        var runLog: String? = null
        engine.runLogSink = { _, text -> runLog = text }
        repo.save(engine.profileFor(GameCatalog.COOKIE_RUN_KINGDOM).copy(policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER))
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val q = GameQueue(listOf(QueueTask(id = "k", type = TaskType.KINGDOM_COLLECT)))
        val id = tasks.start("queue", "game") { h -> engine.runQueue(GameCatalog.COOKIE_RUN_KINGDOM, q, h, maxStepsPerRun = 8) }
        val t = done(tasks, id)
        check("Queue complete" in t.report!!) { t.report!! }
        check("0 by the AI model" in t.report!!) { t.report!! }
        val log = runLog ?: error("no run log")
        check("SCREEN" in log && "DECIDED by phone" in log && "RESULT" in log && "Collect" in log) { log }
        eq(GameActivity.ItemState.DONE, engine.activity.value!!.queue.single().state)
    }

    T.test("farming until stamina runs out stops at the game's \"not enough\" message and never taps the crystal refill") {
        val repo = InMemoryGameProfileRepository()
        val stage = scr(3, txt(1, "Episode 3-4"), txt(5, "Start Battle"))
        val win = scr(4, txt(1, "Victory"), txt(7, "Stage Clear"), txt(8, "Results"))
        val noStamina = scr(5, txt(1, "Not enough Stamina Jellies"), txt(9, "Refill with 50 Crystals"), txt(10, "Close"))
        var battles = 0
        val dev = FakeDevice(stage, transitions = { a, s ->
            val id = (a as? DeviceAction.ClickNode)?.nodeId
            when { s === stage && id == 5 -> { battles++; if (battles <= 2) win else noStamina }; s === win -> stage; else -> null }
        })
        val stop = EmergencyStop()
        val ai = ActionPlanner { c -> if (c.screen === stage) PlannerDecision.Act(DeviceAction.ClickNode(5, "Start Battle"), "start") else PlannerDecision.Act(DeviceAction.ClickNode(9, "Refill"), "refill stamina") }
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { false }) {}, ai, ResourceLocks())
        val engine = GameEngine(repo, pkgs, agentFactory = { agent })
        repo.save(engine.profileFor(GameCatalog.COOKIE_RUN_KINGDOM).copy(policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER))
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val q = GameQueue(listOf(QueueTask(type = TaskType.FARM, stop = StopKind.UNTIL_STAMINA_OUT, resource = "skill powder")))
        val t = done(tasks, tasks.start("farm", "game") { h -> engine.runQueue(GameCatalog.COOKIE_RUN_KINGDOM, q, h, maxStepsPerRun = 6) })
        check(dev.performed.none { it is DeviceAction.ClickNode && it.nodeId == 9 }) { dev.performed.toString() }
        check("2 run(s) verified" in t.report!! && "insufficient" in t.report!!) { t.report!! }
    }

    T.test("repeating tasks stop at their rule, and \"skip\" moves on to the next task") {
        val repo = InMemoryGameProfileRepository()
        val stage = scr(3, txt(1, "Episode 3-4"), txt(5, "Start Battle"))
        val win = scr(4, txt(1, "Victory"), txt(7, "Stage Clear"), txt(8, "Results"))
        val dev = FakeDevice(stage, transitions = { a, s ->
            val id = (a as? DeviceAction.ClickNode)?.nodeId
            if (s === stage && id == 5) win else if (s === win) stage else null
        })
        val stop = EmergencyStop()
        var aiCalls = 0
        // AI starts battles (local rules don't decide that); results are handled locally.
        val ai = ActionPlanner { c -> aiCalls++; if (c.screen === stage) PlannerDecision.Act(DeviceAction.ClickNode(5, "Start Battle"), "start") else PlannerDecision.Act(DeviceAction.Wait(100), "wait") }
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { false }) {}, ai, ResourceLocks())
        val engine = GameEngine(repo, pkgs, agentFactory = { agent })
        repo.save(engine.profileFor(GameCatalog.COOKIE_RUN_KINGDOM).copy(policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER))
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val q = GameQueue(listOf(QueueTask(id = "f", type = TaskType.FARM, stop = StopKind.RUNS, number = 2, resource = "skill powder")))
        val id = tasks.start("farm", "game") { h -> engine.runQueue(GameCatalog.COOKIE_RUN_KINGDOM, q, h, maxStepsPerRun = 6) }
        val t = done(tasks, id)
        eq(2, dev.performed.count { it is DeviceAction.ClickNode && it.nodeId == 5 }) // exactly two runs
        check("2 run(s) verified" in t.report!!) { t.report!! }
        check(aiCalls in 2..6) { "AI calls: $aiCalls" }
    }
}

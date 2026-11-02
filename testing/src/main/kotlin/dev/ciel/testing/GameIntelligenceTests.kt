package dev.ciel.testing

import dev.ciel.game.AutomationPolicy
import dev.ciel.game.Confidence
import dev.ciel.game.ElementKind
import dev.ciel.game.GameActionClass
import dev.ciel.game.GameActionGate
import dev.ciel.game.GameActivity
import dev.ciel.game.GameCatalog
import dev.ciel.game.GameEngine
import dev.ciel.game.GameIntent
import dev.ciel.game.GameKnowledgeBase
import dev.ciel.game.GamePlannerDecorator
import dev.ciel.game.GameProfileCodec
import dev.ciel.game.GameScreenReader
import dev.ciel.game.GameScreenState
import dev.ciel.game.GameSessionState
import dev.ciel.game.GameVocabulary
import dev.ciel.game.GateDecision
import dev.ciel.game.InMemoryGameProfileRepository
import dev.ciel.game.InstalledPackageInfo
import dev.ciel.game.KnowledgeEntry
import dev.ciel.game.KnowledgeKind
import dev.ciel.game.ObjectivePlanner
import dev.ciel.game.PremiumRule
import dev.ciel.game.ScreenStateTracker
import dev.ciel.game.SpendingPolicy
import dev.ciel.game.TaskChannel
import dev.ciel.game.TaskMessage
import dev.ciel.game.UpgradeRule
import dev.ciel.task.ActionPlanner
import dev.ciel.task.AgentContext
import dev.ciel.task.AgentOptions
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
import dev.ciel.task.TaskResult
import dev.ciel.task.TaskState
import dev.ciel.task.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout

/** Text read from a game's pixels (no accessibility nodes), as the OCR layer produces it. */
private fun seen(id: Int, text: String, y: Int = id * 120) = UiNode(id, text = text, className = OCR_CLASS, bounds = Bounds(100, y, 600, y + 80))
private fun game(hash: Long, vararg nodes: UiNode) = ScreenSnapshot("com.devsisters.ck", nodes = nodes.toList(), screenWidth = 1080, screenHeight = 2400, visualHash = hash)
private val CRK = GameCatalog.entryFor(GameCatalog.COOKIE_RUN_KINGDOM)!!

private suspend fun finished(tasks: TaskManager, id: String): TaskState = withTimeout(60_000) {
    while (tasks.get(id)?.isActive != false) delay(10)
    tasks.get(id)!!
}

private fun ctx(screen: ScreenSnapshot) = AgentContext("x", null, screen, emptyList(), emptyList(), 20)

fun gameIntelligenceTests() = T.suite("game-intelligence") {
    val reader = GameScreenReader(CRK.vocabulary)

    T.test("screen reading: quests, loading, shop and image-only screens, with confidence and evidence") {
        val q = reader.read(game(1, seen(1, "Daily Quests"), seen(2, "Weekly"), seen(3, "Mission 3/5"), seen(4, "Claim")))
        eq(GameScreenState.QUESTS, q.state); check(q.confidence != Confidence.LOW) { q.toString() }
        check(q.has(ElementKind.CLAIM)); check("3/5" in q.counters) { q.counters.toString() }
        eq(GameScreenState.LOADING, reader.read(game(2, seen(1, "Now Loading..."))).state)
        eq(GameScreenState.SHOP, reader.read(game(3, seen(1, "Special Package"), seen(2, "R 49.99"))).state)
        val blind = reader.read(game(4))
        eq(GameScreenState.UNKNOWN, blind.state); eq(Confidence.LOW, blind.confidence); check(!blind.textAvailable)
        check(reader.read(game(5, seen(1, "Upgrade cost"), seen(2, "Requirements"), seen(3, "Level 7"))).has(ElementKind.UPGRADE))
        check(reader.read(game(6, seen(1, "500 Crystals"))).has(ElementKind.PREMIUM_CURRENCY))
    }

    T.test("several observations: a state read the same way twice gains confidence") {
        val t = ScreenStateTracker()
        val r = reader.read(game(1, seen(1, "Daily"), seen(2, "Quests")))
        val first = t.add(r); val second = t.add(r)
        check(second.confidence.ordinal > first.confidence.ordinal || second.confidence == Confidence.HIGH)
    }

    T.test("knowledge: repeat sightings raise confidence, failures and game updates lower it, secrets are refused") {
        var kb = GameKnowledgeBase()
        val e = KnowledgeEntry("Quests", KnowledgeKind.LOCATION, "Open quests", "Tap Quests on the kingdom", howToReach = listOf("On main: tap \"Quests\""), confidence = Confidence.MEDIUM, gameVersion = "1.0")
        kb = kb.record(e); kb = kb.record(e)
        eq(Confidence.HIGH, kb.entries.single().confidence); eq(2, kb.entries.single().seenCount)
        kb = kb.contradict(e.key)
        eq(Confidence.MEDIUM, kb.entries.single().confidence); check(kb.entries.single().needsRevalidation)
        kb = kb.record(e).onGameUpdated("2.0")
        check(kb.entries.single().needsRevalidation)
        eq(e.key, kb.search("where are the quests").first().key)
        eq(0, kb.record(KnowledgeEntry("Account", KnowledgeKind.MECHANIC, "password for login", "x")).entries.count { it.system == "Account" })
        check("re-checking" in kb.briefing("quests"))
    }

    T.test("knowledge survives export/import; imported knowledge is capped at medium and re-checked; spending rules stay private") {
        val repo = InMemoryGameProfileRepository()
        val p = GameCatalog.seed(dev.ciel.game.GameProfile(GameCatalog.COOKIE_RUN_KINGDOM, GameCatalog.COOKIE_RUN_KINGDOM))
        eq("Cookie Run: Kingdom", p.name)
        val rich = p.copy(knowledge = p.knowledge.record(KnowledgeEntry("Quests", KnowledgeKind.LOCATION, "Open quests", "d", confidence = Confidence.HIGH)),
            spending = SpendingPolicy(upgrades = UpgradeRule.AUTO_AFFORDABLE))
        val own = GameProfileCodec.decode(GameProfileCodec.encode(rich), trustPolicy = true)
        eq(rich.knowledge.entries.size, own.knowledge.entries.size); eq(UpgradeRule.AUTO_AFFORDABLE, own.spending.upgrades)
        val shared = GameProfileCodec.decode(GameProfileCodec.encode(rich))
        check(shared.knowledge.entries.all { it.confidence != Confidence.HIGH && it.needsRevalidation })
        eq(UpgradeRule.ASK_EACH, shared.spending.upgrades)
        repo.save(own); check(repo.get(GameCatalog.COOKIE_RUN_KINGDOM) != null)
    }

    T.test("spending rules: upgrades ask or proceed per policy, premium currency and real money need approval, PvP is refused") {
        val gate = GameActionGate(CRK.vocabulary)
        val up = game(1, seen(1, "Upgrade"), seen(2, "Requirements"), seen(3, "Level 4"))
        val r = reader.read(up)
        val cls = gate.classify(DeviceAction.ClickNode(1), "upgrade the bakery", up, r)
        eq(GameActionClass.UPGRADE, cls)
        check(gate.decide(cls, SpendingPolicy(), "Upgrade", emptySet()) is GateDecision.Clarify)
        eq(GateDecision.Allow, gate.decide(cls, SpendingPolicy(upgrades = UpgradeRule.AUTO_AFFORDABLE), "Upgrade", emptySet()))
        eq(GateDecision.Allow, gate.decide(cls, SpendingPolicy(), "Upgrade", setOf("upgrade")))
        check(gate.decide(GameActionClass.PREMIUM, SpendingPolicy(), "Crystals", emptySet()) is GateDecision.Block)
        check(gate.decide(GameActionClass.PREMIUM, SpendingPolicy(premium = PremiumRule.ASK), "Crystals", emptySet()) is GateDecision.Approve)
        val shop = game(2, seen(1, "Shop"), seen(2, "Buy"), seen(3, "R 19.99"))
        eq(GameActionClass.REAL_MONEY, gate.classify(DeviceAction.ClickNode(2), "", shop, reader.read(shop)))
        check(gate.decide(GameActionClass.REAL_MONEY, SpendingPolicy(premium = PremiumRule.ASK, upgrades = UpgradeRule.AUTO_AFFORDABLE), "Buy", setOf()) is GateDecision.Approve)
        eq(GameActionClass.SAFE, gate.classify(DeviceAction.ClickNode(1), "open the quest list", game(3, seen(1, "Quests")), reader.read(game(3, seen(1, "Quests")))))
    }

    T.test("objective planner: the brief's example requests map to plans") {
        eq(GameIntent.DAILY_QUESTS, ObjectivePlanner.intentOf("Ciel, complete my daily quests."))
        eq(GameIntent.COLLECT_REWARDS, ObjectivePlanner.intentOf("Ciel, collect all my kingdom rewards."))
        eq(GameIntent.UPGRADE_BUILDINGS, ObjectivePlanner.intentOf("Ciel, upgrade my buildings."))
        eq(GameIntent.CHECK_BUILDINGS, ObjectivePlanner.intentOf("Ciel, check which buildings need attention."))
        eq(GameIntent.PRODUCTION, ObjectivePlanner.intentOf("Ciel, complete the available production tasks."))
        eq(GameIntent.EXPLORE, ObjectivePlanner.intentOf("Ciel, explore Cookie Run: Kingdom and learn how everything works."))
        eq(GameIntent.IMPROVE, ObjectivePlanner.intentOf("Ciel, improve my kingdom progression."))
        val improve = ObjectivePlanner.plan("improve my kingdom")
        eq("Collect completed production", improve.steps.first().title) // quick wins first
        check(ObjectivePlanner.plan("explore and learn").steps.all { it.readOnly })
        check(improve.stepBudgets(40).all { it >= 6 })
    }

    T.test("messages to a running task: control words vs questions") {
        eq(TaskMessage.Stop, TaskMessage.parse("Ciel, stop."))
        eq(TaskMessage.Pause, TaskMessage.parse("pause"))
        eq(TaskMessage.Resume, TaskMessage.parse("Ciel, continue."))
        check(TaskMessage.parse("Ciel, skip that building.") is TaskMessage.Skip)
        check(TaskMessage.parse("Why are you upgrading that building?") is TaskMessage.Question)
        check(TaskMessage.parse("Only collect rewards, don't upgrade") is TaskMessage.Instruction)
    }

    T.test("canvas games: an image change counts as a change, so taps are not wrongly called ineffective") {
        val a = game(0x0F0F0F0F0F0F0F0FL); val b = game(0x70F0F0F0F0F0F0F0L)
        val dev = FakeDevice(a, transitions = { _, s -> if (s === a) b else null })
        val stop = EmergencyStop()
        var events = 0
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, { c ->
            if (c.screen === b) PlannerDecision.Complete("moved", "new image") else PlannerDecision.Act(DeviceAction.TapRel(0.5f, 0.9f), "open")
        }, ResourceLocks())
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val id = tasks.start("t", "game") { h -> agent.run(h, "t", AgentOptions(settleDelayMs = 0, onStep = { e -> if (e.changed) events++ })).result }
        eq(true, finished(tasks, id).verified); eq(1, events)
        check(a.signature() != b.signature())
    }

    T.test("decorator: a clarification pauses only that action; the user's answer lets it through") {
        val channel = TaskChannel()
        val session = GameSessionState("CRK", "1", GameKnowledgeBase(), SpendingPolicy(), CRK.vocabulary, channel, ConfirmationGate { false }, MutableStateFlow(null), CRK.forbiddenAreas)
        val screen = game(1, seen(1, "Upgrade"), seen(2, "Collect"), seen(3, "Level 3"))
        val inner = ActionPlanner { c ->
            if (c.warnings.any { "being asked" in it }) PlannerDecision.Act(DeviceAction.ClickNode(2), "collect goods")
            else PlannerDecision.Act(DeviceAction.ClickNode(1), "upgrade it")
        }
        val dec = GamePlannerDecorator(inner, session) { false }
        val first = dec.next(ctx(screen)) as PlannerDecision.Act
        eq(DeviceAction.ClickNode(2), first.action)            // did other work instead of waiting
        val q = channel.questions.value.single(); check("Upgrade" in q.question)
        channel.answer(q.id, 0)
        eq(DeviceAction.ClickNode(1), (dec.next(ctx(screen)) as PlannerDecision.Act).action)
        check(channel.directives.any { "approved" in it })
    }

    T.test("decorator: premium purchases need explicit approval; read-only steps never collect; blockers stop with a reason") {
        var asked = 0
        val session = GameSessionState("CRK", "1", GameKnowledgeBase(), SpendingPolicy(premium = PremiumRule.ASK), CRK.vocabulary, TaskChannel(),
            ConfirmationGate { asked++; false }, MutableStateFlow(null), CRK.forbiddenAreas)
        val shop = game(1, seen(1, "Use 300 Crystals"), seen(2, "Close"))
        val dec = GamePlannerDecorator({ c -> if (c.warnings.any { "declined" in it }) PlannerDecision.Act(DeviceAction.ClickNode(2), "close") else PlannerDecision.Act(DeviceAction.ClickNode(1), "spend") }, session) { false }
        eq(DeviceAction.ClickNode(2), (dec.next(ctx(shop)) as PlannerDecision.Act).action); eq(1, asked)

        val ro = GamePlannerDecorator({ c -> if (c.warnings.any { "Not allowed in this step" in it }) PlannerDecision.Complete("read", "list shown") else PlannerDecision.Act(DeviceAction.ClickNode(2), "claim") }, session) { true }
        check(ro.next(ctx(game(2, seen(1, "Daily Quests"), seen(2, "Claim")))) is PlannerDecision.Complete)

        val blocked = GamePlannerDecorator({ PlannerDecision.Act(DeviceAction.ClickNode(1), "x") }, session) { false }.next(ctx(game(3, seen(1, "Server maintenance"), seen(2, "Under maintenance"))))
        check(blocked is PlannerDecision.Fail && "maintenance" in blocked.reason)
        val blind = GamePlannerDecorator({ PlannerDecision.Act(DeviceAction.ClickNode(1), "x") }, session) { false }.next(ctx(game(4)))
        check(blind is PlannerDecision.Fail && "cannot see" in blind.reason)
    }

    T.test("full run: plan → steps with proof → learns the route to quests → honest partial report and live activity") {
        val pkgs = object : InstalledPackageInfo {
            override fun isGame(packageName: String) = true
            override fun versionName(packageName: String) = "5.0"
            override fun label(packageName: String) = "Cookie Run: Kingdom"
        }
        val repo = InMemoryGameProfileRepository()
        val main = game(0x1111L, seen(1, "Kingdom Level 12"), seen(2, "Quests"))
        val quests = game(0x7777777777777777L, seen(1, "Daily Quests"), seen(2, "Claim"), seen(3, "Weekly"), seen(4, "Mission 5/5"))
        val claimed = game(0x7777777700000000L, seen(1, "Daily Quests"), seen(5, "Completed"), seen(3, "Weekly"), seen(6, "Rewards received"))
        val dev = FakeDevice(main, transitions = { a, s ->
            when {
                s === main && a is DeviceAction.ClickNode && a.nodeId == 2 -> quests
                s === quests && a is DeviceAction.ClickNode && a.nodeId == 2 -> claimed
                else -> null
            }
        })
        val stop = EmergencyStop()
        val planner = ActionPlanner { c ->
            val o = c.objective
            val onMain = c.screen.nodes.any { it.text == "Quests" }
            when {
                "Read every visible quest" in o -> when {
                    onMain -> PlannerDecision.Act(DeviceAction.ClickNode(2), "open quests", "the quest list opens")
                    c.warnings.any { "Not allowed in this step" in it } -> PlannerDecision.Complete("Daily quests: 1 claimable", "quest list on screen")
                    c.screen.nodes.any { it.text == "Claim" } -> PlannerDecision.Act(DeviceAction.ClickNode(2), "claim it") // must be refused (read-only)
                    else -> PlannerDecision.Complete("All quests listed", "quest list on screen")
                }
                "claim every reward" in o -> if (c.screen.nodes.any { it.text == "Claim" }) PlannerDecision.Act(DeviceAction.ClickNode(2), "claim reward", "reward received")
                    else PlannerDecision.Complete("Claimed 1 reward", "\"Rewards received\" shown")
                "Pick the incomplete" in o -> PlannerDecision.Fail("No easy objectives left: the rest need battles.")
                else -> PlannerDecision.Complete("Nothing more to claim", "no Claim buttons")
            }
        }
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, planner, ResourceLocks())
        val engine = GameEngine(repo, pkgs, agentFactory = { agent })
        engine.profileFor(GameCatalog.COOKIE_RUN_KINGDOM)
        repo.save(repo.get(GameCatalog.COOKIE_RUN_KINGDOM)!!.copy(policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER))
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val id = tasks.start("quests", "game") { h -> engine.runObjective(GameCatalog.COOKIE_RUN_KINGDOM, "Complete my daily quests", h, maxSteps = 40) }
        val t = finished(tasks, id)
        val report = t.report!!
        check("partially complete" in report) { report }
        check("Inspect quests" in report && "Claim completed quest rewards" in report) { report }
        check("Complete easy objectives: not completed" in report) { report }
        // Exactly two taps: open Quests, then one Claim in the claim step (never in the read-only step).
        eq(2, dev.performed.count { it is DeviceAction.ClickNode && it.nodeId == 2 })
        val kb = repo.get(GameCatalog.COOKIE_RUN_KINGDOM)!!.knowledge
        val route = kb.entries.firstOrNull { it.kind == KnowledgeKind.LOCATION && it.system == "Quests" }
        check(route != null && route.howToReach.single().contains("\"Quests\"")) { kb.entries.joinToString("\n") }
        check(kb.entries.any { it.kind == KnowledgeKind.WORKFLOW })
        val a = engine.activity.value!!
        eq(GameActivity.Status.FAILED, a.status); check(a.finalReport != null)
        eq(4, a.stepCount)
        check(engine.answer(id, "what are you doing?").isNotBlank())
    }
}

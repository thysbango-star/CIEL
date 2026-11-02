package dev.ciel.testing

import dev.ciel.comms.CommunicationEngine
import dev.ciel.comms.Contact
import dev.ciel.comms.ContactDirectory
import dev.ciel.comms.MessageDraft
import dev.ciel.comms.MessageSender
import dev.ciel.comms.Platform
import dev.ciel.comms.Resolution
import dev.ciel.comms.SendResult
import dev.ciel.core.json.json
import dev.ciel.game.AutomationPolicy
import dev.ciel.game.GameEngine
import dev.ciel.game.GameProfile
import dev.ciel.game.GameProfileCodec
import dev.ciel.game.InMemoryGameProfileRepository
import dev.ciel.game.InstalledPackageInfo
import dev.ciel.memory.InMemoryMemoryRepository
import dev.ciel.memory.MemoryCategory
import dev.ciel.memory.MemoryManager
import dev.ciel.memory.MemorySettings
import dev.ciel.research.PageFetcher
import dev.ciel.research.ResearchSkill
import dev.ciel.research.SearchHit
import dev.ciel.research.SearchProvider
import dev.ciel.skills.Capability
import dev.ciel.skills.DeclarativeSkill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.SkillRegistry
import dev.ciel.skills.builtin.Calculator
import dev.ciel.skills.builtin.SystemSkill
import dev.ciel.task.ActionPolicy
import dev.ciel.task.AgentOptions
import dev.ciel.task.Bounds
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.DeviceAction
import dev.ciel.task.EmergencyStop
import dev.ciel.task.GuardedDevice
import dev.ciel.task.PlannerDecision
import dev.ciel.task.ResourceLocks
import dev.ciel.task.Risk
import dev.ciel.task.ScreenAgent
import dev.ciel.task.ScreenSnapshot
import dev.ciel.task.TaskManager
import dev.ciel.task.TaskResult
import dev.ciel.task.TaskStatus
import dev.ciel.task.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private fun node(id: Int, text: String, clickable: Boolean = true) = UiNode(id, text = text, bounds = Bounds(0, id * 100, 500, id * 100 + 80), clickable = clickable)
private fun screen(pkg: String, vararg nodes: UiNode) = ScreenSnapshot(pkg, nodes = nodes.toList(), screenWidth = 1080, screenHeight = 2400)

private suspend fun waitFor(tasks: TaskManager, id: String) = withTimeout(10_000) {
    while (tasks.get(id)!!.isActive) delay(10)
    tasks.get(id)!!
}

fun taskTests() = T.suite("task-engine") {
    T.test("policy: purchases, sending, deleting need confirmation; CAPTCHA forbidden; navigation routine") {
        val s = screen("g", node(1, "Buy 500 Gems R49.99"), node(2, "Daily Rewards"), node(3, "Send"), node(4, "Delete account"))
        eq(Risk.CONSEQUENTIAL, ActionPolicy.assess(DeviceAction.ClickNode(1), s).risk)
        eq(Risk.ROUTINE, ActionPolicy.assess(DeviceAction.ClickNode(2), s).risk)
        eq(Risk.CONSEQUENTIAL, ActionPolicy.assess(DeviceAction.ClickNode(3), s).risk)
        eq(Risk.CONSEQUENTIAL, ActionPolicy.assess(DeviceAction.ClickNode(4), s).risk)
        eq(Risk.CONSEQUENTIAL, ActionPolicy.assess(DeviceAction.TapAt(250, 140), s).risk, "coordinate tap on purchase button")
        eq(Risk.FORBIDDEN, ActionPolicy.assess(DeviceAction.ClickNode(2), screen("w", node(1, "I'm not a robot"))).risk)
    }
    T.test("guarded device: declined confirmation means the action never reaches the device") {
        val dev = FakeDevice(screen("g", node(1, "Purchase pack")))
        val records = mutableListOf<String>()
        val g = GuardedDevice(dev, EmergencyStop(), ConfirmationGate { false }) { records += it.outcome }
        g.perform("t", 0, DeviceAction.ClickNode(1), dev.screen)
        check(dev.performed.isEmpty()); eq(listOf("Declined by user"), records)
    }
    T.test("emergency stop cancels running tasks, blocks further actions and preserves history") {
        val stop = EmergencyStop()
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val dev = FakeDevice(screen("a", node(1, "Next")))
        val g = GuardedDevice(dev, stop, ConfirmationGate { true }) {}
        val id = tasks.start("loop forever", "test") { h ->
            while (true) { g.perform(h.id, h.generation, DeviceAction.ClickNode(1), dev.screen); delay(20) }
            @Suppress("UNREACHABLE_CODE") TaskResult(true, "", false)
        }
        delay(150)
        val before = dev.performed.size
        check(before > 0)
        stop.trigger("test")
        val t = waitFor(tasks, id)
        eq(TaskStatus.STOPPED, t.status)
        delay(100)
        check(dev.performed.size <= before + 1) { "actions continued after stop" }
        throws<dev.ciel.task.StoppedException> { kotlinx.coroutines.runBlocking { g.perform("x", 0, DeviceAction.ClickNode(1), null) } }
        eq(0, stop.activeJobCount())
    }
    T.test("screen agent: completes a multi-screen objective with verified evidence") {
        val home = screen("com.android.settings", node(1, "Display"), node(2, "Sound"))
        val display = screen("com.android.settings", node(1, "Dark theme"), node(2, "Brightness"))
        val dark = screen("com.android.settings", node(1, "Dark theme", clickable = true).copy(checked = true), node(2, "Brightness"))
        val dev = FakeDevice(home, transitions = { a, s -> when {
            s === home && a == DeviceAction.ClickNode(1) -> display
            s === display && a == DeviceAction.ClickNode(1) -> dark
            else -> null } })
        val stop = EmergencyStop()
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, { ctx ->
            when {
                ctx.screen.nodes.any { it.checked == true } -> PlannerDecision.Complete("Dark theme enabled", "Dark theme is checked")
                else -> PlannerDecision.Act(DeviceAction.ClickNode(1), "next")
            } }, ResourceLocks())
        val id = tasks.start("dark mode", "execute") { h -> agent.run(h, "turn on dark theme", AgentOptions(settleDelayMs = 1)).result }
        val t = waitFor(tasks, id)
        eq(TaskStatus.COMPLETED, t.status); check(t.verified); eq(2, dev.performed.size)
    }
    T.test("screen agent: refuses to blindly repeat ineffective actions and gives up honestly") {
        val dev = FakeDevice(screen("app", node(1, "Broken button")))
        val stop = EmergencyStop()
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, { PlannerDecision.Act(DeviceAction.ClickNode(1), "again") }, ResourceLocks())
        val id = tasks.start("stuck", "execute") { h -> agent.run(h, "x", AgentOptions(settleDelayMs = 1, maxIneffectiveRepeats = 2)).result }
        val t = waitFor(tasks, id)
        eq(TaskStatus.FAILED, t.status); check(!t.verified); eq(2, dev.performed.size); check("Stuck" in t.report!!)
    }
    T.test("resource lock serialises two screen tasks") {
        val locks = ResourceLocks()
        val order = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = scope.launch { locks.withResource(ResourceLocks.Resource.SCREEN) { order += "a1"; delay(80); order += "a2" } }
        delay(10)
        val b = scope.launch { locks.withResource(ResourceLocks.Resource.SCREEN) { order += "b1"; order += "b2" } }
        a.join(); b.join()
        eq(listOf("a1", "a2", "b1", "b2"), order)
    }
}


fun skillTests() = T.suite("skill-system") {
    T.test("calculator: precedence, power, percent, functions, errors") {
        eq("23", Calculator.format(Calculator.evaluate("3 + 4 * 5")))
        eq("512", Calculator.format(Calculator.evaluate("2^3^2")))
        eq("36", Calculator.format(Calculator.evaluate("15% * 240")))
        eq("1", Calculator.format(Calculator.evaluate("10 % 3")))
        eq("14", Calculator.format(Calculator.evaluate("2(3+4)")))
        eq("0.5", Calculator.format(Calculator.evaluate("sin(rad(30))")))
        eq("1234567", Calculator.format(Calculator.evaluate("1,234,567")))
        eq("-9", Calculator.format(Calculator.evaluate("-(3^2)")))
        throws<Calculator.CalcException> { Calculator.evaluate("1/0") }
        throws<Calculator.CalcException> { Calculator.evaluate("2 +") }
    }
    T.test("app resolution: exact, prefix, fuzzy, and no wild guesses") {
        val apps = mapOf("Cookie Run: Kingdom" to "com.devsisters.ck", "Minecraft" to "com.mojang.minecraftpe", "Settings" to "com.android.settings")
        eq("com.devsisters.ck", SystemSkill.resolveApp("cookie run kingdom", apps))
        eq("com.devsisters.ck", SystemSkill.resolveApp("cookie run", apps))
        eq("com.mojang.minecraftpe", SystemSkill.resolveApp("minecraf", apps))
        eq("com.mojang.minecraftpe", SystemSkill.resolveApp("mincraft", apps))
        eq(null, SystemSkill.resolveApp("banking", apps))
    }
    T.test("registry refuses tools whose permissions are missing, with a clear message") {
        val reg = SkillRegistry { emptySet() }
        reg.register(DeclarativeSkill.parse("""{"id":"demo","tools":[{"name":"go_home","steps":[{"do":"home"}]}]}"""))
        val out = reg.dispatch("go_home", json { }, SkillContext(null, TaskManager(CoroutineScope(Dispatchers.Default), EmergencyStop()), ConfirmationGate { true }, { true }, generation = 0))
        check(!out.success && "accessibility" in out.content) { out.content }
    }
    T.test("declarative skill: validates operations and runs with parameter substitution through the guard") {
        throws<IllegalArgumentException> { DeclarativeSkill.parse("""{"id":"bad","tools":[{"name":"x","steps":[{"do":"shell","cmd":"rm"}]}]}""") }
        val skill = DeclarativeSkill.parse("""{"id":"note","name":"Quick note","tools":[{"name":"quick_note","params":{"text":"Note text"},
            "steps":[{"do":"open_app","package":"com.notes"},{"do":"expect_text","text":"New"},{"do":"tap_text","text":"New"},{"do":"type","text":"{{text}}"}]}]}""")
        val dev = FakeDevice(screen("com.notes", node(1, "New note")))
        val stop = EmergencyStop()
        val ctx = SkillContext(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, TaskManager(CoroutineScope(Dispatchers.Default), stop), ConfirmationGate { true }, { true }, generation = 0)
        val out = skill.invoke("quick_note", json { "text" to "buy milk" }, ctx)
        check(out.success && out.verified) { out.content }
        eq(DeviceAction.TypeText(null, "buy milk"), dev.performed.last())
    }
}

fun memoryTests() = T.suite("memory-engine") {
    T.test("rejects sensitive data, de-duplicates, searches by relevance, respects settings") {
        val m = MemoryManager(InMemoryMemoryRepository())
        check(m.remember("My password is hunter2", MemoryCategory.PREFERENCE) is MemoryManager.SaveResult.Rejected)
        check(m.remember("card 4111 1111 1111 1111", MemoryCategory.PREFERENCE) is MemoryManager.SaveResult.Rejected)
        m.remember("User prefers metric units", MemoryCategory.PREFERENCE)
        m.remember("User prefers metric units always", MemoryCategory.PREFERENCE)
        m.remember("Project Ciel is an Android assistant in Kotlin", MemoryCategory.PROJECT)
        eq(2, m.search("").size, "dedupe")
        eq("Project Ciel is an Android assistant in Kotlin", m.search("kotlin android project").first().content)
        m.updateSettings(MemorySettings(retainedCategories = setOf(MemoryCategory.PREFERENCE)))
        check(m.remember("Clash Royale is installed", MemoryCategory.GAME) is MemoryManager.SaveResult.Rejected)
        check(m.remember("Inferred fact", MemoryCategory.PREFERENCE, source = "ciel") is MemoryManager.SaveResult.Rejected)
        m.updateSettings(MemorySettings(enabled = false))
        check(m.contextFor("units") == null)
    }
    T.test("task memories expire") {
        var now = 1_000_000L
        val m = MemoryManager(InMemoryMemoryRepository(), clock = { now })
        m.remember("temp step", MemoryCategory.TASK)
        now += 25 * 3_600_000L
        m.purgeExpired()
        check(m.search("temp step").isEmpty())
    }
}

fun commsTests() = T.suite("communication") {
    val dir = object : ContactDirectory {
        val all = listOf(Contact("1", "John Smith", listOf("+27820000001")), Contact("2", "John Doe", listOf("+27820000002")),
            Contact("3", "Sarah", listOf("+27820000003", "+27110000003")), Contact("4", "Family", listOf("+27820000004"), isGroup = true))
        override suspend fun find(query: String) = all.filter { it.displayName.contains(query, ignoreCase = true) }
    }
    class Sender(override val platform: Platform, override val canSendDirectly: Boolean) : MessageSender {
        val sent = mutableListOf<MessageDraft>()
        override fun isAvailable() = true
        override suspend fun send(draft: MessageDraft): SendResult { sent += draft; return if (canSendDirectly) SendResult.Submitted("ok") else SendResult.HandedToApp(platform.label, "") }
    }
    T.test("never guesses ambiguous recipients or numbers") {
        val e = CommunicationEngine(dir, emptyList(), null)
        check(e.resolve("John", Platform.SMS) is Resolution.Ambiguous)
        check(e.resolve("Sarah", Platform.SMS) is Resolution.Ambiguous) // two numbers
        eq("+27820000001", (e.resolve("John Smith", Platform.SMS) as Resolution.Resolved).address)
        check(e.resolve("Nobody", Platform.SMS) is Resolution.NotFound)
        check(e.resolve("John Smith", Platform.EMAIL) is Resolution.NoAddress)
    }
    T.test("nothing is sent without approval; group sends carry a warning; results are worded honestly") {
        val sms = Sender(Platform.SMS, true); val wa = Sender(Platform.WHATSAPP, false)
        val e = CommunicationEngine(dir, listOf(sms, wa), null)
        val john = dir.all[0]
        eq("Not sent — you declined.", (e.sendWithApproval(MessageDraft(Platform.SMS, john, "+27820000001", "late")) { false } as SendResult.Failed).reason)
        check(sms.sent.isEmpty())
        var shown = ""
        e.sendWithApproval(MessageDraft(Platform.SMS, dir.all[3], "+27820000004", "hi all")) { shown = it.detail; true }
        check("GROUP" in shown && "hi all" in shown)
        val r = e.sendWithApproval(MessageDraft(Platform.WHATSAPP, john, "+27820000001", "late")) { true }
        check("NOT been sent until you tap send" in CommunicationEngine.describe(r))
        check("not yet confirmed" in CommunicationEngine.describe(SendResult.Submitted("")))
    }
}

fun gameTests() = T.suite("game-engine") {
    val pkgs = object : InstalledPackageInfo {
        var version = "1.0"
        override fun isGame(packageName: String) = true
        override fun versionName(packageName: String) = version
        override fun label(packageName: String) = "Puzzle Quest"
    }
    T.test("unreviewed games are never automated") {
        val repo = InMemoryGameProfileRepository()
        val engine = GameEngine(repo, pkgs, agentFactory = { error("agent must not be created") })
        val tasks = TaskManager(CoroutineScope(Dispatchers.Default), EmergencyStop())
        val id = tasks.start("x", "game") { h -> TaskResult(false, engine.play("com.pq", "collect rewards", h).message, false) }
        check("not been reviewed" in waitFor(tasks, id).report!!)
    }
    T.test("game updates flag interface changes; verified runs record strategies and clear the flag") {
        val repo = InMemoryGameProfileRepository()
        val engine = GameEngine(repo, pkgs, agentFactory = { null })
        engine.profileFor("com.pq")
        repo.save(repo.get("com.pq")!!.copy(policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER))
        pkgs.version = "2.0"
        val p = engine.profileFor("com.pq")
        check(p.interfaceChangeSuspected); eq("2.0", p.versionName)
        // Simulate a verified run through the real agent with a fake device.
        val home = screen("com.pq", node(1, "Rewards")); val claimed = screen("com.pq", node(1, "Claimed!", clickable = false))
        val dev = FakeDevice(home, transitions = { a, s -> if (s === home && a == DeviceAction.ClickNode(1)) claimed else null })
        val stop = EmergencyStop()
        val agent = ScreenAgent(GuardedDevice(dev, stop, ConfirmationGate { true }) {}, { c ->
            if (c.screen.nodes.any { it.label == "Claimed!" }) PlannerDecision.Complete("Collected", "Claimed! shown") else PlannerDecision.Act(DeviceAction.ClickNode(1), "claim") }, ResourceLocks())
        val engine2 = GameEngine(repo, pkgs, agentFactory = { agent })
        val tasks = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), stop)
        val id = tasks.start("rewards", "game") { h -> engine2.play("com.pq", "collect daily rewards", h).run!!.result }
        eq(TaskStatus.COMPLETED, waitFor(tasks, id).status)
        val after = repo.get("com.pq")!!
        check(!after.interfaceChangeSuspected); eq(1, after.strategies.single().successCount)
        eq(listOf("Tap element #1"), after.strategies.single().steps)
    }
    T.test("profiles export/import, and imports never carry an 'allowed' automation decision") {
        val p = GameProfile("com.x", "X", policy = AutomationPolicy.ALLOWED_SINGLE_PLAYER, mechanics = listOf("energy refills hourly"))
        val back = GameProfileCodec.decode(GameProfileCodec.encode(p))
        eq(p.mechanics, back.mechanics); eq(AutomationPolicy.UNREVIEWED, back.policy)
    }
}

fun researchTests() = T.suite("research-engine") {
    T.test("html extraction drops scripts/nav and decodes entities") {
        val text = PageFetcher.htmlToText("<html><head><script>evil()</script><title>T</title></head><body><nav>menu</nav><h1>Ciel &amp; Raphael</h1><p>Line&nbsp;one</p></body></html>")
        check("evil" !in text && "menu" !in text && "Ciel & Raphael" in text && "Line one" in text) { text }
    }
    T.test("fetcher refuses private addresses and reports real HTTP failures") {
        val f = PageFetcher(FakeTransport { dev.ciel.core.http.HttpResponse(404, "") })
        throws<dev.ciel.research.SearchException> { kotlinx.coroutines.runBlocking { f.fetch("http://192.168.1.1/admin") } }
        val e = throws<dev.ciel.research.SearchException> { kotlinx.coroutines.runBlocking { f.fetch("https://example.com/missing") } }
        check("404" in e.message!!)
    }
    T.test("research skill wraps results as untrusted, labels source tiers, and fails clearly offline") {
        val provider = object : SearchProvider { override suspend fun search(query: String, count: Int) = listOf(SearchHit("Gov page", "https://data.gov/x", "snippet")) }
        val skill = ResearchSkill({ provider }, PageFetcher(FakeTransport { dev.ciel.core.http.HttpResponse(200, "") }))
        val ctx = { online: Boolean -> SkillContext(null, TaskManager(CoroutineScope(Dispatchers.Default), EmergencyStop()), ConfirmationGate { true }, { online }, generation = 0) }
        val out = skill.invoke("web_search", json { "query" to "x" }, ctx(true))
        check(out.untrusted && "institutional" in out.content)
        check(!skill.invoke("web_search", json { "query" to "x" }, ctx(false)).success)
    }
}

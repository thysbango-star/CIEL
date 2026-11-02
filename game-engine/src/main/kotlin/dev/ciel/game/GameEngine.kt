package dev.ciel.game

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.json
import dev.ciel.skills.Capability
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.builtin.SystemSkill
import dev.ciel.skills.reqStr
import dev.ciel.skills.str
import dev.ciel.task.AgentOptions
import dev.ciel.task.AgentRun
import dev.ciel.task.DeviceAction
import dev.ciel.task.ScreenAgent

enum class GameCategory { RPG, STRATEGY, SIMULATION, ADVENTURE, CASUAL, PUZZLE, MANAGEMENT, OTHER }

/**
 * Whether automation is allowed for this game. Defaults to UNREVIEWED: the user must confirm
 * that the game's terms permit automation before autonomous play is enabled.
 */
enum class AutomationPolicy { UNREVIEWED, ALLOWED_SINGLE_PLAYER, MENUS_ONLY, PROHIBITED }

data class VerifiedStrategy(
    val objective: String,
    /** Human-readable step descriptions that led to a verified completion. */
    val steps: List<String>,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val gameVersion: String?,
    val lastUsed: Long = System.currentTimeMillis(),
)

data class GameProfile(
    val packageName: String,
    val name: String,
    val category: GameCategory = GameCategory.OTHER,
    val policy: AutomationPolicy = AutomationPolicy.UNREVIEWED,
    val versionName: String? = null,
    /** Known screens: signature -> description ("main lobby", "daily rewards popup"). */
    val knownScreens: Map<String, String> = emptyMap(),
    val navigationNotes: List<String> = emptyList(),
    val mechanics: List<String> = emptyList(),
    val questStructure: List<String> = emptyList(),
    val strategies: List<VerifiedStrategy> = emptyList(),
    val supportedActions: List<String> = listOf("tap", "swipe", "navigate menus", "collect rewards"),
    val limitations: List<String> = emptyList(),
    /** Set when the installed version differs from the version the knowledge was learned on. */
    val interfaceChangeSuspected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun knowledgeSummary(): String = buildString {
        append("Game: $name ($packageName), category ${category.name.lowercase()}, version ${versionName ?: "unknown"}.\n")
        if (interfaceChangeSuspected) append("WARNING: the game was updated since this knowledge was recorded; verify every step.\n")
        if (navigationNotes.isNotEmpty()) append("Navigation: ${navigationNotes.joinToString("; ")}\n")
        if (mechanics.isNotEmpty()) append("Mechanics: ${mechanics.joinToString("; ")}\n")
        if (questStructure.isNotEmpty()) append("Quests: ${questStructure.joinToString("; ")}\n")
        if (limitations.isNotEmpty()) append("Limitations: ${limitations.joinToString("; ")}\n")
        strategies.sortedByDescending { it.successCount - it.failureCount }.take(5).forEach {
            append("Verified strategy for \"${it.objective}\" (${it.successCount} ok / ${it.failureCount} failed): ${it.steps.joinToString(" → ")}\n")
        }
        if (knownScreens.isNotEmpty()) append("Known screens: ${knownScreens.values.distinct().take(15).joinToString()}\n")
    }
}

interface GameProfileRepository {
    suspend fun get(packageName: String): GameProfile?
    suspend fun save(profile: GameProfile)
    suspend fun all(): List<GameProfile>
    suspend fun delete(packageName: String)
}

class InMemoryGameProfileRepository : GameProfileRepository {
    private val map = java.util.concurrent.ConcurrentHashMap<String, GameProfile>()
    override suspend fun get(packageName: String) = map[packageName]
    override suspend fun save(profile: GameProfile) { map[profile.packageName] = profile }
    override suspend fun all() = map.values.toList()
    override suspend fun delete(packageName: String) { map.remove(packageName) }
}

/** Platform info about installed packages (PackageManager on Android). */
interface InstalledPackageInfo {
    fun isGame(packageName: String): Boolean
    fun versionName(packageName: String): String?
    fun label(packageName: String): String?
}

/** Profiles serialise to JSON so they can be exported, shared and re-imported. */
object GameProfileCodec {
    fun encode(p: GameProfile): String = json {
        "packageName" to p.packageName; "name" to p.name; "category" to p.category.name; "policy" to p.policy.name
        opt("versionName", p.versionName)
        "knownScreens" to p.knownScreens; "navigationNotes" to p.navigationNotes; "mechanics" to p.mechanics
        "questStructure" to p.questStructure; "supportedActions" to p.supportedActions; "limitations" to p.limitations
        "interfaceChangeSuspected" to p.interfaceChangeSuspected; "updatedAt" to p.updatedAt
        "strategies" to p.strategies.map { s ->
            json { "objective" to s.objective; "steps" to s.steps; "successCount" to s.successCount; "failureCount" to s.failureCount; opt("gameVersion", s.gameVersion); "lastUsed" to s.lastUsed }
        }
    }.toString()

    /**
     * [trustPolicy] is true only for the app's own database. Imported/shared profiles never
     * carry an "allowed" automation decision: the user must review the game locally.
     */
    fun decode(text: String, trustPolicy: Boolean = false): GameProfile {
        val o = Json.parse(text)
        fun list(k: String) = o[k]?.array?.mapNotNull { it.string } ?: emptyList()
        return GameProfile(
            packageName = o["packageName"]?.string ?: throw IllegalArgumentException("packageName missing"),
            name = o["name"]?.string ?: "Game",
            category = runCatching { GameCategory.valueOf(o["category"]?.string ?: "OTHER") }.getOrDefault(GameCategory.OTHER),
            policy = when {
                trustPolicy -> runCatching { AutomationPolicy.valueOf(o["policy"]?.string ?: "") }.getOrDefault(AutomationPolicy.UNREVIEWED)
                o["policy"]?.string == AutomationPolicy.PROHIBITED.name -> AutomationPolicy.PROHIBITED
                else -> AutomationPolicy.UNREVIEWED
            },
            versionName = o["versionName"]?.string,
            knownScreens = o["knownScreens"]?.obj?.fields?.mapValues { it.value.string ?: "" } ?: emptyMap(),
            navigationNotes = list("navigationNotes"), mechanics = list("mechanics"), questStructure = list("questStructure"),
            supportedActions = list("supportedActions"), limitations = list("limitations"),
            interfaceChangeSuspected = o["interfaceChangeSuspected"]?.bool ?: false,
            updatedAt = o["updatedAt"]?.long ?: System.currentTimeMillis(),
            strategies = o["strategies"]?.array?.map { s ->
                VerifiedStrategy(s["objective"]?.string ?: "", s["steps"]?.array?.mapNotNull { it.string } ?: emptyList(),
                    s["successCount"]?.int ?: 0, s["failureCount"]?.int ?: 0, s["gameVersion"]?.string, s["lastUsed"]?.long ?: 0)
            } ?: emptyList(),
        )
    }
}

/**
 * Game recognition, profile maintenance, learning, and autonomous play through the
 * shared ScreenAgent (so all safety guarantees — stop, purchase confirmation, no
 * human-verification solving — apply to games too).
 */
class GameEngine(
    private val profiles: GameProfileRepository,
    private val packages: InstalledPackageInfo,
    private val agentFactory: () -> ScreenAgent?,
    /** Whether screenshots may be sent to the vision model (user setting). */
    private val screenshotsAllowed: () -> Boolean = { true },
) {
    /** Loads or creates a profile and flags interface changes after game updates. */
    suspend fun profileFor(packageName: String): GameProfile {
        val installedVersion = packages.versionName(packageName)
        val existing = profiles.get(packageName)
        if (existing == null) {
            val p = GameProfile(packageName, packages.label(packageName) ?: packageName, versionName = installedVersion)
            profiles.save(p)
            return p
        }
        if (installedVersion != null && existing.versionName != null && installedVersion != existing.versionName) {
            val updated = existing.copy(versionName = installedVersion, interfaceChangeSuspected = true, updatedAt = System.currentTimeMillis())
            profiles.save(updated)
            return updated
        }
        return existing
    }

    data class PlayOutcome(val run: AgentRun?, val message: String)

    suspend fun play(packageName: String, objective: String, handle: dev.ciel.task.TaskHandle, maxSteps: Int = 60): PlayOutcome {
        val profile = profileFor(packageName)
        when (profile.policy) {
            AutomationPolicy.UNREVIEWED -> return PlayOutcome(null,
                "${profile.name} has not been reviewed for automation. Open Ciel → Games, confirm the game's terms allow automation for single-player play, then try again.")
            AutomationPolicy.PROHIBITED -> return PlayOutcome(null, "Automation is marked as prohibited for ${profile.name}.")
            else -> Unit
        }
        val agent = agentFactory() ?: return PlayOutcome(null, "Autonomous play needs the accessibility service and a configured AI provider.")
        val scope = if (profile.policy == AutomationPolicy.MENUS_ONLY) "\nRestriction: only navigate menus and collect rewards; do not play matches or levels." else ""
        val knowledge = profile.knowledgeSummary() + scope +
            "\nRules: never buy anything or spend premium currency without the user's confirmation; never play competitive PvP matches; stop if a human-verification check appears."
        handle.plan(listOf("Open ${profile.name}", "Analyse current screen", "Execute plan", "Verify objective", "Record what worked"), objective)
        val run = agent.run(handle, "In ${profile.name}: $objective", AgentOptions(maxSteps = maxSteps, includeScreenshots = screenshotsAllowed(), settleDelayMs = 1500, knowledge = knowledge))
        learn(profile, objective, run)
        return PlayOutcome(run, run.result.report)
    }

    /** Records verified successful step sequences, failures, and newly seen screens. */
    internal suspend fun learn(profile: GameProfile, objective: String, run: AgentRun) {
        val effective = run.steps.filter { it.action != null && it.screenChanged == true }.map { it.action!!.describe() }
        val screens = profile.knownScreens.toMutableMap()
        run.steps.forEach { s -> screens.putIfAbsent(s.screenSignature, "seen during \"${objective.take(40)}\"") }
        val existing = profile.strategies.firstOrNull { it.objective.equals(objective, ignoreCase = true) }
        val strategies = profile.strategies.filter { it !== existing }.toMutableList()
        if (run.result.success && run.result.verified) {
            strategies += (existing?.copy(steps = effective.takeLast(30), successCount = existing.successCount + 1, gameVersion = profile.versionName, lastUsed = System.currentTimeMillis())
                ?: VerifiedStrategy(objective, effective.takeLast(30), gameVersion = profile.versionName))
        } else if (existing != null) {
            strategies += existing.copy(failureCount = existing.failureCount + 1, lastUsed = System.currentTimeMillis())
        }
        profiles.save(profile.copy(
            knownScreens = screens.entries.toList().takeLast(200).associate { it.key to it.value },
            strategies = strategies.sortedByDescending { it.lastUsed }.take(25),
            // A verified success on the new version clears the interface-change flag.
            interfaceChangeSuspected = profile.interfaceChangeSuspected && !(run.result.success && run.result.verified),
            updatedAt = System.currentTimeMillis(),
        ))
    }
}

class GameMasterSkill(private val engine: GameEngine, private val profiles: GameProfileRepository) : Skill {
    override val id = "game_master"
    override val displayName = "Game Master"
    override val description = "Recognise games, keep game profiles, and play permitted single-player tasks autonomously."
    override val requiredCapabilities = setOf(Capability.ACCESSIBILITY)
    override val tools = listOf(
        ToolSpec("identify_current_game", "Identify the game on screen and summarise its profile.", Schema.obj()),
        ToolSpec("play_game_task",
            "Start an autonomous task in a game, e.g. 'collect daily rewards'. Runs in the background; returns a task id. Not complete until task_status says COMPLETED and verified.",
            Schema.obj(listOf("game", "objective"),
                "game" to Schema.str("Game name or package"),
                "objective" to Schema.str("What to accomplish, with a clear completion condition"))),
        ToolSpec("add_game_note", "Save a verified fact about a game to its profile.",
            Schema.obj(listOf("game", "kind", "note"),
                "game" to Schema.str("Game name or package"),
                "kind" to Schema.str("Kind", listOf("navigation", "mechanic", "quest", "limitation")),
                "note" to Schema.str("The fact"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val device = ctx.device ?: return ToolOutcome.fail("Device control unavailable.")
        return when (tool) {
            "identify_current_game" -> {
                val screen = device.observe(ctx.generation)
                val pkg = screen.packageName ?: return ToolOutcome.fail("Could not determine the foreground app.")
                val p = engine.profileFor(pkg)
                ToolOutcome.ok(p.knowledgeSummary() + "Automation policy: ${p.policy}\nCurrent screen ${if (screen.signature() in p.knownScreens) "is known: ${p.knownScreens[screen.signature()]}" else "is new"}.", true)
            }
            "play_game_task" -> {
                val pkg = SystemSkill.resolveApp(args.reqStr("game"), device.installedApps())
                    ?: return ToolOutcome.fail("No installed game matches \"${args.str("game")}\".")
                val objective = args.reqStr("objective")
                val id = ctx.tasks.start(objective, "game") { handle ->
                    val open = device.perform(handle.id, handle.generation, DeviceAction.OpenApp(pkg), null)
                    if (open !is dev.ciel.task.ActionOutcome.Done) return@start dev.ciel.task.TaskResult(false, "Could not open the game.", false)
                    kotlinx.coroutines.delay(4000) // games take a while to load
                    val outcome = engine.play(pkg, objective, handle)
                    outcome.run?.result ?: dev.ciel.task.TaskResult(false, outcome.message, false)
                }
                ToolOutcome.ok("Game task started (id $id). It runs in the background and is NOT complete yet.")
            }
            "add_game_note" -> {
                val pkg = SystemSkill.resolveApp(args.reqStr("game"), device.installedApps()) ?: return ToolOutcome.fail("Game not found.")
                val p = engine.profileFor(pkg)
                val note = args.reqStr("note")
                val updated = when (args.str("kind")) {
                    "navigation" -> p.copy(navigationNotes = (p.navigationNotes + note).distinct().takeLast(40))
                    "mechanic" -> p.copy(mechanics = (p.mechanics + note).distinct().takeLast(40))
                    "quest" -> p.copy(questStructure = (p.questStructure + note).distinct().takeLast(40))
                    else -> p.copy(limitations = (p.limitations + note).distinct().takeLast(40))
                }
                profiles.save(updated.copy(updatedAt = System.currentTimeMillis()))
                ToolOutcome.ok("Saved to ${p.name}'s profile.", true)
            }
            else -> ToolOutcome.fail("Unknown tool $tool")
        }
    }
}

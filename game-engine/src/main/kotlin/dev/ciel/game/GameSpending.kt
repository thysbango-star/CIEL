package dev.ciel.game

import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json
import dev.ciel.task.DeviceAction
import dev.ciel.task.ScreenAgent
import dev.ciel.task.ScreenSnapshot

enum class UpgradeRule(val label: String) {
    NEVER("Never upgrade"),
    ASK_EACH("Ask before every upgrade"),
    AUTO_SELECTED("Upgrade only the buildings I list"),
    AUTO_AFFORDABLE("Upgrade automatically when affordable"),
}

enum class ProductionRule(val label: String) { ASK("Ask before starting production"), AUTO("Start production automatically") }

enum class PremiumRule(val label: String) { NEVER("Never spend premium currency"), ASK("Ask me every time") }

/**
 * The user's rules for spending in-game resources during authorised game tasks.
 * Real-money purchases are outside these rules: they ALWAYS need explicit approval.
 */
data class SpendingPolicy(
    val premium: PremiumRule = PremiumRule.NEVER,
    val upgrades: UpgradeRule = UpgradeRule.ASK_EACH,
    val selectedBuildings: List<String> = emptyList(),
    val production: ProductionRule = ProductionRule.AUTO,
    /** Ordinary free rewards may be claimed without asking. */
    val autoClaimRewards: Boolean = true,
    /** Percentage of each ordinary resource Ciel should leave untouched (0–90). */
    val reservePercent: Int = 20,
) {
    fun describe(): String = buildString {
        append("Spending rules: ${premium.label}; ${upgrades.label}")
        if (upgrades == UpgradeRule.AUTO_SELECTED && selectedBuildings.isNotEmpty()) append(" (${selectedBuildings.joinToString()})")
        append("; ${production.label}; ")
        append(if (autoClaimRewards) "claim free rewards automatically" else "ask before claiming rewards")
        if (reservePercent > 0) append("; keep at least $reservePercent% of each resource in reserve")
        append(". Real-money purchases always need my explicit approval.")
    }

    companion object {
        fun encode(p: SpendingPolicy) = json {
            "premium" to p.premium.name; "upgrades" to p.upgrades.name; "selectedBuildings" to p.selectedBuildings
            "production" to p.production.name; "autoClaimRewards" to p.autoClaimRewards; "reservePercent" to p.reservePercent
        }
        fun decode(o: JsonValue?): SpendingPolicy {
            if (o == null) return SpendingPolicy()
            return SpendingPolicy(
                premium = runCatching { PremiumRule.valueOf(o["premium"]?.string ?: "") }.getOrDefault(PremiumRule.NEVER),
                upgrades = runCatching { UpgradeRule.valueOf(o["upgrades"]?.string ?: "") }.getOrDefault(UpgradeRule.ASK_EACH),
                selectedBuildings = o["selectedBuildings"]?.array?.mapNotNull { it.string } ?: emptyList(),
                production = runCatching { ProductionRule.valueOf(o["production"]?.string ?: "") }.getOrDefault(ProductionRule.AUTO),
                autoClaimRewards = o["autoClaimRewards"]?.bool ?: true,
                reservePercent = (o["reservePercent"]?.int ?: 20).coerceIn(0, 90),
            )
        }
    }
}

/** What a pending game action would do. */
enum class GameActionClass { SAFE, COLLECT, UPGRADE, PRODUCE, PREMIUM, REAL_MONEY, UNKNOWN_CONFIRM }

sealed class GateDecision {
    object Allow : GateDecision()
    /** Needs a choice from the user; only this action waits — other safe work may continue. */
    data class Clarify(val question: String, val options: List<String>, val consequence: String) : GateDecision()
    /** Needs explicit approval before anything else happens (premium currency, real money). */
    data class Approve(val title: String, val detail: String) : GateDecision()
    data class Block(val reason: String) : GateDecision()
}

/**
 * Classifies a planned game action from the element it targets, the screen around it and the
 * planner's stated reason, then applies the user's [SpendingPolicy].
 */
class GameActionGate(private val vocab: GameVocabulary) {
    private val money = Regex("(?i)[\\$€£¥₹]\\s?\\d|\\d+[.,]\\d{2}\\s?(usd|eur|gbp|zar)\\b|\\bR\\s?\\d+[.,]\\d{2}")

    fun classify(action: DeviceAction, rationale: String, screen: ScreenSnapshot, reading: ScreenReading): GameActionClass {
        if (action is DeviceAction.Wait || action is DeviceAction.Scroll || action is DeviceAction.Swipe || action is DeviceAction.Global) return GameActionClass.SAFE
        val target = (ScreenAgent.targetLabel(action, screen) ?: "").lowercase()
        val why = rationale.lowercase()
        fun any(words: List<String>, s: String) = words.any { GameScreenReader.containsWord(s, it) }
        return when {
            money.containsMatchIn(target) || reading.state == GameScreenState.SHOP && any(vocab.purchase + vocab.confirm, target) -> GameActionClass.REAL_MONEY
            any(vocab.premiumCurrency, target) || any(vocab.premiumCurrency, why) && (any(vocab.purchase, why) || why.contains("spend")) -> GameActionClass.PREMIUM
            any(vocab.upgrade, target) || reading.state == GameScreenState.UPGRADE && any(vocab.confirm, target) || any(vocab.upgrade, why) && !why.contains("check") && !why.contains("inspect") -> GameActionClass.UPGRADE
            any(vocab.produce, target) || any(vocab.produce, why) && !why.contains("collect") -> GameActionClass.PRODUCE
            any(vocab.claim, target) || any(vocab.claim, why) -> GameActionClass.COLLECT
            // Confirming a dialog whose purpose Ciel could not read: ask.
            reading.state == GameScreenState.POPUP && any(vocab.confirm, target) && reading.confidence == Confidence.LOW -> GameActionClass.UNKNOWN_CONFIRM
            else -> GameActionClass.SAFE
        }
    }

    fun decide(cls: GameActionClass, policy: SpendingPolicy, target: String?, approvedTargets: Set<String>): GateDecision {
        val name = target ?: "this"
        if (target != null && target.lowercase() in approvedTargets) return GateDecision.Allow
        return when (cls) {
            GameActionClass.SAFE -> GateDecision.Allow
            GameActionClass.COLLECT -> if (policy.autoClaimRewards) GateDecision.Allow
                else GateDecision.Clarify("Claim \"$name\"?", listOf("Claim it", "Skip"), "Claiming is free but you asked to be consulted.")
            GameActionClass.UPGRADE -> when (policy.upgrades) {
                UpgradeRule.NEVER -> GateDecision.Block("Your rules say never to upgrade.")
                UpgradeRule.AUTO_AFFORDABLE -> GateDecision.Allow
                UpgradeRule.AUTO_SELECTED -> if (policy.selectedBuildings.any { b -> (target ?: "").contains(b, ignoreCase = true) }) GateDecision.Allow
                    else GateDecision.Clarify("Upgrade \"$name\"? It is not on your list of buildings to upgrade.", listOf("Upgrade it", "Skip"), "Upgrading spends in-game resources.")
                UpgradeRule.ASK_EACH -> GateDecision.Clarify("Upgrade \"$name\"?", listOf("Upgrade it", "Skip"), "Upgrading spends in-game resources (keeping ${policy.reservePercent}% in reserve).")
            }
            GameActionClass.PRODUCE -> if (policy.production == ProductionRule.AUTO) GateDecision.Allow
                else GateDecision.Clarify("Start production \"$name\"?", listOf("Start it", "Skip"), "Production uses materials.")
            GameActionClass.PREMIUM -> when (policy.premium) {
                PremiumRule.NEVER -> GateDecision.Block("Your rules say never to spend premium currency.")
                PremiumRule.ASK -> GateDecision.Approve("Spend premium currency?", "Ciel wants to tap \"$name\", which spends premium currency.")
            }
            GameActionClass.REAL_MONEY -> GateDecision.Approve("Real-money purchase", "\"$name\" looks like a purchase with real money. Ciel never buys anything without your explicit approval.")
            GameActionClass.UNKNOWN_CONFIRM -> GateDecision.Clarify("A pop-up Ciel cannot read asks for confirmation (\"$name\"). Confirm it?", listOf("Confirm", "Close it"), "Ciel could not tell what this confirms.")
        }
    }
}

package dev.ciel.game

import dev.ciel.task.OCR_CLASS
import dev.ciel.task.ScreenSnapshot
import dev.ciel.task.UiNode

/** What kind of screen the game is showing. */
enum class GameScreenState(val label: String, val blocking: Boolean = false) {
    MAIN("Main / kingdom screen"),
    QUESTS("Quests or missions"),
    PRODUCTION("Production"),
    BUILDING("Building information"),
    UPGRADE("Upgrade"),
    REWARD("Reward"),
    BATTLE_PREP("Battle preparation"),
    BATTLE_RESULT("Battle result"),
    EVENT("Event"),
    SHOP("Shop"),
    INVENTORY("Inventory / storage"),
    CHARACTERS("Characters"),
    POPUP("Pop-up"),
    LOADING("Loading", blocking = true),
    LOGIN("Login / account", blocking = true),
    MAINTENANCE("Maintenance", blocking = true),
    NETWORK_ERROR("Connection problem", blocking = true),
    UPDATE_REQUIRED("Game update required", blocking = true),
    HUMAN_CHECK("Human verification", blocking = true),
    UNKNOWN("Unfamiliar screen"),
}

enum class ElementKind { CLAIM, UPGRADE, CONFIRM, CLOSE, PURCHASE, PREMIUM_CURRENCY, PRODUCE, NAVIGATION, TIMER, COUNTER, TEXT }

/** An element Ciel recognised, with how sure she is. */
data class RecognisedElement(
    val label: String,
    val kind: ElementKind,
    val nodeId: Int?,
    val confidence: Confidence,
    /** Centre as screen fractions, if the position is known. */
    val at: Pair<Float, Float>? = null,
)

data class ScreenReading(
    val state: GameScreenState,
    val confidence: Confidence,
    /** Texts that led to the classification — shown to the user when explaining. */
    val evidence: List<String>,
    val elements: List<RecognisedElement>,
    val timers: List<String>,
    val counters: List<String>,
    val textAvailable: Boolean,
) {
    fun has(kind: ElementKind) = elements.any { it.kind == kind }
    fun summary(): String = buildString {
        append(state.label).append(" (").append(confidence.name.lowercase()).append(" confidence)")
        val claim = elements.filter { it.kind == ElementKind.CLAIM }.map { it.label }.distinct()
        if (claim.isNotEmpty()) append("; claimable: ").append(claim.take(4).joinToString())
        val up = elements.filter { it.kind == ElementKind.UPGRADE }.map { it.label }.distinct()
        if (up.isNotEmpty()) append("; upgrade options: ").append(up.take(3).joinToString())
        if (timers.isNotEmpty()) append("; timers: ").append(timers.take(3).joinToString())
        if (!textAvailable) append("; no readable text (image only)")
    }
}

/**
 * Words a game uses on its screens. Generic UI words apply to most games; a game profile adds
 * its own names (currencies, systems). These are recognition hints, not assumptions about where
 * anything is located.
 */
data class GameVocabulary(
    val states: Map<GameScreenState, List<String>>,
    val premiumCurrency: List<String>,
    val claim: List<String> = listOf("claim", "collect", "receive", "claim all", "collect all", "get reward", "rewards ready", "obtain"),
    val upgrade: List<String> = listOf("upgrade", "level up", "enhance", "expand", "promote", "lv up"),
    val confirm: List<String> = listOf("ok", "confirm", "yes", "start", "go", "continue", "tap to continue", "touch to start"),
    val close: List<String> = listOf("close", "cancel", "later", "skip", "back", "no thanks", "not now", "x"),
    val produce: List<String> = listOf("produce", "craft", "make", "start production", "queue"),
    val purchase: List<String> = listOf("buy", "purchase", "pay", "package", "special offer", "limited offer", "top up", "store"),
) {
    companion object {
        val GENERIC = GameVocabulary(
            states = mapOf(
                GameScreenState.LOADING to listOf("loading", "now loading", "downloading", "please wait", "connecting"),
                GameScreenState.LOGIN to listOf("log in", "login", "sign in", "guest", "link account", "terms of service"),
                GameScreenState.MAINTENANCE to listOf("maintenance", "under maintenance", "server check"),
                GameScreenState.NETWORK_ERROR to listOf("network error", "connection failed", "connection lost", "retry", "unstable network", "disconnected"),
                GameScreenState.UPDATE_REQUIRED to listOf("update required", "new version", "please update", "go to store"),
                GameScreenState.HUMAN_CHECK to listOf("captcha", "i'm not a robot", "verify you are human"),
                GameScreenState.QUESTS to listOf("quest", "quests", "mission", "missions", "daily", "weekly", "achievement", "achievements", "objective"),
                GameScreenState.PRODUCTION to listOf("production", "produce", "queue", "in production", "crafting", "ready"),
                GameScreenState.UPGRADE to listOf("upgrade", "level up", "required", "requirements", "upgrade cost", "max level"),
                GameScreenState.BUILDING to listOf("building", "info", "level", "lv"),
                GameScreenState.REWARD to listOf("reward", "rewards", "obtained", "you got", "received", "congratulations"),
                GameScreenState.BATTLE_PREP to listOf("battle", "team", "formation", "stage", "start battle", "power"),
                GameScreenState.BATTLE_RESULT to listOf("victory", "defeat", "clear", "stage clear", "results", "mvp"),
                GameScreenState.EVENT to listOf("event", "limited time", "festival", "season"),
                GameScreenState.SHOP to listOf("shop", "store", "package", "offer", "purchase", "buy"),
                GameScreenState.INVENTORY to listOf("inventory", "storage", "items", "materials", "warehouse"),
                GameScreenState.CHARACTERS to listOf("character", "characters", "hero", "heroes", "skill", "equipment"),
            ),
            premiumCurrency = listOf("gems", "diamonds", "crystals", "premium"),
        )
    }

    fun plus(extra: Map<GameScreenState, List<String>>, premium: List<String> = emptyList()) = copy(
        states = GENERIC.states.keys.union(extra.keys).associateWith { (states[it].orEmpty() + extra[it].orEmpty()).distinct() },
        premiumCurrency = (premiumCurrency + premium).distinct(),
    )
}

/**
 * Reads a game screen from accessibility text plus text read from the screenshot (OCR nodes).
 * Rule-based and explainable: every classification lists the words that led to it, and comes
 * with a confidence. Without readable text the state is UNKNOWN (low) and the vision model must
 * look at the screenshot.
 */
class GameScreenReader(private val vocab: GameVocabulary) {
    private val timer = Regex("(?i)\\b(\\d{1,2}:\\d{2}(:\\d{2})?|(\\d+\\s?[dhms]\\s?){1,3})\\b")
    private val counter = Regex("\\b\\d{1,6}\\s?/\\s?\\d{1,6}\\b")
    private val money = Regex("(?i)[\\$€£¥₹]\\s?\\d|\\d+[.,]\\d{2}\\s?(usd|eur|gbp|zar)\\b|\\bR\\s?\\d+[.,]\\d{2}")

    fun read(screen: ScreenSnapshot): ScreenReading {
        val texts = screen.nodes.mapNotNull { n -> (n.text ?: n.contentDescription)?.trim()?.takeIf { it.isNotEmpty() }?.let { n to it } }
        val all = texts.joinToString("  ") { it.second }.lowercase()
        val elements = texts.mapNotNull { (n, t) -> classify(n, t, screen) }
        val timers = texts.flatMap { (_, t) -> timer.findAll(t).map { it.value.trim() }.toList() }.filter { it.any(Char::isDigit) }.distinct().take(8)
        val counters = texts.flatMap { (_, t) -> counter.findAll(t).map { it.value }.toList() }.distinct().take(8)
        if (texts.isEmpty()) {
            return ScreenReading(GameScreenState.UNKNOWN, Confidence.LOW, emptyList(), emptyList(), emptyList(), emptyList(), textAvailable = false)
        }

        // Blocking situations first: they decide what Ciel may do at all.
        for (s in listOf(GameScreenState.HUMAN_CHECK, GameScreenState.MAINTENANCE, GameScreenState.UPDATE_REQUIRED, GameScreenState.NETWORK_ERROR, GameScreenState.LOGIN, GameScreenState.LOADING)) {
            val hits = vocab.states[s].orEmpty().filter { containsWord(all, it) }
            // Loading screens often show a single word; other blockers need clearer evidence
            // unless the screen has very little text (a dialog).
            val enough = hits.size >= 2 || (hits.isNotEmpty() && (s == GameScreenState.LOADING || s == GameScreenState.HUMAN_CHECK || texts.size <= 6))
            if (enough) return ScreenReading(s, if (hits.size >= 2) Confidence.HIGH else Confidence.MEDIUM, hits, elements, timers, counters, true)
        }

        val scores = vocab.states.filterKeys { !it.blocking }.mapValues { (_, words) -> words.filter { containsWord(all, it) } }
            .filterValues { it.isNotEmpty() }
        val ranked = scores.entries.sortedByDescending { it.value.size }
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)?.value?.size ?: 0
        var state = best?.key ?: GameScreenState.UNKNOWN
        var evidence = best?.value ?: emptyList()
        // A short screen with a close/confirm button and little else is a pop-up.
        val dialogLike = texts.size <= 8 && elements.any { it.kind == ElementKind.CLOSE || it.kind == ElementKind.CONFIRM }
        if (state == GameScreenState.UNKNOWN && dialogLike) { state = GameScreenState.POPUP; evidence = elements.filter { it.kind == ElementKind.CLOSE || it.kind == ElementKind.CONFIRM }.map { it.label } }
        if (money.containsMatchIn(all)) { state = GameScreenState.SHOP; evidence = evidence + "price shown" }
        val n = evidence.size
        val confidence = when {
            state == GameScreenState.UNKNOWN -> Confidence.LOW
            n >= 3 && n - second >= 2 -> Confidence.HIGH
            n >= 2 && n > second -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return ScreenReading(state, confidence, evidence.distinct(), elements, timers, counters, true)
    }

    private fun classify(n: UiNode, text: String, screen: ScreenSnapshot): RecognisedElement? {
        val t = text.lowercase()
        val kind = when {
            money.containsMatchIn(text) || vocab.purchase.any { containsWord(t, it) } -> ElementKind.PURCHASE
            vocab.premiumCurrency.any { containsWord(t, it) } -> ElementKind.PREMIUM_CURRENCY
            vocab.claim.any { containsWord(t, it) } -> ElementKind.CLAIM
            vocab.upgrade.any { containsWord(t, it) } -> ElementKind.UPGRADE
            vocab.produce.any { containsWord(t, it) } -> ElementKind.PRODUCE
            t.length <= 14 && vocab.close.any { t == it || containsWord(t, it) } -> ElementKind.CLOSE
            t.length <= 18 && vocab.confirm.any { t == it || containsWord(t, it) } -> ElementKind.CONFIRM
            timer.matches(t.trim()) -> ElementKind.TIMER
            counter.containsMatchIn(t) && t.length <= 12 -> ElementKind.COUNTER
            (n.clickable && n.className != OCR_CLASS) || (n.className == OCR_CLASS && t.length in 3..22) -> ElementKind.NAVIGATION
            else -> return null
        }
        // Accessibility labels are exact; text read from pixels may be misread.
        val conf = if (n.className == OCR_CLASS) Confidence.MEDIUM else Confidence.HIGH
        val at = if (screen.screenWidth > 0 && !n.bounds.isEmpty) n.bounds.centerX.toFloat() / screen.screenWidth to n.bounds.centerY.toFloat() / screen.screenHeight else null
        return RecognisedElement(text.take(40), kind, n.id, conf, at)
    }

    companion object {
        fun containsWord(haystack: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var i = haystack.indexOf(word)
            while (i >= 0) {
                val before = if (i == 0) ' ' else haystack[i - 1]
                val after = if (i + word.length >= haystack.length) ' ' else haystack[i + word.length]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                i = haystack.indexOf(word, i + 1)
            }
            return false
        }
    }
}

/**
 * Combines several observations: a state is only reported with full confidence once it was read
 * the same way twice in the last three looks (animations and transitions cause misreads).
 */
class ScreenStateTracker(private val window: Int = 3) {
    private val recent = ArrayDeque<ScreenReading>()

    fun add(r: ScreenReading): ScreenReading {
        recent.addLast(r); while (recent.size > window) recent.removeFirst()
        val agreeing = recent.count { it.state == r.state }
        val confidence = when {
            r.state == GameScreenState.UNKNOWN -> Confidence.LOW
            agreeing >= 2 -> r.confidence.up()
            recent.size >= 2 -> r.confidence.down().coerceAtLeastLow()
            else -> r.confidence
        }
        return r.copy(confidence = confidence)
    }

    private fun Confidence.coerceAtLeastLow() = this
    fun reset() = recent.clear()
}

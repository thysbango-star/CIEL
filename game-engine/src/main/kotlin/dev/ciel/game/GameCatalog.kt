package dev.ciel.game

/**
 * Per-game starting knowledge. Deliberately small: recognition words and safety rules from public
 * knowledge of the game. Nothing here claims where anything is on screen — locations are only
 * ever learned by observation. Seeded facts start at LOW confidence and "needs re-checking".
 */
object GameCatalog {
    data class Entry(
        val packageName: String,
        val name: String,
        val category: GameCategory,
        val vocabulary: GameVocabulary,
        /** Systems the exploration mode looks for, in order. */
        val systems: List<String>,
        /** Areas Ciel never plays (competitive PvP). */
        val forbiddenAreas: List<String>,
        val seedKnowledge: List<KnowledgeEntry>,
    )

    val PVP = listOf("pvp", "arena", "ranked", "versus", "duel")

    const val COOKIE_RUN_KINGDOM = "com.devsisters.ck"

    private val cookieRunKingdom = Entry(
        packageName = COOKIE_RUN_KINGDOM,
        name = "Cookie Run: Kingdom",
        category = GameCategory.SIMULATION,
        vocabulary = GameVocabulary.GENERIC.plus(
            extra = mapOf(
                GameScreenState.MAIN to listOf("kingdom", "kingdom level"),
                GameScreenState.PRODUCTION to listOf("goods", "materials", "production queue", "slot"),
                GameScreenState.CHARACTERS to listOf("cookie", "cookies", "toppings", "topping", "treasures", "treasure", "skill level"),
                GameScreenState.BATTLE_PREP to listOf("world exploration", "episode", "formation", "deploy"),
                GameScreenState.QUESTS to listOf("kingdom quests", "daily gifts"),
                GameScreenState.EVENT to listOf("event", "festival"),
            ),
            premium = listOf("crystals", "crystal", "rainbow cubes", "rainbow cube"),
        ),
        systems = listOf("Quests", "Production", "Buildings", "Rewards and mail", "Cookies", "Toppings and treasures", "World exploration", "Events", "Storage", "Research"),
        forbiddenAreas = PVP + listOf("kingdom arena"),
        seedKnowledge = listOf(
            KnowledgeEntry("Currencies", KnowledgeKind.RISK, "Crystals are premium currency",
                "Crystals can be bought with real money. Ciel follows your premium-currency rule (default: never spend).",
                landmarks = listOf("Crystals"), confidence = Confidence.LOW, source = KnowledgeSource.IMPORTED, needsRevalidation = true),
            KnowledgeEntry("Currencies", KnowledgeKind.RISK, "Rainbow Cubes are a premium resource",
                "Treated like premium currency: never spent without your rule allowing it.",
                landmarks = listOf("Rainbow Cubes"), confidence = Confidence.LOW, source = KnowledgeSource.IMPORTED, needsRevalidation = true),
            KnowledgeEntry("Arena", KnowledgeKind.RISK, "Kingdom Arena is competitive PvP",
                "Ciel does not play competitive PvP modes.", confidence = Confidence.MEDIUM, source = KnowledgeSource.IMPORTED, needsRevalidation = true),
            KnowledgeEntry("Production", KnowledgeKind.UNRESOLVED, "Production buildings make goods from materials",
                "Expected from public game knowledge, not observed yet: buildings produce goods in queues; finished goods must be collected.",
                confidence = Confidence.LOW, source = KnowledgeSource.IMPORTED, needsRevalidation = true),
        ),
    )

    private val entries = listOf(cookieRunKingdom).associateBy { it.packageName }

    fun entryFor(packageName: String): Entry? = entries[packageName]

    /** Adds catalog seed knowledge to a newly created profile. */
    fun seed(p: GameProfile): GameProfile {
        val e = entryFor(p.packageName) ?: return p
        var kb = p.knowledge
        e.seedKnowledge.forEach { kb = kb.record(it.copy(gameVersion = null)) }
        return p.copy(name = if (p.name == p.packageName) e.name else p.name, category = e.category, knowledge = kb,
            limitations = (p.limitations + "Competitive PvP (Kingdom Arena) is never played by Ciel.").distinct())
    }
}

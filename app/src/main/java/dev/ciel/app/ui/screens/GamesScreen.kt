@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ciel.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.TaskSpec
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.Spacing
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.game.AutomationPolicy
import dev.ciel.game.Confidence
import dev.ciel.game.GameCatalog
import dev.ciel.game.GameProfile
import dev.ciel.game.KnowledgeEntry
import dev.ciel.game.PremiumRule
import dev.ciel.game.ProductionRule
import dev.ciel.game.SpendingPolicy
import dev.ciel.game.UpgradeRule
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class QuickObjective(val label: String, val objective: String)

private val quickObjectives = listOf(
    QuickObjective("Explore & learn", "Explore the game and learn how everything works. Report what you found."),
    QuickObjective("Daily quests", "Complete my daily quests and claim the rewards."),
    QuickObjective("Collect rewards", "Collect all my available rewards and completed production."),
    QuickObjective("Check buildings", "Check which buildings need attention and tell me what needs to be done."),
    QuickObjective("Upgrade buildings", "Upgrade my buildings where it makes sense."),
    QuickObjective("Production", "Collect finished production and start useful new production."),
    QuickObjective("Improve kingdom", "Improve my kingdom progression."),
)

/**
 * Games: screen-reading switches, then one card per game with automation permission, quick
 * objectives, spending rules and the learned knowledge (searchable, inspectable, clearable).
 */
@Composable
fun GamesScreen() {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val profiles by remember { graph.gameProfiles.observe() }.collectAsStateWithLifecycle(emptyList<GameProfile>())
    var crkInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        crkInstalled = runCatching { graph.context.packageManager.getPackageInfo(GameCatalog.COOKIE_RUN_KINGDOM, 0); true }.getOrDefault(false)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        item {
            CielPanel(title = "Game Master") {
                Text("Ciel plays only games you allow, single-player only. She never solves CAPTCHAs, bypasses anti-cheat, plays competitive PvP, " +
                    "or spends money or premium currency without your approval.", color = CielColors.Muted)
            }
        }
        item {
            CielPanel(title = "How Ciel sees games") {
                Text("Most games draw everything themselves, so Android cannot tell Ciel what is on screen.", color = CielColors.Muted)
                SwitchRow("Read game text on this phone", "Offline text recognition. The image never leaves your phone.", settings.gameScreenReading) { v ->
                    scope.launch { graph.settings.update { it.copy(gameScreenReading = v) } }
                }
                SwitchRow("Show game screens to the AI model", "Sends a screenshot to your vision model at each step. Needed for icons without text. Android 11+.", settings.allowScreenshots) { v ->
                    scope.launch { graph.settings.update { it.copy(allowScreenshots = v) } }
                }
                if (!settings.gameScreenReading && !settings.allowScreenshots) Text("Notice. With both off, Ciel cannot see games and game tasks will stop at once.", color = CielColors.Amber)
            }
        }
        if (crkInstalled && profiles.none { it.packageName == GameCatalog.COOKIE_RUN_KINGDOM }) item {
            CielPanel(title = "Cookie Run: Kingdom found") {
                Text("Set up a profile so Ciel can learn the game. You'll review automation permission next.", color = CielColors.Muted)
                TextButton(onClick = { scope.launch { graph.gameEngine.profileFor(GameCatalog.COOKIE_RUN_KINGDOM) } }) { Text("Set up") }
            }
        }
        if (profiles.isEmpty() && !crkInstalled) item { Text("No games recognised yet. Ask \"Ciel, what game is this?\" while a game is open.", color = CielColors.Muted) }
        items(profiles, key = { it.packageName }) { p -> GameCard(p) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = CielColors.Text, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun GameCard(p: GameProfile) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(p.packageName) { mutableStateOf<String?>(null) }
    fun save(t: (GameProfile) -> GameProfile) = scope.launch { graph.gameProfiles.save(t(p).copy(updatedAt = System.currentTimeMillis())) }
    CielPanel(Modifier.animateContentSize(tween(240)), title = p.name) {
        Text("${p.packageName} · v${p.versionName ?: "?"}" + if (p.interfaceChangeSuspected) " · updated: Ciel will re-check what she learned" else "",
            color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        Text("Automation: ${policyLabel(p.policy)}", color = if (p.policy == AutomationPolicy.ALLOWED_SINGLE_PLAYER) CielColors.Green else CielColors.Amber)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutomationPolicy.values().filter { it != AutomationPolicy.UNREVIEWED }.forEach { pol ->
                FilterChip(selected = p.policy == pol, onClick = { save { it.copy(policy = pol) } }, label = { Text(policyLabel(pol)) })
            }
        }
        val allowed = p.policy == AutomationPolicy.ALLOWED_SINGLE_PLAYER || p.policy == AutomationPolicy.MENUS_ONLY
        androidx.compose.material3.Button(onClick = { graph.navigate("gamedash/${p.packageName}") }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
            Text("Open gameplay manager")
        }
        Text("Or ask Ciel to", color = CielColors.Cyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quickObjectives.forEach { q ->
                AssistChip(enabled = allowed, onClick = {
                    graph.taskLauncher.launch(TaskSpec(q.label, q.objective, p.packageName, p.name))
                    graph.navigate("tasks")
                }, label = { Text(q.label) })
            }
        }
        if (!allowed) Text("Allow automation above first.", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("rules" to "Spending rules", "knowledge" to "What Ciel learned (${p.knowledge.entries.size})").forEach { (k, l) ->
                FilterChip(selected = section == k, onClick = { section = if (section == k) null else k }, label = { Text(l) })
            }
        }
        when (section) {
            "rules" -> SpendingEditor(p.spending) { sp -> save { it.copy(spending = sp) } }
            "knowledge" -> KnowledgeView(p) { kb -> save { it.copy(knowledge = kb) } }
        }
        if (p.strategies.isNotEmpty()) Text("Verified workflows: " + p.strategies.take(5).joinToString { "${it.objective.take(40)} (${it.successCount}✓/${it.failureCount}✗)" },
            color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        var confirmDelete by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete profile", color = CielColors.Danger) }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${p.name}'s profile?") },
            text = { Text("Everything Ciel learned about this game and its permission are removed.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { graph.gameProfiles.delete(p.packageName) } }) { Text("Delete", color = CielColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SpendingEditor(policy: SpendingPolicy, onChange: (SpendingPolicy) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Premium currency", color = CielColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PremiumRule.values().forEach { r -> FilterChip(selected = policy.premium == r, onClick = { onChange(policy.copy(premium = r)) }, label = { Text(r.label) }) }
        }
        Text("Building upgrades", color = CielColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UpgradeRule.values().forEach { r -> FilterChip(selected = policy.upgrades == r, onClick = { onChange(policy.copy(upgrades = r)) }, label = { Text(r.label) }) }
        }
        if (policy.upgrades == UpgradeRule.AUTO_SELECTED) {
            var text by remember(policy.selectedBuildings) { mutableStateOf(policy.selectedBuildings.joinToString(", ")) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Buildings to upgrade (comma-separated)") }, maxLines = 3)
            TextButton(onClick = { onChange(policy.copy(selectedBuildings = text.split(',').map { it.trim() }.filter { it.isNotEmpty() })) }) { Text("Save list") }
        }
        Text("Production", color = CielColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProductionRule.values().forEach { r -> FilterChip(selected = policy.production == r, onClick = { onChange(policy.copy(production = r)) }, label = { Text(r.label) }) }
        }
        SwitchRow("Claim free rewards automatically", "Off: Ciel asks first.", policy.autoClaimRewards) { onChange(policy.copy(autoClaimRewards = it)) }
        var reserve by remember(policy.reservePercent) { mutableStateOf(policy.reservePercent.toFloat()) }
        Text("Keep in reserve: ${reserve.toInt()}% of each resource", color = CielColors.Text)
        Slider(reserve, { reserve = it }, valueRange = 0f..90f, steps = 8, onValueChangeFinished = { onChange(policy.copy(reservePercent = reserve.toInt())) })
        Text("Real-money purchases always need your explicit approval, whatever these rules say.", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun KnowledgeView(p: GameProfile, onChange: (dev.ciel.game.GameKnowledgeBase) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val list = if (query.isBlank()) p.knowledge.entries.sortedWith(compareBy<KnowledgeEntry> { it.system }.thenByDescending { it.confidence }) else p.knowledge.search(query, 40)
    Column(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.knowledge.entries.isEmpty()) {
            Text("Nothing learned yet. Ask Ciel to \"Explore & learn\" — she opens menus, reads them and records what she finds.", color = CielColors.Muted)
            return@Column
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search (e.g. quests, production)") })
        list.take(60).forEach { e ->
            Column(Modifier.fillMaxWidth().background(CielColors.Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.system.uppercase(), color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    ConfidenceBadge(e.confidence)
                }
                Text(e.title, color = CielColors.Text, style = MaterialTheme.typography.titleMedium)
                Text(e.detail, color = CielColors.Text, style = MaterialTheme.typography.bodyMedium)
                if (e.howToReach.isNotEmpty()) Text("Route: " + e.howToReach.joinToString(" → "), color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        "seen ${e.seenCount}×", "failed ${e.failCount}×".takeIf { e.failCount > 0 },
                        "verified " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(e.lastVerified)),
                        "needs re-checking".takeIf { e.needsRevalidation }, e.source.name.lowercase(),
                    ).joinToString(" · "),
                    color = if (e.needsRevalidation) CielColors.Amber else CielColors.Muted, style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { onChange(p.knowledge.remove(e.key)) }) { Text("Forget this", color = CielColors.Danger) }
            }
        }
        TextButton(onClick = { confirmClear = true }) { Text("Clear everything Ciel learned", color = CielColors.Danger) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear game knowledge?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Ciel forgets the routes, menus and mechanics she learned for ${p.name}. Permission and spending rules stay.") } },
        confirmButton = { TextButton(onClick = { confirmClear = false; onChange(dev.ciel.game.GameKnowledgeBase()) }) { Text("Clear", color = CielColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun ConfidenceBadge(c: Confidence) {
    val color = when (c) { Confidence.HIGH -> CielColors.Green; Confidence.MEDIUM -> CielColors.Cyan; Confidence.LOW -> CielColors.Amber }
    Box(Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(c.name.lowercase() + " confidence", color = color, style = MaterialTheme.typography.labelSmall)
    }
}

private fun policyLabel(p: AutomationPolicy) = when (p) {
    AutomationPolicy.UNREVIEWED -> "not reviewed (off)"
    AutomationPolicy.ALLOWED_SINGLE_PLAYER -> "allowed (single-player)"
    AutomationPolicy.MENUS_ONLY -> "menus & rewards only"
    AutomationPolicy.PROHIBITED -> "prohibited"
}

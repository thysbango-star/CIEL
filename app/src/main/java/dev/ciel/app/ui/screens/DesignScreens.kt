@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ciel.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import dev.ciel.app.ui.components.ScaledPreview
import dev.ciel.app.ui.components.Spacing
import dev.ciel.app.ui.components.SystemNotice
import dev.ciel.app.ui.home.Bubble
import dev.ciel.app.ui.home.InputBar
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.data.MessageEntity
import dev.ciel.app.design.AccentColor
import dev.ciel.app.design.AnimationLevel
import dev.ciel.app.design.Appearance
import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.DesignPalette
import dev.ciel.app.design.FloatingSize
import dev.ciel.app.design.ThemeStyle
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.CielVisual
import dev.ciel.app.ui.components.ThemeBackground
import dev.ciel.app.ui.home.HomeActions
import dev.ciel.app.ui.home.HomeLayout
import dev.ciel.app.ui.home.HomeModel
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.CielLook
import dev.ciel.app.ui.theme.CielTheme
import dev.ciel.engine.CielState
import dev.ciel.task.TaskState
import dev.ciel.task.TaskStatus
import kotlinx.coroutines.launch

private enum class PreviewKind(val label: String) { HOME("Home"), CHAT("Chat"), FLOATING("Floating"), VOICE("Voice") }

private val sampleModel = HomeModel(
    state = CielState.IDLE, stateLabel = null, level = 0f, partial = null, listenError = null,
    messages = listOf(
        MessageEntity(1, "p", "user", "Ciel, what's the plan for tomorrow?"),
        MessageEntity(2, "p", "ciel", "Answer. Rain after 14:00 — I'd move your walk to the morning, Master."),
    ),
    streaming = "", activity = emptyList(), online = true, providerLine = "Gemini · fast", connected = true,
    activeTasks = emptyList(),
    recentTasks = listOf(TaskState("t1", "Collect daily rewards", "game", TaskStatus.COMPLETED, verified = true)),
    heyCielOn = true, wakeStatus = "Listening", lastReply = "“I am here, Master.”",
)

private val noActions = HomeActions({}, { _, _, _ -> }, {}, {}, {}, {}, {})

/** Live render of an appearance at phone size, scaled to fit. Touches never reach the preview. */
@Composable
private fun AppearancePreview(appearance: Appearance, kind: PreviewKind, modifier: Modifier, animated: Boolean = false) {
    val look = CielLook(appearance.copy(animation = if (animated) appearance.animation else AnimationLevel.OFF), DesignPalette.resolve(appearance))
    Box(modifier) {
        ScaledPreview(Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp)) {
            CielTheme(look) {
                ThemeBackground(Modifier.fillMaxSize()) {
                    when (kind) {
                        PreviewKind.HOME -> HomeLayout(appearance.design, sampleModel, noActions)
                        PreviewKind.CHAT -> ChatPreview()
                        PreviewKind.VOICE -> VoicePreview()
                        PreviewKind.FLOATING -> FloatingPreview()
                    }
                }
            }
        }
        // Absorbs touches so the preview's buttons are never triggered.
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) {})
    }
}

@Composable
private fun ChatPreview() {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CielVisual(CielState.SPEAKING, 0f, Modifier.size(56.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("Ciel", style = MaterialTheme.typography.titleMedium, color = CielColors.Text)
                Text("Online · Gemini", style = MaterialTheme.typography.labelSmall, color = CielColors.Green)
            }
        }
        SystemNotice("Skill", "Research activated", CielColors.Cyan)
        sampleModel.messages.forEach { Bubble(it) }
        Bubble(MessageEntity(3, "p", "user", "Remind me at 8, please."))
        SystemNotice("Notice", "Reminder set for 08:00", CielColors.Green)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Approve") }
            OutlinedButton(onClick = {}) { Text("Decline") }
        }
        InputBar(noActions)
    }
}

@Composable
private fun VoicePreview() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CielVisual(CielState.LISTENING, 0.7f, Modifier.size(260.dp))
        Spacer(Modifier.height(16.dp))
        Text("LISTENING", style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan)
        Text("“Ciel, open Minecraft”", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
    }
}

@Composable
private fun FloatingPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF2B2F36))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(7) { Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF3A3F48), RoundedCornerShape(10.dp))) }
        }
        // Activity notch, as shown above a game while Ciel works.
        Row(Modifier.align(Alignment.TopCenter).padding(top = 18.dp).background(CielColors.Deep.copy(alpha = 0.85f), RoundedCornerShape(50))
            .border(1.dp, CielColors.PanelBorder, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CielVisual(CielState.EXECUTING, 0f, Modifier.size(22.dp), progress = 0.45f)
            Text("  Completing quests…  4/9", style = MaterialTheme.typography.labelSmall, color = CielColors.Text)
        }
        Column(Modifier.padding(start = 16.dp, top = 300.dp, end = 16.dp)) {
            CielVisual(CielState.IDLE, 0f, Modifier.size(70.dp))
            CielPanel { Text("Ready, Master. Double-tap me to stop everything.", color = CielColors.Text) }
        }
    }
}

/**
 * Theme gallery (8 themes) and layouts (4). An adaptive grid: as many columns as fit
 * (≥150 dp each), so every card is fully visible and nothing extends past the screen.
 */
@Composable
fun DesignPickerScreen(onChosen: (() -> Unit)? = null) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val current = settings.appearance
    var previewing by remember { mutableStateOf<Appearance?>(null) }
    fun set(a: Appearance) { scope.launch { graph.settings.update { it.copy(appearance = a) } } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Choose Ciel's look", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
                Text("Tap a card to apply it, or Preview to see it full size first.", color = CielColors.Muted)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Theme", style = MaterialTheme.typography.titleMedium, color = CielColors.Cyan) }
        items(ThemeStyle.values().toList(), key = { "t" + it.name }) { t ->
            val a = current.copy(theme = t, accent = null)
            ChoiceCard(
                title = t.title, description = t.tagline, selected = current.theme == t,
                swatches = DesignPalette.forTheme(t).let { listOf(it.background, it.background2, it.accent, it.accent2) },
                preview = { AppearancePreview(a, PreviewKind.HOME, it) },
                onApply = { set(a) }, onPreview = { previewing = a },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Layout", style = MaterialTheme.typography.titleMedium, color = CielColors.Cyan, modifier = Modifier.padding(top = Spacing.s)) }
        items(CielDesign.values().toList(), key = { "d" + it.name }) { d ->
            val a = current.copy(design = d)
            ChoiceCard(
                title = d.title, description = d.tagline, selected = current.design == d, swatches = emptyList(),
                preview = { AppearancePreview(a, PreviewKind.HOME, it) },
                onApply = { set(a) }, onPreview = { previewing = a },
            )
        }
        if (onChosen != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Button(onClick = onChosen, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) { Text("Continue") }
        }
    }
    previewing?.let { p ->
        ThemePreviewDialog(p, isCurrent = p.theme == current.theme && p.design == current.design,
            onApply = { set(p); previewing = null }, onDismiss = { previewing = null })
    }
}

@Composable
private fun ChoiceCard(
    title: String, description: String, selected: Boolean, swatches: List<Int>,
    preview: @Composable (Modifier) -> Unit, onApply: () -> Unit, onPreview: () -> Unit,
) {
    val border by androidx.compose.animation.animateColorAsState(if (selected) CielColors.Cyan else CielColors.PanelBorder, tween(250), label = "border")
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().background(CielColors.Panel, shape).border(if (selected) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onApply).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            preview(Modifier.fillMaxWidth().aspectRatio(0.5f))
            androidx.compose.animation.AnimatedVisibility(selected, Modifier.align(Alignment.TopEnd).padding(6.dp),
                enter = fadeIn(tween(200)) + scaleIn(tween(200), 0.6f), exit = fadeOut(tween(150)) + scaleOut(tween(150), 0.6f)) {
                Box(Modifier.size(26.dp).background(CielColors.Cyan, CircleShape), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, "Selected", tint = CielColors.Void, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) CielColors.Cyan else CielColors.Text)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = CielColors.Muted)
        if (swatches.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            swatches.forEach { Box(Modifier.size(14.dp).background(Color(it), CircleShape).border(0.5.dp, CielColors.PanelBorder, CircleShape)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPreview) { Text("Preview") }
            TextButton(onClick = onApply, enabled = !selected) { Text(if (selected) "Applied" else "Apply") }
        }
    }
}

/** Full-screen preview of a theme/layout with tabs; closing keeps the current selection. */
@Composable
private fun ThemePreviewDialog(a: Appearance, isCurrent: Boolean, onApply: () -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(PreviewKind.HOME) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(CielColors.Void.copy(alpha = 0.96f)).safeDrawingPadding().padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.theme.title, style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
                    Text("${a.design.title} layout · ${a.theme.tagline}", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewKind.values().forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
            Crossfade(kind, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "preview") { k ->
                AppearancePreview(a, k, Modifier.fillMaxSize(), animated = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Back") }
                Button(onClick = onApply, enabled = !isCurrent, modifier = Modifier.weight(1f)) { Text(if (isCurrent) "In use" else "Apply") }
            }
        }
    }
}

/** Appearance: theme, layout and fine-tuning. */
@Composable
fun AppearanceScreen(onPickDesign: () -> Unit) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val a = s.appearance
    fun set(t: (Appearance) -> Appearance) { scope.launch { graph.settings.update { it.copy(appearance = t(it.appearance)) } } }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CielPanel(title = "Theme & layout") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppearancePreview(a, PreviewKind.HOME, Modifier.width(96.dp).aspectRatio(0.5f))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(a.theme.title, style = MaterialTheme.typography.titleMedium, color = CielColors.Text)
                        Text("${a.design.title} layout", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onPickDesign) { Text("Browse themes") }
                    }
                }
            }
        }
        item {
            CielPanel(title = "Crystal & effects") {
                LabeledSlider("Glow", a.glow, 0f..1f) { v -> set { it.copy(glow = v) } }
                LabeledSlider("Particles", a.particles, 0f..1f) { v -> set { it.copy(particles = v) } }
                LabeledSlider("Ring speed", a.ringSpeed, 0.25f..2f) { v -> set { it.copy(ringSpeed = v) } }
                LabeledSlider("Panel transparency", a.transparency, 0f..1f) { v -> set { it.copy(transparency = v) } }
            }
        }
        item {
            CielPanel(title = "Accent colour") {
                FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(Color(DesignPalette.forTheme(a.theme).accent), a.accent == null) { set { it.copy(accent = null) } }
                    AccentColor.values().forEach { c -> Swatch(Color(c.argb), a.accent == c) { set { it.copy(accent = c) } } }
                }
            }
        }
        item {
            CielPanel(title = "Animation") {
                FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimationLevel.values().forEach { l -> FilterChip(selected = a.animation == l, onClick = { set { it.copy(animation = l) } }, label = { Text(l.label) }) }
                }
                Text("Subtle or Off saves battery. Backgrounds are drawn once and never animate.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            CielPanel(title = "Floating assistant size") {
                FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FloatingSize.values().forEach { f -> FilterChip(selected = a.floatingSize == f, onClick = { set { it.copy(floatingSize = f) } }, label = { Text(f.label) }) }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    // Local state keeps dragging smooth; the setting is saved when the finger lifts.
    var v by remember(value) { mutableStateOf(value) }
    Text("$label  ${"%.2f".format(v)}", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
    Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onChange(v) })
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val size: Dp = if (selected) 34.dp else 28.dp
    Box(Modifier.size(size).background(color, CircleShape).border(if (selected) 3.dp else 0.dp, CielColors.Text, CircleShape).clickable(onClick = onClick))
}

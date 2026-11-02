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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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

private enum class PreviewKind(val label: String) { HOME("Home"), VOICE("Voice"), FLOATING("Floating") }

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

/** A live, scaled-down render. Touches select instead of reaching the preview. */
@Composable
private fun Preview(appearance: Appearance, kind: PreviewKind, scale: Float, onTap: () -> Unit) {
    val look = CielLook(appearance.copy(animation = AnimationLevel.SUBTLE), DesignPalette.resolve(appearance))
    val w = 360.dp; val h = 720.dp
    Box(Modifier.size(w * scale, h * scale).clip(RoundedCornerShape(16.dp)).border(1.dp, Color(look.palette.border), RoundedCornerShape(16.dp))) {
        Box(Modifier.requiredSize(w, h).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
            CielTheme(look) {
                ThemeBackground(Modifier.fillMaxSize()) {
                    when (kind) {
                        PreviewKind.HOME -> HomeLayout(appearance.design, sampleModel, noActions)
                        PreviewKind.VOICE -> VoicePreview()
                        PreviewKind.FLOATING -> FloatingPreview()
                    }
                }
            }
        }
        Box(Modifier.size(w * scale, h * scale).clickable(remember { MutableInteractionSource() }, null, onClick = onTap))
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
            repeat(6) { Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF3A3F48), RoundedCornerShape(10.dp))) }
        }
        Column(Modifier.padding(start = 16.dp, top = 260.dp)) {
            CielVisual(CielState.IDLE, 0f, Modifier.size(70.dp))
            Box(Modifier.width(260.dp)) { CielPanel { Text("Ready, Master. Double-tap me to stop everything.", color = CielColors.Text) } }
        }
    }
}

/** Theme gallery (8 themes) and layouts (4), with live previews. */
@Composable
fun DesignPickerScreen(onChosen: (() -> Unit)? = null) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val current = settings.appearance
    var kind by remember { mutableStateOf(PreviewKind.HOME) }
    fun set(a: Appearance) { scope.launch { graph.settings.update { it.copy(appearance = a) } } }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Choose Ciel's look", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
            Text("Tap a preview to apply it. Change it any time in Settings → Appearance.", color = CielColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                PreviewKind.values().forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
        }
        item { Text("Theme", style = MaterialTheme.typography.titleMedium, color = CielColors.Cyan) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ThemeStyle.values().toList(), key = { it.name }) { t ->
                    val selected = current.theme == t
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Preview(current.copy(theme = t, accent = null), kind, 0.42f) { set(current.copy(theme = t, accent = null)) }
                        Text((if (selected) "✓ " else "") + t.title, color = if (selected) CielColors.Cyan else CielColors.Text, style = MaterialTheme.typography.titleMedium)
                        Text(t.tagline, color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Text("Layout", style = MaterialTheme.typography.titleMedium, color = CielColors.Cyan) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(CielDesign.values().toList(), key = { it.name }) { d ->
                    val selected = current.design == d
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(160.dp)) {
                        Preview(current.copy(design = d), kind, 0.42f) { set(current.copy(design = d)) }
                        Text((if (selected) "✓ " else "") + d.title, color = if (selected) CielColors.Cyan else CielColors.Text, style = MaterialTheme.typography.titleMedium)
                        Text(d.tagline, color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (onChosen != null) item { Button(onClick = onChosen, modifier = Modifier.fillMaxWidth()) { Text("Continue") } }
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
                    Preview(a, PreviewKind.HOME, 0.3f, onPickDesign)
                    Column(Modifier.padding(start = 12.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(Color(DesignPalette.forTheme(a.theme).accent), a.accent == null) { set { it.copy(accent = null) } }
                    AccentColor.values().forEach { c -> Swatch(Color(c.argb), a.accent == c) { set { it.copy(accent = c) } } }
                }
            }
        }
        item {
            CielPanel(title = "Animation") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimationLevel.values().forEach { l -> FilterChip(selected = a.animation == l, onClick = { set { it.copy(animation = l) } }, label = { Text(l.label) }) }
                }
                Text("Subtle or Off saves battery. Backgrounds are drawn once and never animate.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            CielPanel(title = "Floating assistant size") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

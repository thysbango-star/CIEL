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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.DesignPalette
import dev.ciel.app.design.ThemeMode
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.CielVisual
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
        MessageEntity(1, "p", "user", "Ciel, what's the weather plan for tomorrow?"),
        MessageEntity(2, "p", "ciel", "Answer. Rain after 14:00 — I'd move your walk to the morning, Master."),
    ),
    streaming = "", activity = emptyList(), online = true, providerLine = "Gemini · fast", connected = true,
    activeTasks = emptyList(),
    recentTasks = listOf(TaskState("t1", "Collect daily rewards", "game", TaskStatus.COMPLETED, verified = true)),
    heyCielOn = true, wakeStatus = "Listening", lastReply = "Rain after 14:00 — I'd move your walk to the morning, Master.",
)

private val noActions = HomeActions({}, { _, _, _ -> }, {}, {}, {}, {}, {})

/** A live, scaled-down render of a design. Touches select the design instead of reaching the preview. */
@Composable
private fun DesignPreview(design: CielDesign, kind: PreviewKind, scale: Float, onTap: () -> Unit) {
    val look = CielLook(design, DesignPalette.of(design), AnimationLevel.SUBTLE)
    val w = 360.dp; val h = 720.dp
    Box(Modifier.size(w * scale, h * scale).clip(RoundedCornerShape(14.dp)).border(1.dp, Color(look.palette.border), RoundedCornerShape(14.dp))) {
        Box(Modifier.requiredSize(w, h).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            .background(Color(look.palette.background))) {
            CielTheme(look) {
                when (kind) {
                    PreviewKind.HOME -> HomeLayout(design, sampleModel, noActions)
                    PreviewKind.VOICE -> VoicePreview()
                    PreviewKind.FLOATING -> FloatingPreview()
                }
            }
        }
        Box(Modifier.size(w * scale, h * scale).clickable(remember { MutableInteractionSource() }, null, onClick = onTap))
    }
}

@Composable
private fun VoicePreview() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CielVisual(CielState.LISTENING, 0.7f, Modifier.size(240.dp))
        Spacer(Modifier.height(16.dp))
        Text("LISTENING", style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan)
        Text("“Ciel, open Minecraft”", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
        Spacer(Modifier.height(40.dp))
        CielPanel { Text("Hey Ciel works from any app: the panel appears at the bottom of the screen.", color = CielColors.Muted) }
    }
}

@Composable
private fun FloatingPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF2B2F36))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF3A3F48), RoundedCornerShape(10.dp))) }
        }
        Column(Modifier.padding(start = 16.dp, top = 260.dp)) {
            Box(Modifier.size(64.dp).background(CielColors.Void, CircleShape)) { CielVisual(CielState.IDLE, 0f, Modifier.size(64.dp)) }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(260.dp)) {
                CielPanel { Text("Ready, Master. Double-tap me to stop everything.", color = CielColors.Text) }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            CielPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CielVisual(CielState.SPEAKING, 0.5f, Modifier.size(48.dp))
                    Text("  Opening Minecraft…", color = CielColors.Text)
                }
            }
        }
    }
}

/** Interface selection: preview all four designs (home, voice, floating) and pick one. */
@Composable
fun DesignPickerScreen(onChosen: (() -> Unit)? = null) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Choose Ciel's interface", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
            Text("Each design has its own layout and visual style. You can change it any time in Settings → Appearance.", color = CielColors.Muted)
        }
        items(CielDesign.values().toList(), key = { it.name }) { d ->
            var kind by remember { mutableStateOf(PreviewKind.HOME) }
            val selected = settings.appearance.design == d
            CielPanel(title = d.title + if (d == CielDesign.HYBRID) " · recommended" else "", accent = if (selected) CielColors.Cyan else CielColors.PanelBorder) {
                Text(d.tagline, color = CielColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    PreviewKind.values().forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                }
                val choose = { scope.launch { graph.settings.update { it.copy(appearance = it.appearance.copy(design = d)) } }; onChosen?.invoke(); Unit }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DesignPreview(d, kind, 0.62f, choose) }
                Spacer(Modifier.height(8.dp))
                if (selected) OutlinedButton(onClick = { onChosen?.invoke() }, modifier = Modifier.fillMaxWidth()) { Text("✓ Selected") }
                else Button(onClick = choose, modifier = Modifier.fillMaxWidth()) { Text("Use ${d.title}") }
            }
        }
    }
}

/** Appearance: design, accent colour, animation intensity, light/dark. */
@Composable
fun AppearanceScreen(onPickDesign: () -> Unit) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val a = s.appearance
    fun set(t: (dev.ciel.app.design.Appearance) -> dev.ciel.app.design.Appearance) { scope.launch { graph.settings.update { it.copy(appearance = t(it.appearance)) } } }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CielPanel(title = "Interface design") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DesignPreview(a.design, PreviewKind.HOME, 0.3f, onPickDesign)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(a.design.title, style = MaterialTheme.typography.titleMedium, color = CielColors.Text)
                        Text(a.design.tagline, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onPickDesign) { Text("Change design") }
                    }
                }
            }
        }
        item {
            CielPanel(title = "Accent colour") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(Color(DesignPalette.of(a.design).accent), a.accent == null, "Default") { set { it.copy(accent = null) } }
                    AccentColor.values().forEach { c -> Swatch(Color(c.argb), a.accent == c, c.label) { set { it.copy(accent = c) } } }
                }
            }
        }
        item {
            CielPanel(title = "Animation") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimationLevel.values().forEach { l -> FilterChip(selected = a.animation == l, onClick = { set { it.copy(animation = l) } }, label = { Text(l.label) }) }
                }
                Text("Subtle or Off saves battery.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            CielPanel(title = "Light / dark") {
                if (!a.design.supportsLight) Text("${a.design.title} is a dark design. Great Sage and Hybrid support light mode.", color = CielColors.Muted)
                else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.values().forEach { t -> FilterChip(selected = a.themeMode == t, onClick = { set { it.copy(themeMode = t) } }, label = { Text(t.label) }) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val size: Dp = if (selected) 34.dp else 28.dp
    Box(Modifier.size(size).background(color, CircleShape).border(if (selected) 3.dp else 0.dp, CielColors.Text, CircleShape).clickable(onClick = onClick))
}

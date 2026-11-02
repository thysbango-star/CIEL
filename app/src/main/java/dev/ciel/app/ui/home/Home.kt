package dev.ciel.app.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.design.CielDesign
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.CielVisual
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.LocalLook
import dev.ciel.app.voice.ListenState
import dev.ciel.app.voice.WakeWordService
import dev.ciel.engine.CielState

/** Collects live state and renders the layout of the selected design. */
@Composable
fun HomeScreen(actions: HomeActions) {
    val graph = CielApplication.graph
    val conv = graph.conversation
    val state by conv.state.collectAsStateWithLifecycle()
    val label by conv.stateLabel.collectAsStateWithLifecycle()
    val listen by graph.speech.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val tasks by graph.tasks.tasks.collectAsStateWithLifecycle()
    val online by graph.network.online.collectAsStateWithLifecycle()
    val provider by conv.provider.collectAsStateWithLifecycle()
    val messages by conv.messages.collectAsStateWithLifecycle(emptyList())
    val streaming by conv.streaming.collectAsStateWithLifecycle()
    val activity by conv.activity.collectAsStateWithLifecycle()
    val last by conv.lastReply.collectAsStateWithLifecycle()
    val wake by WakeWordService.status.collectAsStateWithLifecycle()
    val connected = settings.providers.any { it.enabled }
    val model = HomeModel(
        state = state, stateLabel = label,
        level = (listen as? ListenState.Listening)?.level ?: 0f,
        partial = (listen as? ListenState.Listening)?.partial?.takeIf { it.isNotBlank() },
        listenError = (listen as? ListenState.Error)?.message,
        messages = messages, streaming = streaming, activity = activity,
        online = online,
        providerLine = provider ?: settings.providers.firstOrNull { it.enabled }?.displayName,
        connected = connected,
        activeTasks = tasks.filter { it.isActive },
        recentTasks = tasks.filter { !it.isActive }.takeLast(3).reversed(),
        heyCielOn = settings.wakeWordEnabled,
        wakeStatus = if (settings.wakeWordEnabled) wake else "Off",
        lastReply = last ?: messages.lastOrNull { it.role == "ciel" }?.text,
    )
    HomeLayout(LocalLook.current.design, model, actions)
}

@Composable
fun HomeLayout(design: CielDesign, m: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    when (design) {
        CielDesign.CIEL -> CielHome(m, actions, modifier)
        CielDesign.RAPHAEL -> RaphaelHome(m, actions, modifier)
        CielDesign.GREAT_SAGE -> SageHome(m, actions, modifier)
        CielDesign.HYBRID -> HybridHome(m, actions, modifier)
    }
}

@Composable
private fun landscape() = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
private fun StopBar(m: HomeModel, actions: HomeActions) {
    if (m.activeTasks.isEmpty()) return
    Button(onClick = actions.onStop, colors = ButtonDefaults.buttonColors(containerColor = CielColors.Danger)) {
        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("EMERGENCY STOP (${m.activeTasks.size})")
    }
}

@Composable
private fun Visual(m: HomeModel, actions: HomeActions, size: androidx.compose.ui.unit.Dp) {
    CielVisual(
        m.state, m.level,
        Modifier.size(size).clickable(onClick = actions.onTapVisual)
            .semantics { contentDescription = "Ciel, ${stateText(m.state)}. Tap to speak." },
        offline = !m.online,
        progress = m.activeTasks.firstOrNull()?.progress,
    )
}

// ------------------------------------------------------------------------------------------
// A — Ciel: centred magic circle, floating conversation, particles. Immersive and anime-first.
// ------------------------------------------------------------------------------------------
@Composable
fun CielHome(m: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (landscape()) {
            Row(Modifier.fillMaxSize().padding(8.dp)) {
                Column(Modifier.width(260.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Visual(m, actions, 220.dp)
                    Text(stateText(m.state).uppercase(), style = MaterialTheme.typography.labelSmall, color = CielColors.forState(m.state))
                    StopBar(m, actions)
                }
                Column(Modifier.weight(1f).imePadding()) {
                    Notices(m, actions)
                    MessageList(m, Modifier.weight(1f))
                    InputBar(actions)
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Notices(m, actions, max = 2)
            Visual(m, actions, 240.dp)
            Text("« ${stateText(m.state)} »", style = MaterialTheme.typography.titleMedium, color = CielColors.forState(m.state))
            m.partial?.let { Text("“$it”", color = CielColors.Ice, textAlign = TextAlign.Center) }
            StopBar(m, actions)
            MessageList(m, Modifier.weight(1f).fillMaxWidth(), emptyText = "Say \"Hey Ciel\", tap the circle, or type below.")
            InputBar(actions)
        }
    }
}

// ------------------------------------------------------------------------------------------
// B — Raphael: structured system dashboard. Geometric core + data grid + analysis log.
// ------------------------------------------------------------------------------------------
@Composable
fun RaphaelHome(m: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    @Composable fun Stat(k: String, v: String, mod: Modifier) {
        Column(mod.border(1.dp, CielColors.PanelBorder, RoundedCornerShape(2.dp)).padding(8.dp)) {
            Text(k, style = MaterialTheme.typography.labelSmall, color = CielColors.Muted)
            Text(v, fontFamily = FontFamily.Monospace, color = CielColors.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
    val content: @Composable (Modifier) -> Unit = { mod ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Visual(m, actions, 116.dp)
                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stat("STATE", stateText(m.state).uppercase(), Modifier.weight(1f))
                        Stat("NET", if (m.online) "ONLINE" else "OFFLINE", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stat("MODEL", m.providerLine ?: "—", Modifier.weight(1f))
                        Stat("WAKE", m.wakeStatus.uppercase(), Modifier.weight(1f))
                    }
                }
            }
            StopBar(m, actions)
            Notices(m, actions)
            CielPanel(Modifier.weight(1f), title = "Analysis log") {
                MessageList(m, Modifier.fillMaxSize(), console = true, emptyText = "> awaiting instruction")
            }
            CielPanel(title = "Operations") {
                QuickSkillRow(actions)
                Spacer(Modifier.height(6.dp))
                RecentTasks(m, actions, 2)
            }
            InputBar(actions)
        }
    }
    if (landscape()) Row(modifier.fillMaxSize().padding(8.dp).imePadding()) { content(Modifier.weight(1f)) }
    else content(modifier.fillMaxSize().padding(10.dp).imePadding())
}

// ------------------------------------------------------------------------------------------
// C — Great Sage (default): the reference layout. Crystal, status line, Ciel's line, mic.
// ------------------------------------------------------------------------------------------
@Composable
fun SageHome(m: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    var typing by remember { mutableStateOf(false) }
    val glass = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Header: title, floating-assistant crystal, settings
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Ciel", style = MaterialTheme.typography.headlineMedium, color = CielColors.Text)
                Text(LocalLook.current.theme.title, style = MaterialTheme.typography.bodyMedium, color = CielColors.Muted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.IconButton(onClick = { actions.onOpen("settings") }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, "Settings", tint = CielColors.Text)
                }
                Box(Modifier.size(46.dp).border(1.dp, CielColors.PanelBorder, androidx.compose.foundation.shape.CircleShape)
                    .clickable { actions.onOpen("floating") }, contentAlignment = Alignment.Center) {
                    CielVisual(CielState.IDLE, 0f, Modifier.size(40.dp))
                }
            }
        }
        Notices(m, actions, max = 1)
        Spacer(Modifier.weight(0.4f))
        Visual(m, actions, if (landscape()) 170.dp else 280.dp)
        Spacer(Modifier.weight(0.3f))
        // Status line
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(CielColors.forState(m.state), androidx.compose.foundation.shape.CircleShape))
            val status = when (m.state) {
                CielState.IDLE -> if (m.online) "Idle • Awaiting your command…" else "Offline • Basic commands only"
                CielState.LISTENING -> "Listening…"
                else -> stateText(m.state) + "…"
            }
            Text("  $status", style = MaterialTheme.typography.bodyMedium, color = CielColors.Muted)
        }
        Spacer(Modifier.height(10.dp))
        // Ciel's line
        val line = m.partial?.let { "“$it”" } ?: m.streaming.takeLast(220).ifBlank { null } ?: when (m.state) {
            CielState.IDLE, CielState.COMPLETED -> m.lastReply?.take(220) ?: "“I am here, Master.”"
            else -> "…"
        }
        Box(Modifier.fillMaxWidth().background(CielColors.Panel, glass).border(1.dp, CielColors.PanelBorder, glass)
            .clickable { actions.onOpen("chats") }.padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(line, style = MaterialTheme.typography.bodyLarge, color = CielColors.Text, textAlign = TextAlign.Center, maxLines = if (landscape()) 2 else 5)
        }
        Spacer(Modifier.height(14.dp))
        StopBar(m, actions)
        if (typing) InputBar(actions) else BigMic(m.state, actions.onMic)
        Spacer(Modifier.height(10.dp))
        // Bottom row: skills, tasks, memory, type
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SageIcon(androidx.compose.material.icons.Icons.Filled.GridView, "Skills") { actions.onOpen("skills") }
            SageIcon(androidx.compose.material.icons.Icons.Filled.BarChart, "Tasks") { actions.onOpen("tasks") }
            SageIcon(androidx.compose.material.icons.Icons.Filled.Inventory2, "Memory") { actions.onOpen("memory") }
            SageIcon(if (typing) androidx.compose.material.icons.Icons.Filled.Mic else androidx.compose.material.icons.Icons.Filled.Keyboard, if (typing) "Voice" else "Type") { typing = !typing }
        }
    }
}

@Composable
private fun SageIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) { androidx.compose.material3.Icon(icon, label, tint = CielColors.Text.copy(alpha = 0.8f)) }
}

// ------------------------------------------------------------------------------------------
// D — Hybrid (recommended): circle + status, quick skills, recent tasks, notices, conversation.
// ------------------------------------------------------------------------------------------
@Composable
fun HybridHome(m: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    val header: @Composable (Modifier) -> Unit = { mod ->
        Column(mod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Visual(m, actions, if (landscape()) 180.dp else 170.dp)
            Text(stateText(m.state).uppercase() + (m.stateLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = CielColors.forState(m.state))
            Text((if (m.online) "Online" else "Offline") + (m.providerLine?.let { " · $it" } ?: "") + " · Hey Ciel: ${m.wakeStatus.lowercase()}",
                style = MaterialTheme.typography.labelSmall, color = CielColors.Muted, textAlign = TextAlign.Center)
            m.partial?.let { Text("“$it”", color = CielColors.Ice, textAlign = TextAlign.Center) }
            StopBar(m, actions)
        }
    }
    if (landscape()) {
        Row(modifier.fillMaxSize().padding(8.dp)) {
            Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                header(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp)); RecentTasks(m, actions)
            }
            Column(Modifier.weight(1f).imePadding()) {
                Notices(m, actions); QuickSkillRow(actions, Modifier.padding(vertical = 6.dp))
                MessageList(m, Modifier.weight(1f)); InputBar(actions)
            }
        }
    } else Column(modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding()) {
        header(Modifier.fillMaxWidth().padding(top = 8.dp))
        Notices(m, actions, max = 2)
        QuickSkillRow(actions, Modifier.padding(vertical = 6.dp))
        RecentTasks(m, actions, 2)
        MessageList(m, Modifier.weight(1f).padding(top = 4.dp), emptyText = "Say \"Hey Ciel\" or tap the circle.")
        InputBar(actions)
    }
}

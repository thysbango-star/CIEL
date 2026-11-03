package dev.ciel.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ciel.desktop.core.DesktopGraph
import dev.ciel.desktop.core.DesktopPaths
import dev.ciel.desktop.core.FileSecretStore
import dev.ciel.desktop.ui.Backdrop
import dev.ciel.desktop.ui.C
import dev.ciel.desktop.ui.CielDesktopTheme
import dev.ciel.desktop.ui.CielTheme
import dev.ciel.desktop.ui.ChatScreen
import dev.ciel.desktop.ui.ConnectionsScreen
import dev.ciel.desktop.ui.DownloadsScreen
import dev.ciel.desktop.ui.FloatingAssistant
import dev.ciel.desktop.ui.GamingScreen
import dev.ciel.desktop.ui.HomeScreen
import dev.ciel.desktop.ui.MemoryScreen
import dev.ciel.desktop.ui.PcScreen
import dev.ciel.desktop.ui.PerfOverlay
import dev.ciel.desktop.ui.SettingsScreen
import dev.ciel.desktop.ui.StorageScreen
import dev.ciel.desktop.ui.TasksScreen
import dev.ciel.desktop.ui.stateLabel
import java.io.File

enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.AutoAwesome), CHAT("Chat", Icons.AutoMirrored.Filled.Chat), TASKS("Tasks", Icons.Filled.Checklist),
    PC("PC", Icons.Filled.Computer), STORAGE("Storage", Icons.Filled.Storage), GAMING("Gaming", Icons.Filled.SportsEsports),
    DOWNLOADS("Downloads", Icons.Filled.Download), CONNECT("Connect", Icons.Filled.Link), MEMORY("Memory", Icons.Filled.Psychology),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Secrets are encrypted with Windows DPAPI (only this Windows user can decrypt them). */
private fun secretStore(): FileSecretStore {
    val file = File(DesktopPaths.dataDir, "keys.json")
    return if (dev.ciel.windows.isWindows) FileSecretStore(file,
        { com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(it) }, { com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(it) })
    else FileSecretStore(file, { it }, { it }) // development on other systems only
}

private fun moveToTrash(f: File): Boolean = runCatching {
    java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && java.awt.Desktop.getDesktop().moveToTrash(f)
}.getOrDefault(false)

fun main() {
    val graph = DesktopGraph(secretStore(), ::moveToTrash)
    application {
        val settings by graph.settings.flow.collectAsState()
        var mainOpen by remember { mutableStateOf(true) }
        var dest by remember { mutableStateOf(Dest.HOME) }
        val icon = painterResource("ciel_core.webp")
        val theme = CielTheme.of(settings.theme)

        Tray(icon = icon, tooltip = "Ciel", onAction = { mainOpen = true }, menu = {
            Item("Open Ciel", onClick = { mainOpen = true })
            Item(if (graph.pc.gaming.isActive) "Turn off Gaming Mode" else "Gaming Mode…", onClick = { mainOpen = true; dest = Dest.GAMING })
            Item("Emergency stop", onClick = { graph.emergencyStop.trigger("tray") })
            Item("Quit", onClick = { exitApplication() })
        })

        if (mainOpen) Window(onCloseRequest = { mainOpen = false }, title = "Ciel", icon = icon,
            state = rememberWindowState(size = DpSize(1240.dp, 820.dp), position = WindowPosition(Alignment.Center))) {
            CielDesktopTheme(theme) {
                Backdrop(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(containerColor = C.panel, modifier = Modifier.fillMaxHeight()) {
                            Spacer(Modifier.size(8.dp))
                            Dest.values().forEach { d ->
                                NavigationRailItem(selected = dest == d, onClick = { dest = d }, icon = { Icon(d.icon, d.label) }, label = { Text(d.label) },
                                    colors = NavigationRailItemDefaults.colors(indicatorColor = C.accent.copy(alpha = 0.2f)))
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            TopBar(graph)
                            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                                when (dest) {
                                    Dest.HOME -> HomeScreen(graph) { dest = it }
                                    Dest.CHAT -> ChatScreen(graph)
                                    Dest.TASKS -> TasksScreen(graph)
                                    Dest.PC -> PcScreen(graph)
                                    Dest.STORAGE -> StorageScreen(graph)
                                    Dest.GAMING -> GamingScreen(graph)
                                    Dest.DOWNLOADS -> DownloadsScreen(graph)
                                    Dest.CONNECT -> ConnectionsScreen(graph)
                                    Dest.MEMORY -> MemoryScreen(graph)
                                    Dest.SETTINGS -> SettingsScreen(graph)
                                }
                            }
                        }
                    }
                    ConfirmationHost(graph)
                }
            }
        }

        if (settings.floatingAssistant) FloatingAssistant(graph, theme, onOpen = { mainOpen = true; dest = Dest.CHAT })
        if (settings.perfOverlay) PerfOverlay(graph, theme)
    }
}

@Composable
private fun TopBar(graph: DesktopGraph) {
    val state by graph.conversation.state.collectAsState()
    val provider by graph.conversation.provider.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Ciel · ${stateLabel(state)}", color = C.text)
        provider?.let { Text("   $it", color = C.muted) }
        Spacer(Modifier.weight(1f))
        Button(onClick = { graph.emergencyStop.trigger("desktop stop") }, colors = ButtonDefaults.buttonColors(containerColor = C.danger)) { Text("■ Stop") }
    }
}

/** Every consequential action waits here for your explicit decision. */
@Composable
private fun ConfirmationHost(graph: DesktopGraph) {
    val pending by graph.confirmations.pending.collectAsState()
    val p = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Notice. ${p.request.title}", color = C.amber) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(p.request.detail, color = C.text)
            }
        },
        confirmButton = { Button(onClick = { graph.confirmations.answer(p.id, true) }) { Text("Approve") } },
        dismissButton = { TextButton(onClick = { graph.confirmations.answer(p.id, false) }) { Text("Decline", color = C.danger) } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Suppress("unused") private val spacing = Arrangement.spacedBy(8.dp)

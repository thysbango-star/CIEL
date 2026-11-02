package dev.ciel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.ciel.app.CielApplication
import dev.ciel.app.ui.home.HomeActions
import dev.ciel.app.ui.home.HomeScreen
import dev.ciel.app.ui.screens.AppearanceScreen
import dev.ciel.app.ui.screens.ChatsScreen
import dev.ciel.app.ui.screens.ConnectionsScreen
import dev.ciel.app.ui.screens.DesignPickerScreen
import dev.ciel.app.ui.screens.DeveloperScreen
import dev.ciel.app.ui.screens.GamesScreen
import dev.ciel.app.ui.screens.MemoryScreen
import dev.ciel.app.ui.screens.OnboardingScreen
import dev.ciel.app.ui.screens.PermissionsScreen
import dev.ciel.app.ui.screens.PreferencesScreen
import dev.ciel.app.ui.screens.ProvidersScreen
import dev.ciel.app.ui.screens.SettingsHub
import dev.ciel.app.ui.screens.SkillsScreen
import dev.ciel.app.ui.screens.SystemHub
import dev.ciel.app.ui.screens.TasksScreen
import dev.ciel.app.ui.screens.VoiceScreen
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.CielTheme
import dev.ciel.engine.CielState

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Ciel", Icons.Filled.AutoAwesome),
    Tab("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    Tab("tasks", "Tasks", Icons.Filled.Checklist),
    Tab("connect", "Connect", Icons.Filled.Link),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun CielApp(onListen: () -> Unit) {
    val graph = CielApplication.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    CielTheme(settings.appearance) {
        if (!settings.onboardingDone) {
            Box(Modifier.fillMaxSize().background(CielColors.Void).safeDrawingPadding()) { OnboardingScreen() }
        } else MainShell(onListen)
    }
}

@Composable
private fun MainShell(onListen: () -> Unit) {
    val graph = CielApplication.graph
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val actions = HomeActions(
        onMic = onListen,
        onSend = { text, image, mode -> graph.conversation.send(text, listOfNotNull(image), mode) },
        onStop = { graph.emergencyStop.trigger("in-app stop") },
        onTapVisual = { if (graph.conversation.state.value == CielState.SPEAKING) graph.voice.stop() else onListen() },
        onConnect = { nav.go("connect") },
        onEnableHeyCiel = { nav.go("voice") },
        onOpen = { nav.go(it) },
    )
    Scaffold(
        containerColor = CielColors.Void,
        bottomBar = {
            NavigationBar(containerColor = CielColors.Deep) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { if (route != t.route) nav.navigate(t.route) { popUpTo("home"); launchSingleTop = true } },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = CielColors.Cyan.copy(alpha = 0.2f)),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(CielColors.Void).padding(padding)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(actions) }
                composable("chats") { ChatsScreen(onOpened = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }) }
                composable("tasks") { TasksScreen() }
                composable("connect") { ConnectionsScreen(onDeveloper = { nav.go("providers") }) }
                composable("settings") { SettingsHub(nav) }
                composable("appearance") { AppearanceScreen(onPickDesign = { nav.go("designs") }) }
                composable("designs") { DesignPickerScreen(onChosen = { nav.popBackStack() }) }
                composable("voice") { VoiceScreen(nav) }
                composable("preferences") { PreferencesScreen(nav) }
                composable("status") { SystemHub(nav) }
                composable("developer") { DeveloperScreen(nav) }
                composable("providers") { ProvidersScreen() }
                composable("memory") { MemoryScreen() }
                composable("permissions") { PermissionsScreen() }
                composable("games") { GamesScreen() }
                composable("skills") { SkillsScreen() }
            }
            ConfirmationHost()
        }
    }
}

/** Shows pending confirmations (purchases, sends, deletes...) as a dialog inside the app. */
@Composable
fun ConfirmationHost() {
    val graph = CielApplication.graph
    val pending by graph.confirmations.pending.collectAsStateWithLifecycle()
    val p = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { },
        containerColor = CielColors.Deep,
        title = { Text("Notice. ${p.request.title}", color = CielColors.Amber) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(p.request.actionDescription, color = CielColors.Text)
                Text("\n" + p.request.detail, color = CielColors.Muted)
            }
        },
        confirmButton = { TextButton(onClick = { graph.confirmations.answer(p.id, true) }) { Text("Approve", color = CielColors.Green) } },
        dismissButton = { TextButton(onClick = { graph.confirmations.answer(p.id, false) }) { Text("Decline", color = CielColors.Danger) } },
    )
}

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

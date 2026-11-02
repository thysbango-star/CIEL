package dev.ciel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import dev.ciel.app.ui.components.ScreenFrame
import dev.ciel.app.ui.components.imeVisible
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
import dev.ciel.app.ui.components.ThemeBackground
import kotlinx.coroutines.launch
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
        ThemeBackground(Modifier.fillMaxSize()) {
            if (!settings.onboardingDone) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { OnboardingScreen() }
            } else MainShell(onListen)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MainShell(onListen: () -> Unit) {
    val graph = CielApplication.graph
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = HomeActions(
        onMic = onListen,
        onSend = { text, image, mode -> graph.conversation.send(text, listOfNotNull(image), mode) },
        onStop = { graph.emergencyStop.trigger("in-app stop") },
        onTapVisual = { if (graph.conversation.state.value == CielState.SPEAKING) graph.voice.stop() else onListen() },
        onConnect = { nav.go("connect") },
        onEnableHeyCiel = { nav.go("voice") },
        onOpen = { route -> if (route == "floating") toggleFloating(context) else nav.go(route) },
    )
    androidx.compose.runtime.LaunchedEffect(nav) { graph.navEvents.collect { nav.go(it) } }
    // Keyboard handling (see components/Layout.kt): the shell is the only place insets are applied.
    // While the keyboard is open the bottom navigation is hidden, so the input sits directly on
    // top of the keyboard on every device and keyboard height.
    val keyboard = imeVisible()
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        bottomBar = {
            AnimatedVisibility(!keyboard, enter = fadeIn(tween(180)) + expandVertically(tween(180)), exit = fadeOut(tween(120)) + shrinkVertically(tween(120))) {
                NavigationBar(containerColor = CielColors.Panel) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { if (route != t.route) nav.navigate(t.route) { popUpTo("home"); launchSingleTop = true } },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = CielColors.Cyan.copy(alpha = 0.2f)),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            NavHost(
                nav, startDestination = "home",
                enterTransition = { fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 48 } },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 48 } },
            ) {
                composable("home") { HomeScreen(actions) }
                composable("chats") { ScreenFrame { ChatsScreen(onOpened = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }) } }
                composable("tasks") { ScreenFrame { TasksScreen() } }
                composable("connect") { ScreenFrame { ConnectionsScreen(onDeveloper = { nav.go("providers") }) } }
                composable("settings") { ScreenFrame { SettingsHub(nav) } }
                composable("appearance") { ScreenFrame { AppearanceScreen(onPickDesign = { nav.go("designs") }) } }
                composable("designs") { DesignPickerScreen(onChosen = { nav.popBackStack() }) }
                composable("voice") { ScreenFrame { VoiceScreen(nav) } }
                composable("preferences") { ScreenFrame { PreferencesScreen(nav) } }
                composable("status") { ScreenFrame { SystemHub(nav) } }
                composable("developer") { ScreenFrame { DeveloperScreen(nav) } }
                composable("providers") { ScreenFrame { ProvidersScreen() } }
                composable("memory") { ScreenFrame { MemoryScreen() } }
                composable("permissions") { ScreenFrame { PermissionsScreen() } }
                composable("games") { ScreenFrame { GamesScreen() } }
                composable("gamedash/{pkg}") { entry -> ScreenFrame { dev.ciel.app.ui.screens.GameDashboard(entry.arguments?.getString("pkg") ?: "") } }
                composable("skills") { ScreenFrame { SkillsScreen() } }
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

/** Shows or hides Floating Ciel; asks for the overlay permission the first time. */
fun toggleFloating(context: android.content.Context) {
    val graph = CielApplication.graph
    if (!android.provider.Settings.canDrawOverlays(context)) {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val on = !graph.settings.current.floatingEnabled
    graph.appScope.launch { graph.settings.update { it.copy(floatingEnabled = on) } }
    if (on) dev.ciel.app.overlay.FloatingCielService.start(context) else dev.ciel.app.overlay.FloatingCielService.stop(context)
}

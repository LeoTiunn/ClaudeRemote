package com.claude.remote.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.claude.remote.core.ui.layout.RootLayout
import com.claude.remote.core.ui.layout.SidebarAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.claude.remote.features.chat.ChatScreen
import com.claude.remote.features.session.SessionSwitcherScreen
import com.claude.remote.features.settings.DebugLogScreen
import com.claude.remote.features.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.SESSIONS) {
        composable(Routes.SESSIONS) {
            val widthDp = LocalConfiguration.current.screenWidthDp
            if (RootLayout.forWidthDp(widthDp) == RootLayout.SPLIT) {
                TabletHome(
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onNavigateToDebugLog = { navController.navigate(Routes.DEBUG_LOG) { launchSingleTop = true } }
                )
                return@composable
            }
            SessionSwitcherScreen(
                onSessionSelected = { sessionName ->
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.SESSIONS)
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onNavigateToDebugLog = { navController.navigate(Routes.DEBUG_LOG) { launchSingleTop = true } }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToSessions = {
                    navController.popBackStack()
                },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDebugLog = { navController.navigate(Routes.DEBUG_LOG) { launchSingleTop = true } }
            )
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Tablet / open-foldable home: webmux-style session sidebar beside the terminal.
 * Tapping a sidebar session switches the terminal in place (no navigation), the
 * menu button in the terminal's top bar collapses the sidebar for full width.
 */
@Composable
private fun TabletHome(
    onNavigateToSettings: () -> Unit,
    onNavigateToDebugLog: () -> Unit
) {
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    /** Session the terminal pane is showing; null until the first tap. */
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    /** Fed to ChatScreen so it switches in place instead of being rebuilt. */
    var requestedSession by rememberSaveable { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = sidebarVisible) {
            Row {
                Box(Modifier.width(280.dp).fillMaxHeight()) {
                    SessionSwitcherScreen(
                        onSessionSelected = { tapped ->
                            when (val a = SidebarAction.forTap(tapped, openSession)) {
                                is SidebarAction.Open -> openSession = a.name
                                is SidebarAction.SwitchTo -> { requestedSession = a.name; openSession = a.name }
                                SidebarAction.None -> Unit
                            }
                        },
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToDebugLog = onNavigateToDebugLog,
                        selectedSession = openSession
                    )
                }
                VerticalDivider()
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (openSession != null) {
                ChatScreen(
                    onNavigateToSessions = { sidebarVisible = true },
                    onNavigateToSettings = onNavigateToSettings,
                    requestedSession = requestedSession,
                    onToggleSidebar = { sidebarVisible = !sidebarVisible },
                    onSessionChanged = { openSession = it }
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Pick a session from the sidebar",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

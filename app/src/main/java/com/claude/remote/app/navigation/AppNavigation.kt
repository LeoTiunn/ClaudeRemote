package com.claude.remote.app.navigation

import androidx.compose.runtime.Composable
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
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onBack = { navController.popBackStack() })
        }
    }
}

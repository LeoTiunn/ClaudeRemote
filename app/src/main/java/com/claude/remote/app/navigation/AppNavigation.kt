package com.claude.remote.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.claude.remote.features.chat.ChatScreen
import com.claude.remote.features.session.SessionSwitcherScreen
import com.claude.remote.features.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToSessions = { navController.navigate(Routes.SESSIONS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SESSIONS) {
            SessionSwitcherScreen(
                onSessionSelected = { session ->
                    // Pop back to chat - the ChatViewModel will pick up the session
                    navController.popBackStack()
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}

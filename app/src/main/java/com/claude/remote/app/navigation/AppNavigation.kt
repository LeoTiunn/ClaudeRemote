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
    NavHost(navController = navController, startDestination = Routes.SESSIONS) {
        composable(Routes.SESSIONS) {
            SessionSwitcherScreen(
                onSessionSelected = { sessionName ->
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.SESSIONS)
                    }
                }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToSessions = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SESSIONS) { inclusive = true }
                    }
                },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}

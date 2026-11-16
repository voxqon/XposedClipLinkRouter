package com.github.voxqon.xposed.clipLinkrouter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.ui.rules.RuleEditScreen
import com.github.voxqon.xposed.clipLinkrouter.ui.settings.SettingsScreen

object Routes {
    const val SETTINGS = "settings"
    const val RULE_EDIT = "rule_edit"
}

@Composable
fun AppNavHost(app: ClipLinkApp) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SETTINGS,
    ) {
        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                onAddRule = { navController.navigate(Routes.RULE_EDIT) },
                onEditRule = { id -> navController.navigate("${Routes.RULE_EDIT}?id=$id") },
            )
        }
        composable(
            route = "${Routes.RULE_EDIT}?id={id}",
            arguments = listOf(navArgument("id") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it > 0 }
            RuleEditScreen(app = app, ruleId = id, onBack = { navController.popBackStack() })
        }
    }
}

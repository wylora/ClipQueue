package com.clipqueue.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clipqueue.app.ui.handoff.HandoffScreen
import com.clipqueue.app.ui.hashtags.HashtagsScreen
import com.clipqueue.app.ui.history.HistoryScreen
import com.clipqueue.app.ui.home.HomeScreen
import com.clipqueue.app.ui.item.ItemEditorScreen
import com.clipqueue.app.ui.schedule.ScheduleScreen
import com.clipqueue.app.ui.settings.SettingsScreen
import com.clipqueue.app.ui.videos.VideosScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("videos", "Видео", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Tab("schedule", "Расписание", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab("hashtags", "Хештеги", Icons.Outlined.Tag, Icons.Filled.Tag),
    Tab("settings", "Настройки", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun AppNavigation(handoffRequest: Long?, onHandoffConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(handoffRequest) {
        if (handoffRequest != null) {
            nav.navigate("handoff/$handoffRequest") { launchSingleTop = true }
            onHandoffConsumed()
        }
    }

    val openItem: (Long) -> Unit = { nav.navigate("item/$it") }
    val openHandoff: (Long) -> Unit = { nav.navigate("handoff/$it") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        val sel = route == tab.route
                        NavigationBarItem(
                            selected = sel,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (sel) tab.selected else tab.icon, null) },
                            label = { Text(tab.label, maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        NavHost(
            nav, startDestination = "home", modifier = Modifier
                .padding(bottom = pad.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = pad.calculateBottomPadding())),
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() }
        ) {
            composable("home") {
                HomeScreen(openItem, openHandoff,
                    onOpenVideos = { nav.navigate("videos") { launchSingleTop = true } },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } })
            }
            composable("videos") { VideosScreen(openItem, openHandoff) }
            composable("schedule") { ScheduleScreen(openItem) }
            composable("hashtags") { HashtagsScreen() }
            composable("settings") { SettingsScreen(onOpenHistory = { nav.navigate("history") }) }
            composable(
                "history",
                enterTransition = { slideInHorizontally { it } },
                popExitTransition = { slideOutHorizontally { it } }
            ) { HistoryScreen(onBack = { nav.popBackStack() }, onOpenItem = openItem) }
            composable(
                "item/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType }),
                enterTransition = { slideInHorizontally { it } },
                popExitTransition = { slideOutHorizontally { it } }
            ) { e ->
                ItemEditorScreen(e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onHandoff = openHandoff)
            }
            composable(
                "handoff/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType }),
                enterTransition = { slideInHorizontally { it } },
                popExitTransition = { slideOutHorizontally { it } }
            ) { e ->
                HandoffScreen(e.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
        }
    }
}

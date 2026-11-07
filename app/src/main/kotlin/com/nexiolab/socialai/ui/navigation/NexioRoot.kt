package com.nexiolab.socialai.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.ui.agents.AgentConfigScreen
import com.nexiolab.socialai.ui.agents.AgentsScreen
import com.nexiolab.socialai.ui.agents.TaskScreen
import com.nexiolab.socialai.ui.api.ApiScreen
import com.nexiolab.socialai.ui.api.ProviderScreen
import com.nexiolab.socialai.ui.brand.BrandScreen
import com.nexiolab.socialai.ui.brand.InterviewScreen
import com.nexiolab.socialai.ui.brand.MonthlyScreen
import com.nexiolab.socialai.ui.calendar.CalendarScreen
import com.nexiolab.socialai.ui.calendar.ContentScreen
import com.nexiolab.socialai.ui.common.LocalSnackbar
import com.nexiolab.socialai.ui.dashboard.DashboardScreen
import com.nexiolab.socialai.ui.insights.AnalyticsScreen
import com.nexiolab.socialai.ui.insights.ExpensesScreen
import com.nexiolab.socialai.ui.instagram.InstagramScreen
import com.nexiolab.socialai.ui.media.MediaScreen
import com.nexiolab.socialai.ui.migration.MigrationScreen
import com.nexiolab.socialai.ui.security.SecurityScreen
import com.nexiolab.socialai.ui.settings.SettingsScreen
import com.nexiolab.socialai.ui.video.VideoProjectScreen
import com.nexiolab.socialai.ui.video.VideoScreen
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import kotlinx.coroutines.launch

private fun NavHostController.openTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexioRoot(onboarding: Boolean) {
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val section = Section.entries.firstOrNull { it.route == route }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = tab != null,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Nexio.colors.surface) {
                Column(Modifier.padding(20.dp)) {
                    Image(painterResource(R.drawable.nexio_mark), null, Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(com.nexiolab.socialai.domain.Brand.PRODUCT, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                    Text(com.nexiolab.socialai.domain.Brand.SLOGAN, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                }
                Section.entries.forEach { item ->
                    NavigationDrawerItem(
                        icon = { Icon(item.icon, null) },
                        label = { Text(stringResource(item.title)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            nav.navigate(item.route) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            containerColor = Nexio.colors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Nexio.colors.background),
                    title = {
                        if (tab == Tab.Dashboard) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Image(painterResource(R.drawable.nexio_mark), null, Modifier.size(34.dp))
                                Text("NEXIO SOCIAL AI", style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                            }
                        } else {
                            val titleRes = tab?.title ?: section?.title ?: Routes.title(route)
                            Text(titleRes?.let { stringResource(it) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    navigationIcon = {
                        if (tab != null) {
                            IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.shell_open_menu)) }
                        } else {
                            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.shell_back)) }
                        }
                    },
                )
            },
            bottomBar = {
                if (tab != null) {
                    NavigationBar(containerColor = Nexio.colors.surface) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == tab,
                                onClick = { nav.openTab(item) },
                                icon = { Icon(item.icon, stringResource(item.title)) },
                                label = { Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Brand.Accent,
                                    selectedTextColor = Brand.Accent,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = Nexio.colors.textMuted,
                                    unselectedTextColor = Nexio.colors.textMuted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            CompositionLocalProvider(LocalSnackbar provides snackbar) { NavGraph(nav, padding, onboarding) }
        }
    }
}

@Composable
private fun NavGraph(nav: NavHostController, padding: PaddingValues, onboarding: Boolean) {
    var interviewOffered by rememberSaveable { mutableStateOf(false) }
    val open: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
    val openOrTab: (String) -> Unit = { route ->
        Tab.entries.firstOrNull { it.route == route }?.let(nav::openTab) ?: open(route)
    }
    NavHost(nav, Tab.Dashboard.route, Modifier.padding(padding).background(Nexio.colors.background).fillMaxSize()) {
        composable(Tab.Dashboard.route) {
            LaunchedEffect(Unit) {
                if (onboarding && !interviewOffered) {
                    interviewOffered = true
                    open(Routes.INTERVIEW)
                }
            }
            DashboardScreen(openOrTab)
        }
        composable(Tab.Agents.route) { AgentsScreen(open) }
        composable(Tab.Calendar.route) { CalendarScreen(open) }
        composable(Tab.Video.route) { VideoScreen(open) }
        composable(Tab.Analytics.route) { AnalyticsScreen() }
        composable(Tab.Settings.route) { SettingsScreen(openOrTab) }
        composable(Section.Brand.route) { BrandScreen() }
        composable(Section.Media.route) { MediaScreen() }
        composable(Section.Instagram.route) { InstagramScreen() }
        composable(Section.Expenses.route) { ExpensesScreen() }
        composable(Section.Api.route) { ApiScreen(open) }
        composable(Section.Migration.route) { MigrationScreen() }
        composable(Section.Security.route) { SecurityScreen() }
        composable(Routes.INTERVIEW) { InterviewScreen { nav.popBackStack() } }
        composable(Routes.MONTHLY) { MonthlyScreen { nav.popBackStack() } }
        composable("agent/{role}", listOf(navArgument("role") { type = NavType.StringType })) { entry ->
            AgentConfigScreen(AgentRole.valueOf(entry.arguments?.getString("role").orEmpty()), open)
        }
        composable("task/{id}", listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            TaskScreen(entry.arguments?.getLong("id") ?: 0, open)
        }
        composable("content/{id}", listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ContentScreen(entry.arguments?.getLong("id") ?: 0, { nav.popBackStack() }, { open(Section.Instagram.route) })
        }
        composable("video/{id}", listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            VideoProjectScreen(entry.arguments?.getLong("id") ?: 0, open) { nav.popBackStack() }
        }
        composable("provider/{id}", listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ProviderScreen(entry.arguments?.getLong("id") ?: 0) { nav.popBackStack() }
        }
    }
}

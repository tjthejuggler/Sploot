package com.example.sploot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.screens.ABCompareScreen
import com.example.sploot.ui.screens.ABCompareViewModel
import com.example.sploot.ui.screens.DashboardScreen
import com.example.sploot.ui.screens.DashboardViewModel
import com.example.sploot.ui.screens.DiaryScreen
import com.example.sploot.ui.screens.DiaryViewModel
import com.example.sploot.ui.screens.ExportScreen
import com.example.sploot.ui.screens.ExportViewModel
import com.example.sploot.ui.screens.HomeScreen
import com.example.sploot.ui.screens.ProcessingScreen
import com.example.sploot.ui.screens.ProcessingViewModel
import com.example.sploot.ui.screens.RecordViewModel
import com.example.sploot.ui.screens.SettingsScreen
import com.example.sploot.ui.screens.SettingsViewModel
import com.example.sploot.ui.screens.NightDetailScreen
import com.example.sploot.ui.screens.NightDetailViewModel
import com.example.sploot.ui.screens.TrendsScreen
import com.example.sploot.ui.screens.TrendsViewModel
import com.example.sploot.ui.theme.SplootTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SplootApplication).container
        setContent {
            SplootTheme {
                SplootApp(container)
            }
        }
    }
}

private data class TopDestination(val route: String, val label: String, val iconRes: Int)

private val destinations = listOf(
    TopDestination("home", "record", com.example.sploot.R.drawable.ic_nav_record),
    TopDestination("diary", "diary", com.example.sploot.R.drawable.ic_nav_diary),
    TopDestination("dashboard", "report", com.example.sploot.R.drawable.ic_nav_report),
    TopDestination("trends", "trends", com.example.sploot.R.drawable.ic_nav_trends),
    TopDestination("settings", "settings", com.example.sploot.R.drawable.ic_nav_settings),
)

@Composable
fun SplootApp(container: AppContainer) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in destinations.map { it.route }) {
                // nearly invisible navigation: transparent, no container,
                // no pill — a soft glow dot under the active destination
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                ) {
                    destinations.forEach { d ->
                        val selected = currentRoute == d.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(d.iconRes),
                                        contentDescription = d.label,
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                            if (selected) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                        modifier = Modifier.size(26.dp),
                                    )
                                    // soft glow dot: the only active indication
                                    Box(
                                        Modifier
                                            .padding(top = 3.dp)
                                            .size(5.dp)
                                            .alpha(if (selected) 1f else 0f)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            ),
                                    )
                                }
                            },
                            label = null,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                val vm: RecordViewModel = viewModel(factory = RecordViewModel.factory(container))
                HomeScreen(
                    viewModel = vm,
                    onProcessRecorded = { sid -> nav.navigate("processing/$sid") },
                    onProcessSnoop = { sid, uri -> nav.navigate("processing/$sid?snoopUri=$uri") },
                )
            }

            composable("diary") {
                val vm: DiaryViewModel = viewModel(factory = DiaryViewModel.factory(container))
                DiaryScreen(
                    viewModel = vm,
                    onOpenNight = { sid -> nav.navigate("night/$sid") },
                )
            }

            composable(
                route = "night/{sessionId}",
                arguments = listOf(androidx.navigation.navArgument("sessionId") { }),
            ) { entry ->
                val sid = entry.arguments?.getString("sessionId") ?: return@composable
                val vm: NightDetailViewModel = viewModel(factory = NightDetailViewModel.factory(container))
                NightDetailScreen(viewModel = vm, sessionId = sid)
            }

            composable(
                route = "processing/{sessionId}?snoopUri={snoopUri}",
                arguments = listOf(
                    androidx.navigation.navArgument("sessionId") { },
                    androidx.navigation.navArgument("snoopUri") {
                        defaultValue = ""
                        androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val sid = entry.arguments?.getString("sessionId") ?: return@composable
                val snoop = entry.arguments?.getString("snoopUri")?.takeIf { it.isNotBlank() }
                    ?.let(android.net.Uri::parse)
                val vm: ProcessingViewModel = viewModel(factory = ProcessingViewModel.factory(container))
                ProcessingScreen(
                    sessionId = sid,
                    snoopUri = snoop,
                    viewModel = vm,
                    onDone = { doneId ->
                        nav.navigate("dashboard?sessionId=$doneId") {
                            popUpTo("home")
                        }
                    },
                )
            }

            composable(
                route = "dashboard?sessionId={sessionId}",
                arguments = listOf(
                    androidx.navigation.navArgument("sessionId") {
                        defaultValue = ""
                        androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val sid = entry.arguments?.getString("sessionId")?.takeIf { it.isNotBlank() }
                val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
                DashboardScreen(
                    viewModel = vm,
                    initialSessionId = sid,
                    onCompare = { id -> nav.navigate("compare/$id") },
                )
            }

            composable("trends") {
                val vm: TrendsViewModel = viewModel(factory = TrendsViewModel.factory(container))
                TrendsScreen(vm)
            }

            composable(
                route = "compare/{sessionId}",
                arguments = listOf(androidx.navigation.navArgument("sessionId") { }),
            ) { entry ->
                val sid = entry.arguments?.getString("sessionId") ?: return@composable
                val vm: ABCompareViewModel = viewModel(factory = ABCompareViewModel.factory(container))
                ABCompareScreen(sessionId = sid, viewModel = vm)
            }

            composable("export") {
                val vm: ExportViewModel = viewModel(factory = ExportViewModel.factory(container))
                ExportScreen(vm)
            }

            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
                SettingsScreen(vm, onOpenExport = { nav.navigate("export") })
            }
        }
    }
}

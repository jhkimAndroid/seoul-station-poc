package com.hubilon.seoulstationpoc.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hubilon.seoulstationpoc.ui.intro.IntroScreen
import com.hubilon.seoulstationpoc.ui.map.MapScreen
import com.hubilon.seoulstationpoc.ui.map.MapViewModel
import com.hubilon.seoulstationpoc.ui.scan.ScanDetailScreen

sealed class Screen(val route: String) {
    data object Intro : Screen("intro")
    data object Map : Screen("map")
    data object ScanDetail : Screen("scan_detail/{section}") {
        fun createRoute(section: String) = "scan_detail/$section"
    }
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    // Activity 범위로 생성 — MapScreen과 ScanDetailScreen이 동일 인스턴스 공유
    val mapViewModel: MapViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Intro.route
    ) {
        composable(Screen.Intro.route) {
            IntroScreen(
                onAllPermissionsGranted = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Intro.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Map.route) {
            MapScreen(
                viewModel = mapViewModel,
                onNavigateToScanDetail = { section ->
                    navController.navigate(Screen.ScanDetail.createRoute(section))
                }
            )
        }
        composable(
            route = Screen.ScanDetail.route,
            arguments = listOf(
                navArgument("section") {
                    type = NavType.StringType
                    defaultValue = "wifi"
                }
            )
        ) { backStackEntry ->
            val section = backStackEntry.arguments?.getString("section") ?: "wifi"
            ScanDetailScreen(
                viewModel = mapViewModel,
                startSection = section,
                onNavigateUp = { navController.navigateUp() }
            )
        }
    }
}

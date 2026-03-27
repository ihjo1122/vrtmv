package com.vrtmv.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vrtmv.app.ui.camera.CameraScreen
import com.vrtmv.app.ui.intro.IntroScreen
import com.vrtmv.app.ui.main.MainScreen

object NavRoutes {
    const val INTRO = "intro"
    const val MAIN = "main"
    const val CAMERA = "camera"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.INTRO
    ) {
        composable(NavRoutes.INTRO) {
            IntroScreen(
                onNavigateToMain = {
                    navController.navigate(NavRoutes.MAIN) {
                        popUpTo(NavRoutes.INTRO) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.MAIN) {
            MainScreen(
                onNavigateToCamera = {
                    navController.navigate(NavRoutes.CAMERA)
                }
            )
        }

        composable(NavRoutes.CAMERA) {
            CameraScreen()
        }
    }
}

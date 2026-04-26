package com.vrtmv.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.ui.camera.CameraScreen
import com.vrtmv.app.ui.intro.IntroScreen
import com.vrtmv.app.ui.main.MainScreen

object NavRoutes {
    const val INTRO = "intro"
    const val MAIN = "main"
    const val CAMERA = "camera/{modelId}/{detectorId}/{useArCore}/{fullFrameVlm}"

    fun cameraRoute(modelId: String, detectorId: String, useArCore: Boolean, fullFrameVlm: Boolean) =
        "camera/$modelId/$detectorId/$useArCore/$fullFrameVlm"
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
                onNavigateToCamera = { modelId, detectorId, useArCore, fullFrameVlm ->
                    navController.navigate(NavRoutes.cameraRoute(modelId, detectorId, useArCore, fullFrameVlm))
                }
            )
        }

        composable(
            route = NavRoutes.CAMERA,
            arguments = listOf(
                navArgument("modelId") {
                    type = NavType.StringType
                    defaultValue = ModelRegistry.DEFAULT_MODEL_ID
                },
                navArgument("detectorId") {
                    type = NavType.StringType
                    defaultValue = DetectorKind.MEDIAPIPE.id
                },
                navArgument("useArCore") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("fullFrameVlm") {
                    type = NavType.BoolType
                    defaultValue = true
                }
            )
        ) {
            CameraScreen()
        }
    }
}

package com.vrtmv.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.ui.camera.CameraScreen
import com.vrtmv.app.ui.intro.IntroScreen
import com.vrtmv.app.ui.main.MainScreen

object NavRoutes {
    const val INTRO = "intro"
    const val MAIN = "main"
    const val CAMERA = "camera/{modelId}/{captureMode}"

    fun cameraRoute(modelId: String, captureMode: CaptureMode) =
        "camera/$modelId/${captureMode.id}"
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
                onNavigateToCamera = { modelId, captureMode ->
                    navController.navigate(NavRoutes.cameraRoute(modelId, captureMode))
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
                navArgument("captureMode") {
                    type = NavType.StringType
                    defaultValue = CaptureMode.OBJECT_DETECTION.id
                }
            )
        ) {
            CameraScreen()
        }
    }
}

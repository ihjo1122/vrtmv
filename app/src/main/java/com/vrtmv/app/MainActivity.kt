package com.vrtmv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vrtmv.app.ui.camera.CameraScreen
import com.vrtmv.app.ui.theme.VrtmvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 메인 액티비티.
 * Edge-to-Edge 모드로 전체 화면 사용,
 * Compose 기반 CameraScreen을 루트 화면으로 설정.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VrtmvTheme {
                CameraScreen()
            }
        }
    }
}

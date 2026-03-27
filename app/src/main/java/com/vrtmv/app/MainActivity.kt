package com.vrtmv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vrtmv.app.navigation.AppNavHost
import com.vrtmv.app.ui.theme.VrtmvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 메인 액티비티.
 * Edge-to-Edge 모드로 전체 화면 사용,
 * Navigation을 통해 Intro → Main → Camera 흐름을 관리.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VrtmvTheme {
                AppNavHost()
            }
        }
    }
}

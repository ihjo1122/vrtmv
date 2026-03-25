package com.vrtmv.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 앱 전역 Application 클래스.
 * Hilt 의존성 주입 컨테이너의 시작점.
 */
@HiltAndroidApp
class VrtmvApplication : Application()

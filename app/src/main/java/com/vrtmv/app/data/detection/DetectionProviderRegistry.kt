package com.vrtmv.app.data.detection

import android.content.Context
import android.util.Log
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.util.AssetPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 검출기 Singleton 캐시. Intro 에서 initAll() 로 두 구현을 모두 선행 초기화해
 * Main→Camera 전환 시 setup 비용(수백 ms)을 숨긴다.
 */
@Singleton
class DetectionProviderRegistry @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val assetPathResolver: AssetPathResolver
) {
    companion object {
        private const val TAG = "DetProviderReg"
    }

    private val providers = mutableMapOf<DetectorKind, DetectionProvider>()

    @Synchronized
    fun get(kind: DetectorKind): DetectionProvider {
        providers[kind]?.let { return it }
        val created = createProvider(kind)
        providers[kind] = created
        return created
    }

    @Synchronized
    fun initAll() {
        for (kind in DetectorKind.entries) {
            if (providers.containsKey(kind)) continue
            try {
                providers[kind] = createProvider(kind)
                Log.i(TAG, "${kind.displayName} 초기화 완료")
            } catch (e: Exception) {
                Log.w(TAG, "${kind.displayName} 초기화 실패 — 첫 get() 시 재시도", e)
            }
        }
    }

    private fun createProvider(kind: DetectorKind): DetectionProvider = when (kind) {
        DetectorKind.MEDIAPIPE -> MediaPipeDetectionProvider(appContext)
        DetectorKind.YOLO -> YoloDetectionProvider(appContext, assetPathResolver)
    }
}

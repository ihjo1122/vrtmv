package com.vrtmv.app.data.detection

import android.content.Context
import android.util.Log
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.util.AssetPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 검출기(MediaPipe/YOLO) Singleton 레지스트리.
 *
 * Camera 화면 진입마다 재생성되던 DetectionProvider를 전역 캐시로 전환한다.
 * Intro 단계에서 initAll()로 두 구현 모두 선행 초기화하면, 이후 Main→Camera 전환이 즉시 이루어진다.
 *
 * - 동일 세션 내 검출기 동시 사용이 없으므로 paused 플래그 공유는 문제 없음
 * - 각 Provider의 updateFrame은 Camera 수명주기 동안만 호출되고,
 *   Camera 화면 이탈 시 Analyzer가 종료되므로 버퍼는 자연스레 비워진다
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

    /** 특정 검출기를 생성(없으면)해 반환. 블로킹 setup은 수백 ms. */
    @Synchronized
    fun get(kind: DetectorKind): DetectionProvider {
        providers[kind]?.let { return it }
        val created = createProvider(kind)
        providers[kind] = created
        return created
    }

    /** 두 검출기 모두 선행 초기화. YOLO 모델 파일 부재 등은 내부에서 로그만 남기고 성공으로 간주. */
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

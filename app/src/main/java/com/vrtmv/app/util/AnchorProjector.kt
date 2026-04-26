package com.vrtmv.app.util

import android.opengl.Matrix
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

/**
 * ARCore [Anchor]의 월드 좌표를 화면 픽셀 좌표로 투영한다.
 *
 * 매 프레임 [Frame.camera] 의 view·projection 행렬을 받아 anchor 의 translation 을 MVP 로 변환,
 * 클립 → NDC → 뷰포트 좌표로 환산. 카메라 뒤쪽이거나 anchor 가 STOPPED(영구 실패) 면 null.
 *
 * 일시적 PAUSED 상태에서는 마지막으로 알려진 pose 로 투영을 계속한다 — 일시 trackingFailure 가
 * 화면에서 태그를 깜빡이게 만드는 문제를 회피.
 */
object AnchorProjector {

    private const val TAG = "AnchorProj"
    /** 디버그 로그 토글. 너무 자주 찍히지 않도록 카운터 throttle. */
    private const val LOG_EVERY_N_FRAMES = 60
    private var frameCounter: Int = 0

    /**
     * @return 화면 픽셀 좌표(좌상단 원점, Y 아래 양수). 카메라 뒤이거나 영구 tracking 실패면 null.
     */
    fun project(anchor: Anchor, frame: Frame, viewportW: Int, viewportH: Int): Offset? {
        if (viewportW <= 0 || viewportH <= 0) return null
        // 영구 실패만 거르고, PAUSED 는 마지막 pose 로 계속 투영
        if (anchor.trackingState == TrackingState.STOPPED) return null
        val camera = frame.camera
        // 카메라 자체가 PAUSED 면 view 행렬이 stale 일 가능성이 있으나, 그래도 마지막 매트릭스로 투영
        // (완전히 null 반환하면 UI 가 정적 박스로 폴백되어 "태그가 카메라 추종"으로 보이는 문제 회피)

        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        val pose = anchor.pose
        val world = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, mvp, 0, world, 0)

        val w = clip[3]
        if (w <= 0f) return null  // 카메라 뒤쪽

        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val ndcZ = clip[2] / w

        // 뷰프러스텀 엄격 검사 — 카메라가 90° 이상 돌면 ndc 가 ±1 을 넘어가며, 이 경우 태그를 숨겨야 한다.
        // 1.2 여유는 가장자리 반짝임 방지용 (경계에서 살짝 오버슈트 허용).
        val NDC_LIMIT = 1.2f
        if (ndcX < -NDC_LIMIT || ndcX > NDC_LIMIT) return null
        if (ndcY < -NDC_LIMIT || ndcY > NDC_LIMIT) return null
        if (ndcZ < -1f || ndcZ > 1f) return null  // 니어/파 평면 밖

        val screenX = (ndcX + 1f) * 0.5f * viewportW
        val screenY = (1f - ndcY) * 0.5f * viewportH

        // 60 프레임에 1번 디버그 로그 — anchor 가 카메라와 무관하게 움직이는지 확인용
        if (LOG_EVERY_N_FRAMES > 0 && (frameCounter++ % LOG_EVERY_N_FRAMES) == 0) {
            val cam = frame.camera.pose
            Log.d(
                TAG,
                "anchor world=(%.2f,%.2f,%.2f) cam=(%.2f,%.2f,%.2f) ndc=(%.2f,%.2f) screen=(%.0f,%.0f) anchorTS=%s camTS=%s".format(
                    pose.tx(), pose.ty(), pose.tz(),
                    cam.tx(), cam.ty(), cam.tz(),
                    ndcX, ndcY, screenX, screenY,
                    anchor.trackingState, camera.trackingState
                )
            )
        }

        return Offset(screenX, screenY)
    }
}

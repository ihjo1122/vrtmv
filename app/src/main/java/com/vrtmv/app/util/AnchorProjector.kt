package com.vrtmv.app.util

import android.opengl.Matrix
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

/**
 * ARCore [Anchor] 의 월드 좌표를 화면 픽셀로 투영. PAUSED 상태에서도 마지막 알려진 pose 로
 * 계속 투영해 일시 trackingFailure 가 태그를 깜빡이게 만드는 회귀 회피. STOPPED(영구 실패)
 * 또는 카메라 뒤/뷰프러스텀 밖이면 null.
 */
object AnchorProjector {

    private const val TAG = "AnchorProj"
    private const val LOG_EVERY_N_FRAMES = 60
    private var frameCounter: Int = 0

    fun project(anchor: Anchor, frame: Frame, viewportW: Int, viewportH: Int): Offset? {
        if (viewportW <= 0 || viewportH <= 0) return null
        if (anchor.trackingState == TrackingState.STOPPED) return null
        val camera = frame.camera

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

        // 카메라가 90° 이상 돌면 ndc 가 ±1 을 넘어 — 1.2 여유는 가장자리 반짝임 방지용 오버슈트 허용.
        val NDC_LIMIT = 1.2f
        if (ndcX < -NDC_LIMIT || ndcX > NDC_LIMIT) return null
        if (ndcY < -NDC_LIMIT || ndcY > NDC_LIMIT) return null
        if (ndcZ < -1f || ndcZ > 1f) return null

        val screenX = (ndcX + 1f) * 0.5f * viewportW
        val screenY = (1f - ndcY) * 0.5f * viewportH

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

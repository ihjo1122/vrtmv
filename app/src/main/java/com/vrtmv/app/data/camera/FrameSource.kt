package com.vrtmv.app.data.camera

import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.LifecycleOwner

/**
 * 카메라 프레임 공급자 추상화. 두 백엔드(CameraX/ARCore)를 동일 인터페이스로 노출하고
 * 리스너에는 항상 upright Bitmap(센서 회전 적용 완료)만 전달한다.
 */
interface FrameSource {

    val view: View
    fun start(lifecycleOwner: LifecycleOwner)
    fun stop()
    fun addListener(listener: FrameListener)
    fun removeListener(listener: FrameListener)
    fun close()
}

fun interface FrameListener {
    /**
     * bitmap 은 콜백 동안만 유효 (소스가 호출 직후 recycle). 보존하려면 `bitmap.copy(...)`.
     * timestampMs 는 단조 증가 — MediaPipe LIVE_STREAM 등 시퀀스 식별용.
     */
    fun onFrame(bitmap: Bitmap, timestampMs: Long)
}

/**
 * GL thread 에서 매 프레임 호출 — anchor 투영 등 [com.google.ar.core.Frame] 직접 접근용.
 * 렌더 thread 점유 방지를 위해 좌표 계산만 하고 빠르게 반환.
 */
fun interface ArFrameCallback {
    fun onArFrame(frame: com.google.ar.core.Frame, viewportW: Int, viewportH: Int)
}

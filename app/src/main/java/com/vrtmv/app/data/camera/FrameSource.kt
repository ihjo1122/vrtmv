package com.vrtmv.app.data.camera

import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.LifecycleOwner

/**
 * 카메라 프레임 공급자 추상화.
 *
 * 두 백엔드를 동일 인터페이스로 노출:
 * - [CameraXFrameSource]: CameraX `ImageAnalysis` 기반 (ARCore 미지원/실패 기기 폴백)
 * - [ArCoreFrameSource]: ARCore `Session.update()` 기반 (월드 앵커 활성화)
 *
 * 리스너는 항상 **upright Bitmap**(센서 회전 적용 완료)을 받는다.
 */
interface FrameSource {

    /**
     * 카메라 영상을 화면에 표시할 뷰. CameraX는 `PreviewView`, ARCore는 `GLSurfaceView`.
     * 호출자는 이 뷰를 `AndroidView` 컴포저블로 감싸 화면에 부착한다.
     */
    val view: View

    /** 프레임 송출을 시작한다. LifecycleOwner에 바인딩되어 자동 resume/pause. */
    fun start(lifecycleOwner: LifecycleOwner)

    /** 프레임 송출을 중지하고 카메라 자원을 해제한다. */
    fun stop()

    /** 프레임 리스너 등록. 같은 리스너 중복 등록 무시. */
    fun addListener(listener: FrameListener)

    /** 프레임 리스너 해제. 등록되지 않은 리스너 무시. */
    fun removeListener(listener: FrameListener)

    /** 영구 종료 — 더는 start/stop 불가. */
    fun close()
}

fun interface FrameListener {
    /**
     * 프레임 콜백.
     *
     * **수명 계약**: [bitmap]은 콜백 호출 동안만 유효하며 호출 직후 소스가 recycle 한다.
     * 리스너가 프레임을 보존해야 하면 반드시 `bitmap.copy(...)` 로 사본을 만들어야 한다.
     *
     * @param bitmap upright(회전 적용 완료) ARGB_8888 비트맵
     * @param timestampMs 단조 증가 ms 타임스탬프 (MediaPipe LIVE_STREAM 등 시퀀스 식별용)
     */
    fun onFrame(bitmap: Bitmap, timestampMs: Long)
}

/**
 * ARCore 전용 프레임 콜백 — GL thread 에서 매 프레임 호출.
 * anchor 투영 등 [com.google.ar.core.Frame] 객체에 직접 접근해야 하는 경우 사용.
 *
 * 콜백 내부에서 무거운 작업 금지(렌더 thread 점유). 좌표 계산만 수행하고 빠르게 반환할 것.
 */
fun interface ArFrameCallback {
    fun onArFrame(frame: com.google.ar.core.Frame, viewportW: Int, viewportH: Int)
}

package com.vrtmv.app.data.detection

import android.graphics.Bitmap
import com.vrtmv.app.domain.model.DetectedObject

/**
 * 객체 검출기 공통 인터페이스. 입력 비트맵은 [com.vrtmv.app.data.camera.FrameSource] 가
 * 이미 upright(센서 회전 적용) 상태로 송출하므로 구현체는 회전 처리를 하지 않는다.
 */
interface DetectionProvider {

    // bitmap 은 호출 동안만 유효 (FrameSource 가 호출 직후 recycle) — 보존하려면 사본.
    fun updateFrame(bitmap: Bitmap, timestampMs: Long)

    fun detectNow(): DetectionResult?

    // 임의 Bitmap 에 대해 즉시 1회 검출. 비트맵 recycle 은 호출자 책임 (구현체는 복사·해제하지 않음).
    // Cascade 파이프라인이 ROI 비트맵을 재검출할 때 사용.
    fun detectOnBitmap(bitmap: Bitmap): List<DetectedObject>

    // 반환된 Bitmap 의 recycle 책임은 호출자.
    fun captureFrame(): Bitmap?

    // true 면 updateFrame 이 프레임을 버린다 — 추론 중 GPU/CPU 를 VLM 에 양보용.
    var paused: Boolean

    fun close()
}

data class DetectionResult(
    val objects: List<DetectedObject>,
    val bitmap: Bitmap,
    val imageWidth: Int,
    val imageHeight: Int
)

package com.vrtmv.app.data.detection

import android.graphics.Bitmap
import com.vrtmv.app.domain.model.DetectedObject

/**
 * 객체 검출기 공통 인터페이스.
 * MediaPipe / YOLO 구현체가 동일한 하류 파이프라인(CameraViewModel)에 주입될 수 있도록 한다.
 *
 * - 런타임에는 한 번에 한 구현체만 생성·로드됨 (혼합 실행 아님)
 * - 프레임 버퍼링은 각 구현체 내부에서 관리
 * - detectNow()는 터치·제스처 시점에 온디맨드 호출
 *
 * 입력 비트맵은 [com.vrtmv.app.data.camera.FrameSource] 가 이미 upright(센서 회전 적용)
 * 상태로 송출하므로 구현체는 회전 처리를 하지 않는다.
 */
interface DetectionProvider {

    /**
     * 최신 카메라 프레임을 버퍼에 저장.
     *
     * **수명 계약**: [bitmap]은 호출 동안만 유효. 보존하려면 사본을 만들 것.
     *
     * @param bitmap upright ARGB_8888 비트맵 — 호출 직후 [FrameSource]가 recycle
     * @param timestampMs 단조 ms 타임스탬프 (현재 미사용, 추후 디버그/통계용)
     */
    fun updateFrame(bitmap: Bitmap, timestampMs: Long)

    /** 현재 버퍼된 프레임에서 즉시 검출 실행. 프레임 없으면 null. */
    fun detectNow(): DetectionResult?

    /**
     * 최신 프레임 사본만 반환 (검출 없음). 추론 모드에서 캡처→VLM 경로용.
     * 반환된 Bitmap은 호출자가 recycle 책임을 진다.
     */
    fun captureFrame(): Bitmap?

    /** 추론 중 프레임 처리 중단 플래그. true면 updateFrame이 프레임을 버리고 즉시 종료. */
    var paused: Boolean

    /** 리소스 해제. DisposableEffect·ViewModel onCleared에서 호출. */
    fun close()
}

/**
 * 검출 결과 공통 타입.
 *
 * @param objects 검출된 객체 목록 (바운딩박스는 [bitmap] 좌표계)
 * @param bitmap 검출에 사용된 upright 프레임 — VLM 크롭용으로 보관
 * @param imageWidth 비트맵 너비
 * @param imageHeight 비트맵 높이
 */
data class DetectionResult(
    val objects: List<DetectedObject>,
    val bitmap: Bitmap,
    val imageWidth: Int,
    val imageHeight: Int
)

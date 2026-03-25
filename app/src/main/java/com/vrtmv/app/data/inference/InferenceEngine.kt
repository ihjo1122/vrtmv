package com.vrtmv.app.data.inference

import android.graphics.Bitmap

/**
 * VLM 추론 엔진 인터페이스.
 * 구현체: GeminiNanoEngine (온디바이스), MockInferenceEngine (테스트용).
 * Hilt DI를 통해 런타임에 구현체가 주입됨.
 */
interface InferenceEngine {
    /**
     * 크롭된 이미지와 검출 정보를 기반으로 객체 설명을 생성한다.
     *
     * @param image 크롭된 ROI 이미지 (바운딩박스 기준)
     * @param label 검출된 객체 라벨 (예: "chair")
     * @param confidence 검출 신뢰도 (0.0 ~ 1.0)
     * @return 생성된 객체 설명 텍스트
     */
    suspend fun describe(image: Bitmap, label: String, confidence: Float): String

    /** 현재 기기에서 추론 엔진을 사용할 수 있는지 여부 */
    fun isAvailable(): Boolean
}

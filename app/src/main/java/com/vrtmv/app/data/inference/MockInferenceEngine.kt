package com.vrtmv.app.data.inference

import android.graphics.Bitmap
import javax.inject.Inject

/**
 * 테스트용 더미 추론 엔진.
 * 실제 모델 없이 검출 라벨과 신뢰도만 반환한다.
 * 온디바이스 모델 배치 전 UI 동작 확인에 사용.
 */
class MockInferenceEngine @Inject constructor() : InferenceEngine {

    override suspend fun describe(image: Bitmap, label: String, confidence: Float): String {
        return "$label detected (${(confidence * 100).toInt()}% confidence)"
    }

    override fun isAvailable(): Boolean = true
}

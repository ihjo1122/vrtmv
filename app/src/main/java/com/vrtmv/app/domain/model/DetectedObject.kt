package com.vrtmv.app.domain.model

import android.graphics.RectF

/**
 * 객체 검출 결과를 나타내는 도메인 모델.
 *
 * @param boundingBox 검출된 객체의 바운딩박스 (이미지 픽셀 좌표)
 * @param label 검출된 객체의 라벨 (예: "chair", "cup")
 * @param confidence 검출 신뢰도 (0.0 ~ 1.0)
 */
data class DetectedObject(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)

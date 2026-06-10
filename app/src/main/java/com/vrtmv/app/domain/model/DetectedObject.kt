package com.vrtmv.app.domain.model

import android.graphics.RectF

data class DetectedObject(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)

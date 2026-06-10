package com.vrtmv.app.data.inference

import android.graphics.Bitmap
import com.vrtmv.app.data.recording.DescriptionResult

interface InferenceEngine {
    suspend fun describe(image: Bitmap, label: String, confidence: Float): DescriptionResult
    suspend fun describeScene(image: Bitmap): DescriptionResult
    fun isAvailable(): Boolean
    suspend fun loadModel(modelInfo: com.vrtmv.app.domain.model.ModelInfo): Boolean = true
    suspend fun ensureLoaded(): Boolean = true
    fun release() {}
}

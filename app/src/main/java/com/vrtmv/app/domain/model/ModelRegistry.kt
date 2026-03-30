package com.vrtmv.app.domain.model

import com.vrtmv.app.BuildConfig

/**
 * 사용 가능한 온디바이스 VLM 모델 목록을 관리한다.
 * 모델 추가/변경 시 이 파일만 수정하면 됨.
 */
object ModelRegistry {

    /** 기본 모델 ID */
    const val DEFAULT_MODEL_ID = "gemma3n-e2b-it-int4"

    private val models = listOf(
        ModelInfo(
            id = "gemma3n-e2b-it-int4",
            displayName = "Gemma 3n E2B-IT (INT4)",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            downloadUrl = "",  // 수동 배치: adb push → Download/vrtmv/
            quantization = "int4",
            expectedSizeMB = 3660
        )
    )

    /** 모든 모델 목록 반환 */
    fun getAllModels(): List<ModelInfo> = models

    /** ID로 모델 조회. 없으면 null */
    fun getModel(id: String): ModelInfo? = models.find { it.id == id }

    /** 기본 모델 반환 */
    fun getDefaultModel(): ModelInfo = getModel(DEFAULT_MODEL_ID) ?: models.first()
}

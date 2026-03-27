package com.vrtmv.app.domain.model

/**
 * 사용 가능한 온디바이스 LLM 모델 목록을 관리한다.
 * 모델 추가/변경 시 이 파일만 수정하면 됨.
 */
object ModelRegistry {

    /** 기본 모델 ID (앱 시작 시 자동 다운로드) */
    const val DEFAULT_MODEL_ID = "gemma3-1b-int4"

    private val models = listOf(
        ModelInfo(
            id = "gemma3-1b-int4",
            displayName = "Gemma 3 1B (INT4)",
            fileName = "gemma3-1b-it-int4.task",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            quantization = "int4",
            expectedSizeMB = 900
        ),
        ModelInfo(
            id = "gemma3-1b-int8",
            displayName = "Gemma 3 1B (INT8)",
            fileName = "gemma3-1b-it-int8.task",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int8.task",
            quantization = "int8",
            expectedSizeMB = 1500
        )
    )

    /** 모든 모델 목록 반환 */
    fun getAllModels(): List<ModelInfo> = models

    /** ID로 모델 조회. 없으면 null */
    fun getModel(id: String): ModelInfo? = models.find { it.id == id }

    /** 기본 모델 반환 */
    fun getDefaultModel(): ModelInfo = getModel(DEFAULT_MODEL_ID)!!
}

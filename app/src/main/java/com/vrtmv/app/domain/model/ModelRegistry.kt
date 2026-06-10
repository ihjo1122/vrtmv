package com.vrtmv.app.domain.model

object ModelRegistry {

    const val DEFAULT_MODEL_ID = "gemma4-e2b-it-int4"

    private val models = listOf(
        ModelInfo(
            id = "gemma4-e2b-it-int4",
            displayName = "Gemma 4 E2B-IT",
            fileName = "gemma-4-E2B-it.litertlm",
            // 개인 공개 미러 리포 (Google 공식 배포는 게이티드)
            downloadUrl = "https://huggingface.co/joinhyeok/gemma/resolve/main/gemma-4-E2B-it.litertlm",
            quantization = "int4",
            // 실측 Content-Length: 2,583,085,056 바이트 ≈ 2464 MB
            expectedSizeMB = 2464
        )
    )

    fun getAllModels(): List<ModelInfo> = models

    fun getModel(id: String): ModelInfo? = models.find { it.id == id }

    fun getDefaultModel(): ModelInfo = getModel(DEFAULT_MODEL_ID) ?: models.first()
}

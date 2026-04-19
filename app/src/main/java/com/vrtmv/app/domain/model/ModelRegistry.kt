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
            displayName = "Gemma 3n E2B-IT",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            // 개인 공개 미러 리포 (Google 공식 gemma-3n-E2B-it-litert-preview는 게이티드)
            downloadUrl = "https://huggingface.co/joinhyeok/gemma/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            quantization = "int4",
            // 실측 Content-Length: 3,655,827,456 바이트 ≈ 3486 MB
            expectedSizeMB = 3486
        )
        // TODO(gemma4): litertlm-android 0.10.1이 Google Maven에 배포되면 Gemma 4 재활성화
        // ModelInfo(
        //     id = "gemma4-e2b-it",
        //     displayName = "Gemma 4 E2B-IT",
        //     fileName = "gemma-4-E2B-it.litertlm",
        //     downloadUrl = "https://huggingface.co/joinhyeok/gemma/resolve/main/gemma-4-E2B-it.litertlm",
        //     quantization = "int4",
        //     expectedSizeMB = 2464  // 실측 2,583,085,056 바이트
        // )
    )

    /** 모든 모델 목록 반환 */
    fun getAllModels(): List<ModelInfo> = models

    /** ID로 모델 조회. 없으면 null */
    fun getModel(id: String): ModelInfo? = models.find { it.id == id }

    /** 기본 모델 반환 */
    fun getDefaultModel(): ModelInfo = getModel(DEFAULT_MODEL_ID) ?: models.first()
}

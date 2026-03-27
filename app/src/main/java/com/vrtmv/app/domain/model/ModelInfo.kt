package com.vrtmv.app.domain.model

/**
 * 온디바이스 LLM 모델 정보.
 *
 * @param id 모델 고유 식별자 (Navigation argument로 사용)
 * @param displayName UI에 표시될 이름
 * @param fileName 모델 파일명 (.task)
 * @param downloadUrl HuggingFace 다운로드 URL
 * @param quantization 양자화 수준 (int4, int8, float16 등)
 * @param expectedSizeMB 예상 파일 크기 (MB, 다운로드 진행률용)
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val quantization: String,
    val expectedSizeMB: Int
)

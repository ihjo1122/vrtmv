package com.vrtmv.app.domain.model

/**
 * VLM 추론 상태를 나타내는 sealed class.
 * UI에서 로딩/결과/에러 상태를 분기 처리하는 데 사용.
 */
sealed class InferenceState {
    /** 대기 상태 (추론 미실행) */
    data object Idle : InferenceState()

    /** 추론 진행 중 (로딩 표시) */
    data object Loading : InferenceState()

    /** 추론 성공 — 생성된 설명 텍스트 포함 */
    data class Success(val text: String) : InferenceState()

    /** 추론 실패 — 에러 메시지 포함 */
    data class Error(val message: String?) : InferenceState()

    /** 모델을 사용할 수 없는 기기 */
    data object ModelUnavailable : InferenceState()
}

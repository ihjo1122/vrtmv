package com.vrtmv.app.domain.model

sealed class InferenceState {
    data object Idle : InferenceState()
    data object Loading : InferenceState()
    data class Success(val text: String) : InferenceState()
    data class Error(val message: String?) : InferenceState()
}

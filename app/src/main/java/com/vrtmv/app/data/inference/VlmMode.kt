package com.vrtmv.app.data.inference

/**
 * VLM 추론 모드.
 * 우상단 토글 버튼으로 전환.
 * OFF: 검출만 수행, ON: 검출 + 온디바이스 VLM 추론.
 */
enum class VlmMode(val label: String) {
    OFF("VLM Off"),
    ON("VLM On")
}

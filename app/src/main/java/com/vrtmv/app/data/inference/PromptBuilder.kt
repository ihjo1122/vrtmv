package com.vrtmv.app.data.inference

/**
 * VLM 추론용 프롬프트 생성기.
 * 검출된 객체의 라벨과 신뢰도를 기반으로
 * 한국어 설명을 요청하는 프롬프트를 구성한다.
 */
object PromptBuilder {

    /**
     * 멀티모달 비전 추론용 프롬프트를 생성한다.
     * 이미지가 함께 전달되므로 이미지를 보고 설명하도록 요청.
     */
    fun buildVisionPrompt(label: String, confidence: Float): String {
        val pct = (confidence * 100).toInt()
        return """
            이 이미지에서 "$label"(${pct}% 확률)로 감지된 객체가 보입니다.
            이미지를 보고 이 객체가 무엇인지 한국어로 간결하게 1~2문장으로 설명해주세요.
        """.trimIndent()
    }

    /** 전체 장면 설명용 프롬프트. 객체 미검출 시 사용. */
    fun buildScenePrompt(): String {
        return "이 이미지에 보이는 장면을 한국어로 간결하게 1~2문장으로 설명해주세요."
    }
}

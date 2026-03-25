package com.vrtmv.app.data.inference

/**
 * VLM 추론용 프롬프트 생성기.
 * 검출된 객체의 라벨과 신뢰도를 기반으로
 * 한국어 설명을 요청하는 프롬프트를 구성한다.
 */
object PromptBuilder {

    /**
     * 객체 설명 요청 프롬프트를 생성한다.
     *
     * @param label 검출된 객체 라벨
     * @param confidence 검출 신뢰도 (0.0 ~ 1.0)
     * @return LLM에 전달할 프롬프트 문자열
     */
    fun buildDescriptionPrompt(label: String, confidence: Float): String {
        val pct = (confidence * 100).toInt()
        return """
            You are looking at an object detected as "$label" with $pct% confidence.
            Describe this object in one concise sentence in Korean.
            Focus on what it is, its appearance, and any notable features visible in the image.
        """.trimIndent()
    }
}

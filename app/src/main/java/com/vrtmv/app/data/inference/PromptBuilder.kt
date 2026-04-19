package com.vrtmv.app.data.inference

/**
 * VLM 추론용 프롬프트 생성기 + 응답 후처리.
 *
 * 실측(Gemma 3n E2B int4, GPU, S23 Ultra):
 *   - Prefill 고정: ~1.8초
 *   - 20-40자 응답: 총 3-4초 범위
 *
 * Gemma 3n 한국어 응답 특성:
 *   - 자연스러운 대화형 프롬프트가 "짧게 설명" 지시문보다 품질이 높음
 *   - 가끔 마크다운 별표(**), 영어 번역 병기, 목록 템플릿이 섞이므로 후처리로 정리
 *   - EOS를 일찍 찍는 문제는 프롬프트 끝에 공백만 두는 방식으로 완화
 */
object PromptBuilder {

    /** 객체 크롭 이미지 설명 요청. 검출 라벨을 힌트로 제공. */
    fun buildVisionPrompt(label: String): String {
        return "이 ${label}을 한국어로 40자 이내 한 문장으로 구체적으로 설명해줘. 마침표로 끝내."
    }

    /** 전체 장면 설명. 객체 미검출 시 사용. */
    fun buildScenePrompt(): String {
        return "이 장면을 한국어로 40자 이내 한 문장으로 구체적으로 설명해줘. 마침표로 끝내."
    }

    /**
     * 모델 응답 후처리.
     * - 마크다운 별표 제거
     * - 괄호 안 영어 번역 제거
     * - 여러 문장이면 첫 문장만
     * - 공백 정리
     */
    fun cleanResponse(raw: String): String {
        var text = raw.trim()

        // 마크다운 강조 제거
        text = text.replace(Regex("""\*+"""), "")

        // 괄호 안 영어 번역 제거: "한국어 문장. (English translation.)"
        text = text.replace(Regex("""\([^)]*[A-Za-z][^)]*\)"""), "")

        // 첫 문장만 추출 (마침표/물음표/느낌표)
        val firstSentenceEnd = text.indexOfAny(charArrayOf('.', '。', '?', '!', '！', '？'))
        if (firstSentenceEnd > 0) {
            text = text.substring(0, firstSentenceEnd + 1)
        }

        // 공백 정리
        return text.replace(Regex("""\s+"""), " ").trim()
    }
}

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
 *   - **영어 label 을 한국어 프롬프트에 섞으면 hallucination 증가** — 관찰 이슈로 인해
 *     객체/장면 프롬프트를 완전히 동일한 한국어 문장으로 통일 (label 힌트 제거)
 *   - EOS를 일찍 찍는 문제는 프롬프트 끝에 공백만 두는 방식으로 완화
 */
object PromptBuilder {

    /**
     * 통일 프롬프트 — 객체 크롭·전체 장면 모두 동일.
     * label 은 프롬프트에서 제외 (영어 label 혼입으로 hallucination 증가 관찰).
     */
    private const val DESCRIBE_PROMPT =
        "이 이미지를 한국어로 40자 이내 한 문장으로 구체적으로 설명해줘. 마침표로 끝내."

    /** 객체 크롭 설명 요청. (label 은 후처리·메타데이터용으로만 유지, 프롬프트에 주입 안 함) */
    fun buildVisionPrompt(@Suppress("UNUSED_PARAMETER") label: String): String = DESCRIBE_PROMPT

    /** 전체 장면 설명. 객체 미검출 시 사용. */
    fun buildScenePrompt(): String = DESCRIBE_PROMPT

    /**
     * 모델 응답 후처리.
     * - 마크다운 별표 제거
     * - 괄호 안 영어 번역 제거
     * - 반복 token 할루시네이션(예: "구로 구로 구로...") 감지 시 빈 문자열 반환 → 호출자가 fallback 사용
     * - 여러 문장이면 첫 문장만
     * - 공백 정리
     */
    fun cleanResponse(raw: String): String {
        var text = raw.trim()

        // 마크다운 강조 제거
        text = text.replace(Regex("""\*+"""), "")

        // 괄호 안 영어 번역 제거: "한국어 문장. (English translation.)"
        text = text.replace(Regex("""\([^)]*[A-Za-z][^)]*\)"""), "")

        // 반복 token loop 감지 → 할루시네이션으로 간주하고 버림
        if (isLoopyHallucination(text)) return ""

        // 첫 문장만 추출 (마침표/물음표/느낌표)
        val firstSentenceEnd = text.indexOfAny(charArrayOf('.', '。', '?', '!', '！', '？'))
        if (firstSentenceEnd > 0) {
            text = text.substring(0, firstSentenceEnd + 1)
        }

        // 공백 정리
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 반복 token 할루시네이션 감지 — Gemma 3n 이 greedy sampler 에서 가끔 짧은 token 을
     * 수십 번 반복하는 패턴(예: "구로 구로 구로 구로 구로..."). 유효 응답이 아님.
     *
     * 휴리스틱: 공백으로 split 한 토큰 중 4자 이하인 짧은 토큰이 연속 5회 이상 동일하게 등장하면
     * loop 로 간주. 정상적인 한국어 문장에서는 이런 패턴이 거의 없다.
     */
    fun isLoopyHallucination(text: String): Boolean {
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size < 6) return false
        var currentRun = 1
        for (i in 1 until tokens.size) {
            if (tokens[i] == tokens[i - 1] && tokens[i].length <= 4) {
                currentRun++
                if (currentRun >= 5) return true
            } else {
                currentRun = 1
            }
        }
        return false
    }
}

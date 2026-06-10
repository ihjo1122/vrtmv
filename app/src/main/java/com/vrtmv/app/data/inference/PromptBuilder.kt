package com.vrtmv.app.data.inference

object PromptBuilder {

    // 영어 label 을 한국어 프롬프트에 섞으면 hallucination 증가가 관찰돼 라벨 힌트 미주입.
    private const val OBJECT_DESCRIBE_PROMPT =
        "이 이미지를 한국어로 40자 이내 한 문장으로 구체적으로 설명해줘. 마침표로 끝내."

    // Gemma 3n 실측에서 짧고 부정형(예: "단정 금지") 지시는 오히려 단정형 명사 한 개로 응답하게
    // 만들어 휴지롤→"테이프" 같은 오인식을 강화함이 관찰됨. 자연스러운 톤이 안정적.
    // Gemma 4 에서도 동일한 톤 안정성 가설 유지 — 실측 후 새 패턴 보이면 재튜닝.
    private const val SCENE_DESCRIBE_PROMPT =
        "이 이미지에 보이는 장면을 한국어로 간결하게 1~2문장으로 설명해주세요."

    fun buildVisionPrompt(): String = OBJECT_DESCRIBE_PROMPT

    fun buildScenePrompt(): String = SCENE_DESCRIBE_PROMPT

    fun cleanResponse(raw: String, keepFirstSentenceOnly: Boolean = true): String {
        var text = raw.trim()
        text = text.replace(Regex("""\*+"""), "")
        // "한국어 문장. (English translation.)" 같은 괄호 영어 번역 병기 제거
        text = text.replace(Regex("""\([^)]*[A-Za-z][^)]*\)"""), "")

        if (isLoopyHallucination(text)) return ""

        if (keepFirstSentenceOnly) {
            val firstSentenceEnd = text.indexOfAny(charArrayOf('.', '。', '?', '!', '！', '？'))
            if (firstSentenceEnd > 0) {
                text = text.substring(0, firstSentenceEnd + 1)
            }
        }

        return text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Gemma 계열이 greedy sampler 에서 빠지는 두 종류 루프 차단 (Gemma 3n 실측 기반,
     * Gemma 4 에도 일반화 적용):
     *   1) 동일 짧은(≤4자) 토큰 5회 연속 — "구로 구로 구로..." 류
     *   2) 토큰 ≥12개일 때 unique 비율 < 0.45 또는 ":"/"**" 메타 토큰 ≥ 40%
     *      — "1번: 2번: 3번:..." / "**사람들:** **배경:**..." 류 패턴 루프
     * 정상 한국어 문장은 unique 비율 > 0.5, 메타 토큰 < 30%.
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

        if (tokens.size >= 12) {
            val uniqueRatio = tokens.distinct().size.toDouble() / tokens.size
            if (uniqueRatio < 0.45) return true

            val metaTokenCount = tokens.count {
                it.endsWith(":") || it.endsWith("：") || it.startsWith("**") || it.endsWith("**")
            }
            if (metaTokenCount.toDouble() / tokens.size >= 0.4) return true
        }

        return false
    }
}

package com.vrtmv.app.data.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 발표용 비교 표 한 행 — 항목 / 설명 / 시작 값 / 종료 값. */
data class ComparisonRow(
    val label: String,
    val description: String,
    val startValue: String,
    val endValue: String
)

/** 결과 요약 한 행 — 항목 / 설명 / 값. */
data class ResultRow(val label: String, val description: String, val value: String)

data class ExperimentMetric(
    // ── 시작 시점 스냅샷 (트리거 직후)
    val epochMs: Long,                       // == startEpochMs, PNG 파일명/정렬용
    val isoTimestamp: String,                // startEpoch 의 ISO 표현
    val captureMode: CaptureMode,
    val startCameraFps: Float,
    val triggerSource: String,
    val tapPointInView: Pair<Float, Float>?,
    val frameWidth: Int,
    val frameHeight: Int,
    val startJavaHeapMb: Float,
    val startNativeHeapMb: Float,
    val maxJavaHeapMb: Float,

    // ── 종료 시점 스냅샷 (추론 완료 직후)
    val endEpochMs: Long,
    val endCameraFps: Float,
    val endJavaHeapMb: Float,
    val endNativeHeapMb: Float,
    val detectionTotalMs: Long,
    val mediaPipeMs: Long,
    val yoloMs: Long,
    val mediaPipeObjectCount: Int,
    val selectedLabelMediaPipe: String?,
    val selectedConfidenceMediaPipe: Float?,
    val selectedLabelFinal: String?,
    val selectedConfidenceFinal: Float?,
    val cascadeUsedYolo: Boolean,
    val vlmInputWidth: Int,
    val vlmInputHeight: Int,
    val vlmPreprocessMs: Long,
    val vlmCreateConvMs: Long,
    val vlmSendMessageMs: Long,
    val vlmTotalMs: Long,
    val vlmResponseCharCount: Int,
    val vlmResponseText: String,
    val savedFilePath: String? = null
) {
    val capturedRegionShort: String
        get() = when {
            captureMode.isObjectMode && selectedLabelFinal != null -> "CROP"
            else -> "FRAME"
        }

    val elapsedTotalMs: Long get() = endEpochMs - epochMs
    val deltaJavaHeapMb: Float get() = endJavaHeapMb - startJavaHeapMb
    val deltaNativeHeapMb: Float get() = endNativeHeapMb - startNativeHeapMb
    val decodeCharsPerSec: Float
        get() = if (vlmSendMessageMs > 0) vlmResponseCharCount * 1000f / vlmSendMessageMs else 0f

    /**
     * 발표용 비교 표 — 왼쪽 타이틀, 가운데 [시작], 오른쪽 [종료].
     * 시작/종료 둘 다 의미가 있는 항목만 포함. 그 외는 [toResultSummary] 로.
     */
    fun toComparisonRows(): List<ComparisonRow> {
        val tStart = TIME_FMT.format(Date(epochMs))
        val tEnd = TIME_FMT.format(Date(endEpochMs))
        val deltaJava = "%+.1f".format(deltaJavaHeapMb)
        val deltaNative = "%+.1f".format(deltaNativeHeapMb)
        return listOf(
            ComparisonRow("Time", "측정 시각 (시:분:초.밀리)", tStart, tEnd),
            ComparisonRow("Elapsed", "트리거→화면 결과 노출까지 경과", "0 ms", "$elapsedTotalMs ms"),
            ComparisonRow(
                "Java heap",
                "JVM heap 사용량 (Δ 는 증감)",
                "%.1f MB".format(startJavaHeapMb),
                "%.1f MB ($deltaJava)".format(endJavaHeapMb)
            ),
            ComparisonRow(
                "Native heap",
                "Native 메모리 사용량 (Δ 는 증감)",
                "%.1f MB".format(startNativeHeapMb),
                "%.1f MB ($deltaNative)".format(endNativeHeapMb)
            ),
            ComparisonRow(
                "Camera FPS",
                "카메라 프레임 레이트",
                "%.1f".format(startCameraFps),
                "%.1f".format(endCameraFps)
            )
        )
    }

    /**
     * 시작/종료 비교가 무의미한 결과성 항목 — 단일 값으로 표 아래에 작게 표기.
     */
    fun toResultSummary(): List<ResultRow> {
        val rows = mutableListOf<ResultRow>()
        rows += ResultRow("Mode", "캡처 모드", captureMode.displayName)
        rows += ResultRow("VLM total", "VLM 추론 합계 시간", "$vlmTotalMs ms")
        rows += ResultRow("VLM input", "비전 인코더 입력 해상도", "$vlmInputWidth × $vlmInputHeight px")
        if (captureMode.isObjectMode) {
            val label = selectedLabelFinal ?: "--"
            val conf = selectedConfidenceFinal?.let { "%.2f".format(it) } ?: "--"
            rows += ResultRow("Selected", "선택된 객체 (라벨/신뢰도)", "$label ($conf)")
            if (detectionTotalMs > 0) {
                rows += ResultRow("Detection", "MediaPipe + YOLO 합계 시간", "$detectionTotalMs ms")
            }
        }
        rows += ResultRow("Decode", "디코딩 처리량 (글자/초)", "%.1f chars/s".format(decodeCharsPerSec))
        rows += ResultRow("Response", "응답 글자 수", "${vlmResponseCharCount} chars")
        return rows
    }

    companion object {
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun isoNow(epochMs: Long): String = ISO_FORMAT.format(Date(epochMs))
    }
}

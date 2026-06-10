package com.vrtmv.app.data.recording

data class RecordItem(
    val filePath: String,
    val epochMs: Long,
    val captureMode: CaptureMode,
    val fileSizeBytes: Long,
    /** 트리거 → 추론 완료까지 측정된 총 경과 시간(ms). 사이드카에 endEpochMs 가 없는 옛 기록은 null. */
    val elapsedMs: Long? = null
)

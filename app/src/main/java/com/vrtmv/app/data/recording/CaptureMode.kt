package com.vrtmv.app.data.recording

enum class CaptureMode(val id: String, val displayName: String) {
    OBJECT_DETECTION("object", "객체 검출"),
    OBJECT_DETECTION_NO_PADDING("object-nopad", "객체 검출 (패딩 없음)"),
    FULL_FRAME("full", "전체 이미지");

    val isObjectMode: Boolean
        get() = this == OBJECT_DETECTION || this == OBJECT_DETECTION_NO_PADDING

    companion object {
        fun fromId(id: String?): CaptureMode =
            entries.firstOrNull { it.id == id } ?: OBJECT_DETECTION
    }
}

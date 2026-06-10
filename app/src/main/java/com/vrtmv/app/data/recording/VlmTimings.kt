package com.vrtmv.app.data.recording

data class VlmTimings(
    val preprocessMs: Long,
    val createConvMs: Long,
    val sendMessageMs: Long,
    val totalMs: Long
)

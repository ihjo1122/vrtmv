package com.vrtmv.app.domain.model

data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val quantization: String,
    val expectedSizeMB: Int
) {
    val fileExtension: String
        get() = fileName.substringAfterLast('.', "litertlm")
}

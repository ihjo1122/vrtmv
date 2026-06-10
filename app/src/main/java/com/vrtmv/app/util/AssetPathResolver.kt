package com.vrtmv.app.util

import android.content.Context
import android.util.Log
import com.vrtmv.app.domain.model.AssetInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 보조 자산 경로 (`getExternalFilesDir/vrtmv-assets/`). 앱 전용이라 MANAGE_EXTERNAL_STORAGE
 * 불필요하고 DownloadManager.setDestinationInExternalFilesDir 가 직접 쓸 수 있음.
 * VLM 모델은 별도로 [ModelPathResolver] 가 관리.
 */
@Singleton
class AssetPathResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AssetPath"
        const val ASSET_SUBDIR = "vrtmv-assets"
        // 부분 다운로드/0바이트 파일 배제용 최소 크기
        private const val MIN_ASSET_SIZE_BYTES = 1024L
    }

    fun assetsDir(): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir unavailable")
        val dir = File(base, ASSET_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun findAssetPath(fileName: String): String? {
        val file = File(assetsDir(), fileName)
        return if (file.exists() && file.length() >= MIN_ASSET_SIZE_BYTES) {
            file.absolutePath
        } else {
            if (file.exists()) {
                Log.w(TAG, "부분 다운로드 파일 무시: ${file.absolutePath} (${file.length()}B)")
            }
            null
        }
    }

    fun assetExists(assetInfo: AssetInfo): Boolean = findAssetPath(assetInfo.fileName) != null
}

package com.vrtmv.app.util

import android.content.Context
import android.util.Log
import com.vrtmv.app.domain.model.AssetInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 보조 자산 파일 경로 탐색 유틸.
 *
 * 저장 위치: `context.getExternalFilesDir(null)/vrtmv-assets/{fileName}`
 * - 앱 전용 외부 저장소 — MANAGE_EXTERNAL_STORAGE 권한 불필요
 * - `DownloadManager.Request.setDestinationInExternalFilesDir()`가 직접 쓸 수 있음
 * - 앱 제거 시 OS가 자동 정리
 *
 * VLM 모델(수 GB)은 [ModelPathResolver]가 `Download/vrtmv/`에서 별도 관리 — 경로 분리.
 */
@Singleton
class AssetPathResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AssetPath"
        const val ASSET_SUBDIR = "vrtmv-assets"
        // 부분 다운로드·0바이트 파일 배제용 최소 크기 (1KB)
        private const val MIN_ASSET_SIZE_BYTES = 1024L
    }

    /** 자산 저장 디렉터리 반환 (없으면 생성). */
    fun assetsDir(): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir unavailable")
        val dir = File(base, ASSET_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 파일의 절대 경로를 반환. 존재하지 않거나 크기가 유효하지 않으면 null. */
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

    /** 자산이 사용 가능한 상태로 존재하는지. */
    fun assetExists(assetInfo: AssetInfo): Boolean = findAssetPath(assetInfo.fileName) != null
}

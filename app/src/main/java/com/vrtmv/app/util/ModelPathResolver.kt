package com.vrtmv.app.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vrtmv.app.domain.model.ModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 모델 파일 경로 탐색 유틸.
 * ModelDownloadManager와 LiteRtLmEngine에서 공통 사용.
 *
 * 탐색 순서:
 * 1. 앱 전용 외부 저장소 `getExternalFilesDir(null)/vrtmv/{fileName}` — 권한 불필요(기본 저장 위치)
 * 2. 앱 내부 저장소 `filesDir/{modelId}.{ext}` — 수동 배치용
 * 3. 공용 `Download/vrtmv/{fileName}` — MANAGE_EXTERNAL_STORAGE 허용 시에만 동작(레거시/adb push)
 */
@Singleton
class ModelPathResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelPath"
        const val MIN_MODEL_SIZE_BYTES = 100_000_000L
        const val MODEL_SUBDIR = "vrtmv"
    }

    /** 앱 전용 외부 저장소의 모델 디렉터리. DownloadManager가 권한 없이 쓸 수 있는 경로. */
    fun modelsDir(): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir unavailable")
        val dir = File(base, MODEL_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 모델 파일의 절대 경로를 반환한다.
     * @return 모델 파일 경로, 없으면 null
     */
    fun findModelPath(modelInfo: ModelInfo): String? {
        // 1순위: 앱 전용 외부 저장소 (권한 불필요 — 이번 버전부터의 기본 저장 위치)
        try {
            val appExternalModel = File(modelsDir(), modelInfo.fileName)
            Log.d(TAG, "앱 외부 저장소 확인: ${appExternalModel.absolutePath}, exists=${appExternalModel.exists()}, size=${appExternalModel.length()}")
            if (appExternalModel.exists() && appExternalModel.length() > MIN_MODEL_SIZE_BYTES) {
                return appExternalModel.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "앱 외부 저장소 탐색 실패", e)
        }

        // 2순위: 앱 내부 저장소 (수동 배치용)
        val internalModel = File(context.filesDir, "${modelInfo.id}.${modelInfo.fileExtension}")
        Log.d(TAG, "내부 저장소 확인: ${internalModel.absolutePath}, exists=${internalModel.exists()}, size=${internalModel.length()}")
        if (internalModel.exists() && internalModel.length() > MIN_MODEL_SIZE_BYTES) {
            return internalModel.absolutePath
        }

        // 3순위: 공용 Download/vrtmv/ — MANAGE_EXTERNAL_STORAGE 허용 시에만 접근 가능(레거시)
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val vrtmvModel = File(downloadDir, "$MODEL_SUBDIR/${modelInfo.fileName}")
            Log.d(TAG, "Download 확인(legacy): ${vrtmvModel.absolutePath}, exists=${vrtmvModel.exists()}, size=${vrtmvModel.length()}")
            if (vrtmvModel.exists() && vrtmvModel.length() > MIN_MODEL_SIZE_BYTES) {
                return vrtmvModel.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download 폴더 탐색 실패", e)
        }

        Log.w(TAG, "모델 파일 없음: ${modelInfo.fileName}")
        return null
    }

    /** 모델 파일이 존재하는지 확인한다. */
    fun modelExists(modelInfo: ModelInfo): Boolean = findModelPath(modelInfo) != null
}

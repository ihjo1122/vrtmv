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
 * VLM 모델 파일 경로 탐색. 우선순위:
 *   1) `getExternalFilesDir/vrtmv/` — 권한 불필요 기본 저장소
 *   2) `filesDir/{modelId}.{ext}` — 수동 배치용
 *   3) `/sdcard/Download/vrtmv/` — MANAGE_EXTERNAL_STORAGE 허용 시 레거시(adb push)
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

    fun modelsDir(): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir unavailable")
        val dir = File(base, MODEL_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun findModelPath(modelInfo: ModelInfo): String? {
        try {
            val appExternalModel = File(modelsDir(), modelInfo.fileName)
            Log.d(TAG, "앱 외부 저장소 확인: ${appExternalModel.absolutePath}, exists=${appExternalModel.exists()}, size=${appExternalModel.length()}")
            if (appExternalModel.exists() && appExternalModel.length() > MIN_MODEL_SIZE_BYTES) {
                return appExternalModel.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "앱 외부 저장소 탐색 실패", e)
        }

        val internalModel = File(context.filesDir, "${modelInfo.id}.${modelInfo.fileExtension}")
        Log.d(TAG, "내부 저장소 확인: ${internalModel.absolutePath}, exists=${internalModel.exists()}, size=${internalModel.length()}")
        if (internalModel.exists() && internalModel.length() > MIN_MODEL_SIZE_BYTES) {
            return internalModel.absolutePath
        }

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

    fun modelExists(modelInfo: ModelInfo): Boolean = findModelPath(modelInfo) != null
}

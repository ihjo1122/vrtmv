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
 * 1. 앱 내부 저장소 files/{modelId}.{ext}
 * 2. Download/vrtmv/{fileName}
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

    /**
     * 모델 파일의 절대 경로를 반환한다.
     * @return 모델 파일 경로, 없으면 null
     */
    fun findModelPath(modelInfo: ModelInfo): String? {
        // 1순위: 앱 내부 저장소
        val internalModel = File(context.filesDir, "${modelInfo.id}.${modelInfo.fileExtension}")
        Log.d(TAG, "내부 저장소 확인: ${internalModel.absolutePath}, exists=${internalModel.exists()}, size=${internalModel.length()}")
        if (internalModel.exists() && internalModel.length() > MIN_MODEL_SIZE_BYTES) {
            return internalModel.absolutePath
        }

        // 2순위: Download/vrtmv/{fileName}
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val vrtmvModel = File(downloadDir, "$MODEL_SUBDIR/${modelInfo.fileName}")
            Log.d(TAG, "Download 확인: ${vrtmvModel.absolutePath}, exists=${vrtmvModel.exists()}, size=${vrtmvModel.length()}")
            Log.d(TAG, "isExternalStorageManager=${Environment.isExternalStorageManager()}")
            if (vrtmvModel.exists() && vrtmvModel.length() > MIN_MODEL_SIZE_BYTES) {
                Log.d(TAG, "Download/vrtmv에서 모델 발견: ${modelInfo.fileName}")
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

package com.vrtmv.app.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 모델 다운로드 관리자.
 * Android DownloadManager를 사용하여 HuggingFace에서 .task 모델을 다운로드한다.
 * 다운로드 경로: Download/vrtmv/gemma3-1b-it-int4.task
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownload"
        const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
        const val MODEL_SUBDIR = "vrtmv"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 모델 파일이 이미 존재하는지 확인한다.
     * 확인 순서:
     * 1. 앱 내부 저장소 (files/model.task)
     * 2. Download/vrtmv/gemma3-1b-it-int4.task
     */
    suspend fun modelExists(): Boolean = withContext(Dispatchers.IO) {
        val internalModel = File(context.filesDir, "model.task")
        if (internalModel.exists()) {
            Log.d(TAG, "내부 저장소에 모델 존재")
            return@withContext true
        }

        val downloadModel = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$MODEL_SUBDIR/$MODEL_FILENAME"
        )
        if (downloadModel.exists() && downloadModel.length() > 100_000_000) {
            Log.d(TAG, "Download/vrtmv에 모델 존재: ${downloadModel.length() / 1024 / 1024}MB")
            return@withContext true
        }

        false
    }

    /**
     * 이미 진행 중인 다운로드가 있는지 확인한다.
     * @return 진행 중인 다운로드 ID, 없으면 null
     */
    fun findExistingDownload(): Long? {
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
        val cursor = downloadManager.query(query)
        cursor?.use {
            while (it.moveToNext()) {
                val uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                if (uri == MODEL_URL) {
                    return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    /**
     * 모델 다운로드를 시작한다.
     * @return 다운로드 ID
     */
    fun startDownload(): Long {
        // 기존 진행 중인 다운로드가 있으면 그대로 사용
        findExistingDownload()?.let { return it }

        // vrtmv 서브디렉토리에 다운로드
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("VRTMV AI 모델 다운로드")
            .setDescription("gemma3-1b-it-int4.task")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$MODEL_SUBDIR/$MODEL_FILENAME"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "다운로드 시작: ID=$downloadId")
        return downloadId
    }

    /**
     * 다운로드 진행률을 관찰한다.
     * 500ms 간격으로 폴링하여 Flow로 방출.
     */
    fun observeProgress(downloadId: Long): Flow<DownloadProgress> = flow {
        while (true) {
            val progress = queryProgress(downloadId)
            emit(progress)

            if (progress.status == DownloadManager.STATUS_SUCCESSFUL ||
                progress.status == DownloadManager.STATUS_FAILED
            ) {
                break
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO)

    private fun queryProgress(downloadId: Long): DownloadProgress {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor? = downloadManager.query(query)
        cursor?.use {
            if (it.moveToFirst()) {
                val bytesDownloaded =
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val totalBytes =
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val status =
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason =
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

                return DownloadProgress(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    status = status,
                    reason = reason
                )
            }
        }
        return DownloadProgress(0, -1, DownloadManager.STATUS_FAILED, 0)
    }
}

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: Int,
    val reason: Int
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f

    val downloadedMB: Int
        get() = (bytesDownloaded / 1024 / 1024).toInt()

    val totalMB: Int
        get() = if (totalBytes > 0) (totalBytes / 1024 / 1024).toInt() else 0

    val isComplete: Boolean
        get() = status == DownloadManager.STATUS_SUCCESSFUL

    val isFailed: Boolean
        get() = status == DownloadManager.STATUS_FAILED
}

package com.vrtmv.app.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.util.ModelPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 멀티 모델 다운로드 관리자.
 * Android DownloadManager를 사용하여 모델을 다운로드한다.
 * 다운로드 경로: Download/vrtmv/{fileName}
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathResolver: com.vrtmv.app.util.ModelPathResolver
) {
    companion object {
        private const val TAG = "ModelDownload"
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // ── 기본 모델용 (기존 IntroScreen 호환) ──────────────────────

    /** 기본 모델 파일이 이미 존재하는지 확인 */
    suspend fun modelExists(): Boolean = modelExists(ModelRegistry.getDefaultModel())

    /** 기본 모델 다운로드 시작 */
    fun startDownload(): Long = startDownload(ModelRegistry.getDefaultModel())

    // ── 멀티 모델 지원 ──────────────────────────────────────────

    /** 지정 모델 파일이 존재하는지 확인 (ModelPathResolver에 위임). */
    suspend fun modelExists(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        pathResolver.modelExists(modelInfo)
    }

    /** 지정 모델의 진행 중인 다운로드 확인 */
    fun findExistingDownload(modelInfo: ModelInfo): Long? {
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
        val cursor = downloadManager.query(query)
        cursor?.use {
            while (it.moveToNext()) {
                val uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                if (uri == modelInfo.downloadUrl) {
                    return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    /**
     * 지정 모델 다운로드를 시작한다.
     * @return 다운로드 ID
     * @throws InsufficientStorageException 저장공간 부족 시
     */
    fun startDownload(modelInfo: ModelInfo): Long {
        // 다운로드 URL이 비어있으면 수동 배치 모델
        if (modelInfo.downloadUrl.isBlank()) {
            throw ManualInstallRequiredException(modelInfo)
        }

        // 기존 진행 중인 다운로드가 있으면 그대로 사용
        findExistingDownload(modelInfo)?.let { return it }

        // 저장공간 확인
        val requiredBytes = modelInfo.expectedSizeMB.toLong() * 1024 * 1024
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val stat = StatFs(downloadDir.absolutePath)
        val availableBytes = stat.availableBytes
        if (availableBytes < requiredBytes) {
            throw InsufficientStorageException(
                required = modelInfo.expectedSizeMB,
                available = (availableBytes / 1024 / 1024).toInt()
            )
        }

        val request = DownloadManager.Request(Uri.parse(modelInfo.downloadUrl))
            .setTitle("VRTMV 모델: ${modelInfo.displayName}")
            .setDescription(modelInfo.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "${ModelPathResolver.MODEL_SUBDIR}/${modelInfo.fileName}"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "다운로드 시작: ${modelInfo.displayName} ID=$downloadId")
        return downloadId
    }

    /** 다운로드 진행률 관찰 (500ms 폴링) */
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

/** 수동 배치 필요 예외 (downloadUrl이 비어있는 모델) */
class ManualInstallRequiredException(
    val modelInfo: ModelInfo
) : Exception("수동 설치 필요: adb push ${modelInfo.fileName} /sdcard/Download/vrtmv/")

/** 저장공간 부족 예외 */
class InsufficientStorageException(
    val required: Int,
    val available: Int
) : Exception("저장공간 부족: ${required}MB 필요, ${available}MB 사용 가능")

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

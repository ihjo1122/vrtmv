package com.vrtmv.app.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.vrtmv.app.BuildConfig
import com.vrtmv.app.domain.model.AssetInfo
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.util.AssetPathResolver
import com.vrtmv.app.util.ModelPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
    private val pathResolver: ModelPathResolver,
    private val assetPathResolver: AssetPathResolver
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
    suspend fun startDownload(): Long = startDownload(ModelRegistry.getDefaultModel())

    /**
     * HF 리다이렉트 선행 해석.
     * Android DownloadManager는 3xx 응답을 따라갈 때 원본 Authorization 헤더를 재전송하는데,
     * HF는 S3 pre-signed URL(X-Amz-Signature 포함)로 302 리다이렉트하므로 S3가
     * "Only one auth mechanism allowed" 400 에러로 거부한다.
     * HEAD 요청으로 pre-signed URL만 미리 얻어 DownloadManager에 넘기면 이 충돌을 피할 수 있다.
     * 해석 실패 시 원본 URL 반환 — 호출자는 그 경우 auth 헤더를 유지한다.
     */
    private suspend fun resolveHfRedirect(originalUrl: String): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.HF_TOKEN.isEmpty() || !originalUrl.contains("huggingface.co")) {
            return@withContext null
        }
        try {
            val conn = (URL(originalUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.HF_TOKEN}")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrEmpty()) {
                        Log.d(TAG, "HF 리다이렉트 해석 성공")
                        return@withContext location
                    }
                }
                Log.w(TAG, "HF 리다이렉트 해석 실패(code=$code), 원본 URL 사용")
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HF 리다이렉트 해석 중 예외, 원본 URL 사용", e)
            null
        }
    }

    // ── 멀티 모델 지원 ──────────────────────────────────────────

    /** 지정 모델 파일이 존재하는지 확인 (ModelPathResolver에 위임). */
    suspend fun modelExists(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        pathResolver.modelExists(modelInfo)
    }

    /** 지정 모델의 진행 중인 다운로드 확인. 리다이렉트 해석 후 URL이 달라지므로 파일명(description)으로 매칭. */
    fun findExistingDownload(modelInfo: ModelInfo): Long? {
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
        val cursor = downloadManager.query(query)
        cursor?.use {
            while (it.moveToNext()) {
                val description = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION))
                if (description == modelInfo.fileName) {
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
    suspend fun startDownload(modelInfo: ModelInfo): Long {
        // 다운로드 URL이 비어있으면 수동 배치 모델
        if (modelInfo.downloadUrl.isBlank()) {
            throw ManualInstallRequiredException(modelInfo)
        }

        // 기존 진행 중인 다운로드가 있으면 그대로 사용
        findExistingDownload(modelInfo)?.let { return it }

        // 저장공간 확인 — 앱 전용 외부 저장소 기준
        val requiredBytes = modelInfo.expectedSizeMB.toLong() * 1024 * 1024
        val modelsDir = pathResolver.modelsDir()
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBytes
        if (availableBytes < requiredBytes) {
            throw InsufficientStorageException(
                required = modelInfo.expectedSizeMB,
                available = (availableBytes / 1024 / 1024).toInt()
            )
        }

        // HF 리다이렉트 선행 해석 — 성공 시 pre-signed URL을 받고 auth 헤더 없이 enqueue
        val resolvedUrl = resolveHfRedirect(modelInfo.downloadUrl)
        val finalUrl = resolvedUrl ?: modelInfo.downloadUrl

        // 앱 전용 외부 저장소에 저장 — 권한 불필요, File API로 직접 접근 가능
        val request = DownloadManager.Request(Uri.parse(finalUrl))
            .setTitle("VRTMV 모델: ${modelInfo.displayName}")
            .setDescription(modelInfo.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                null,
                "${ModelPathResolver.MODEL_SUBDIR}/${modelInfo.fileName}"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        // 해석 실패(공개 리포 등) 시에만 원본 URL + auth 헤더 방식 유지
        if (resolvedUrl == null && BuildConfig.HF_TOKEN.isNotEmpty() && finalUrl.contains("huggingface.co")) {
            request.addRequestHeader("Authorization", "Bearer ${BuildConfig.HF_TOKEN}")
        }

        val downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "다운로드 시작: ${modelInfo.displayName} ID=$downloadId (resolved=${resolvedUrl != null})")
        return downloadId
    }

    // ── 보조 자산(YOLO, 제스처 등) 다운로드 ──────────────────────

    /** 자산이 이미 존재하는지 (부분 다운로드 배제 포함). */
    suspend fun assetExists(asset: AssetInfo): Boolean = withContext(Dispatchers.IO) {
        assetPathResolver.assetExists(asset)
    }

    /** 진행 중인 자산 다운로드가 있으면 해당 ID 반환 (파일명으로 매칭). */
    fun findExistingAssetDownload(asset: AssetInfo): Long? {
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
        val cursor = downloadManager.query(query)
        cursor?.use {
            while (it.moveToNext()) {
                val description = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION))
                if (description == asset.fileName) {
                    return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    /**
     * 자산 다운로드를 시작한다.
     * 저장 위치: `context.getExternalFilesDir(null)/vrtmv-assets/{fileName}` (앱 전용, 권한 불필요)
     */
    suspend fun startAssetDownload(asset: AssetInfo): Long {
        findExistingAssetDownload(asset)?.let { return it }

        // HF 리다이렉트 선행 해석 (모델 다운로드와 동일 로직)
        val resolvedUrl = resolveHfRedirect(asset.downloadUrl)
        val finalUrl = resolvedUrl ?: asset.downloadUrl

        val request = DownloadManager.Request(Uri.parse(finalUrl))
            .setTitle("VRTMV 자산: ${asset.displayName}")
            .setDescription(asset.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                null,
                "${AssetPathResolver.ASSET_SUBDIR}/${asset.fileName}"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        if (resolvedUrl == null && BuildConfig.HF_TOKEN.isNotEmpty() && finalUrl.contains("huggingface.co")) {
            request.addRequestHeader("Authorization", "Bearer ${BuildConfig.HF_TOKEN}")
        }

        val downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "자산 다운로드 시작: ${asset.displayName} ID=$downloadId (resolved=${resolvedUrl != null})")
        return downloadId
    }

    // ── 공통 진행률 관찰 ─────────────────────────────────────────

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

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

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathResolver: ModelPathResolver,
    private val assetPathResolver: AssetPathResolver
) {
    companion object {
        private const val TAG = "ModelDownload"
        // HF 리다이렉트 결과는 S3 pre-signed URL 이라 자체 만료가 있어 보수적으로 30분 캐시.
        private const val HF_REDIRECT_CACHE_TTL_MS = 30L * 60 * 1000
        private const val HF_PREFS = "vrtmv_download_cache"
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val redirectPrefs by lazy {
        context.getSharedPreferences(HF_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * HF 리다이렉트 선행 해석.
     * Android DownloadManager 는 3xx 응답에서 원본 Authorization 헤더를 재전송하는데
     * HF 는 S3 pre-signed URL(X-Amz-Signature 포함)로 302 리다이렉트하므로 S3 가
     * "Only one auth mechanism allowed" 400 으로 거부한다. HEAD 로 pre-signed URL 만
     * 미리 얻어 DownloadManager 에 넘기면 충돌 회피. 해석 실패 시 null 반환 → 호출자가
     * 원본 URL 로 폴백하며 auth 헤더를 유지한다.
     */
    private suspend fun resolveHfRedirect(originalUrl: String): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.HF_TOKEN.isEmpty() || !originalUrl.contains("huggingface.co")) {
            return@withContext null
        }

        val cachedUrl = redirectPrefs.getString("url:$originalUrl", null)
        val cachedAt = redirectPrefs.getLong("ts:$originalUrl", 0L)
        if (cachedUrl != null && System.currentTimeMillis() - cachedAt < HF_REDIRECT_CACHE_TTL_MS) {
            Log.d(TAG, "HF 리다이렉트 캐시 적중")
            return@withContext cachedUrl
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
                        redirectPrefs.edit()
                            .putString("url:$originalUrl", location)
                            .putLong("ts:$originalUrl", System.currentTimeMillis())
                            .apply()
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

    suspend fun modelExists(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        pathResolver.modelExists(modelInfo)
    }

    // 진행 중 다운로드는 리다이렉트 해석 후 URL 이 달라지므로 fileName(description)으로 매칭한다.
    fun findExistingDownload(modelInfo: ModelInfo): Long? =
        findActiveDownload(modelInfo.fileName)

    suspend fun startDownload(modelInfo: ModelInfo): Long {
        if (modelInfo.downloadUrl.isBlank()) {
            throw ManualInstallRequiredException(modelInfo)
        }

        findExistingDownload(modelInfo)?.let { return it }

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

        return enqueue(
            fileName = modelInfo.fileName,
            displayName = modelInfo.displayName,
            titlePrefix = "모델",
            downloadUrl = modelInfo.downloadUrl,
            relativePath = "${ModelPathResolver.MODEL_SUBDIR}/${modelInfo.fileName}"
        )
    }

    suspend fun assetExists(asset: AssetInfo): Boolean = withContext(Dispatchers.IO) {
        assetPathResolver.assetExists(asset)
    }

    fun findExistingAssetDownload(asset: AssetInfo): Long? =
        findActiveDownload(asset.fileName)

    suspend fun startAssetDownload(asset: AssetInfo): Long {
        findExistingAssetDownload(asset)?.let { return it }
        return enqueue(
            fileName = asset.fileName,
            displayName = asset.displayName,
            titlePrefix = "자산",
            downloadUrl = asset.downloadUrl,
            relativePath = "${AssetPathResolver.ASSET_SUBDIR}/${asset.fileName}"
        )
    }

    private fun findActiveDownload(fileName: String): Long? {
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
        val cursor = downloadManager.query(query)
        cursor?.use {
            while (it.moveToNext()) {
                val description = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION))
                if (description == fileName) {
                    return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    private suspend fun enqueue(
        fileName: String,
        displayName: String,
        titlePrefix: String,
        downloadUrl: String,
        relativePath: String
    ): Long {
        val resolvedUrl = resolveHfRedirect(downloadUrl)
        val finalUrl = resolvedUrl ?: downloadUrl

        val request = DownloadManager.Request(Uri.parse(finalUrl))
            .setTitle("VRTMV $titlePrefix: $displayName")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, relativePath)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        // 해석 실패(공개 리포 등) 시에만 원본 URL + auth 헤더 방식 유지
        if (resolvedUrl == null && BuildConfig.HF_TOKEN.isNotEmpty() && finalUrl.contains("huggingface.co")) {
            request.addRequestHeader("Authorization", "Bearer ${BuildConfig.HF_TOKEN}")
        }

        val downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "다운로드 시작: $displayName ID=$downloadId (resolved=${resolvedUrl != null})")
        return downloadId
    }

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

class ManualInstallRequiredException(
    val modelInfo: ModelInfo
) : Exception("수동 설치 필요: adb push ${modelInfo.fileName} /sdcard/Download/vrtmv/")

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

package com.vrtmv.app.data.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "RecordRepo"
        private val FILENAME_REGEX = Regex("""^record_(\d+)\.png$""")
    }

    fun directory(): File = MetricRecorder.recordsDir(context)

    suspend fun listRecords(): List<RecordItem> = withContext(Dispatchers.IO) {
        val dir = directory()
        val files = dir.listFiles { f -> f.isFile && FILENAME_REGEX.matches(f.name) } ?: return@withContext emptyList()
        files.mapNotNull { f ->
            val match = FILENAME_REGEX.find(f.name) ?: return@mapNotNull null
            val epoch = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val meta = readSidecarMeta(f)
            RecordItem(
                filePath = f.absolutePath,
                epochMs = epoch,
                captureMode = meta?.captureMode ?: CaptureMode.OBJECT_DETECTION,
                fileSizeBytes = f.length(),
                elapsedMs = meta?.endEpochMs?.let { it - epoch }?.takeIf { it >= 0 }
            )
        }.sortedByDescending { it.epochMs }
    }

    private data class SidecarMeta(val captureMode: CaptureMode?, val endEpochMs: Long?)

    suspend fun loadThumbnail(path: String, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > targetWidth * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            Log.w(TAG, "썸네일 로드 실패: $path", e)
            null
        }
    }

    suspend fun loadFull(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.w(TAG, "원본 로드 실패: $path", e)
            null
        }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        val sidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
        sidecar.delete()
        file.delete()
    }

    suspend fun deleteMany(paths: Collection<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (p in paths) {
            val file = File(p)
            val sidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
            sidecar.delete()
            if (file.delete()) count++
        }
        count
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        val dir = directory()
        val files = dir.listFiles() ?: return@withContext 0
        var count = 0
        for (f in files) if (f.delete()) count++
        count
    }

    private fun readSidecarMeta(pngFile: File): SidecarMeta? {
        val sidecar = File(pngFile.parentFile, pngFile.nameWithoutExtension + ".txt")
        if (!sidecar.exists()) return null
        return try {
            var mode: CaptureMode? = null
            var endEpoch: Long? = null
            sidecar.useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("captureMode=") ->
                            mode = CaptureMode.fromId(line.substringAfter("captureMode="))
                        line.startsWith("endEpochMs=") ->
                            endEpoch = line.substringAfter("endEpochMs=").trim().toLongOrNull()
                    }
                    if (mode != null && endEpoch != null) return@useLines
                }
            }
            SidecarMeta(mode, endEpoch)
        } catch (e: Exception) {
            null
        }
    }
}

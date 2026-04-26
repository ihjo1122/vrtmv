package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.util.Log

/**
 * ARCore `Frame.acquireCameraImage()` 등 YUV_420_888 [Image] → ARGB_8888 [Bitmap] 변환.
 *
 * 후면 카메라 센서는 보통 landscape 방향이므로 portrait 앱에서 90° 회전이 필요하다.
 * BT.601 변환 행렬 사용. S23 Ultra 640x480 기준 ~10ms 내외.
 */
object YuvToBitmapConverter {
    private const val TAG = "YuvToBitmap"

    /**
     * @param image YUV_420_888 포맷 카메라 이미지. 호출자가 close 책임을 진다.
     * @param rotationDegrees 적용할 회전(센서→디스플레이). 후면 카메라 portrait는 보통 90.
     */
    fun convert(image: Image, rotationDegrees: Int = 90): Bitmap? {
        return try {
            val argb = yuv420ToArgbArray(image) ?: return null
            val sensor = Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            if (rotationDegrees == 0) {
                sensor
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(sensor, 0, 0, sensor.width, sensor.height, matrix, true)
                if (rotated != sensor) sensor.recycle()
                rotated
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV→Bitmap 변환 실패", e)
            null
        }
    }

    /**
     * YUV_420_888 → ARGB_8888 픽셀 배열.
     * planes[0]=Y(full), planes[1]=U(half×half), planes[2]=V(half×half).
     * pixelStride/rowStride 를 고려하여 인덱스 계산.
     */
    private fun yuv420ToArgbArray(image: Image): IntArray? {
        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // 버퍼를 byte 배열로 일괄 복사 — 직접 ByteBuffer.get(idx) 보다 훨씬 빠름
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yBytes = ByteArray(yBuffer.remaining())
        yBuffer.get(yBytes)
        val uBytes = ByteArray(uBuffer.remaining())
        uBuffer.get(uBytes)
        val vBytes = ByteArray(vBuffer.remaining())
        vBuffer.get(vBytes)

        val out = IntArray(width * height)
        var outIdx = 0
        for (row in 0 until height) {
            val yRowOffset = row * yRowStride
            val uvRowOffset = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val yIdx = yRowOffset + col
                if (yIdx >= yBytes.size) continue
                val uvIdx = uvRowOffset + (col shr 1) * uvPixelStride
                if (uvIdx >= uBytes.size || uvIdx >= vBytes.size) continue

                val y = yBytes[yIdx].toInt() and 0xFF
                val u = (uBytes[uvIdx].toInt() and 0xFF) - 128
                val v = (vBytes[uvIdx].toInt() and 0xFF) - 128

                // BT.601 (정수 근사, 8.16 고정소수점)
                val r1 = y + ((91881 * v) shr 16)              // 1.402
                val g1 = y - ((22554 * u + 46802 * v) shr 16)  // 0.344136 / 0.714136
                val b1 = y + ((116130 * u) shr 16)             // 1.772

                val r = if (r1 < 0) 0 else if (r1 > 255) 255 else r1
                val g = if (g1 < 0) 0 else if (g1 > 255) 255 else g1
                val b = if (b1 < 0) 0 else if (b1 > 255) 255 else b1

                out[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

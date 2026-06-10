package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.util.Log

/**
 * YUV_420_888 (ARCore acquireCameraImage) → ARGB_8888 Bitmap.
 * BT.601 8.16 고정소수점 변환. 후면 카메라 portrait 는 보통 90° 회전.
 */
object YuvToBitmapConverter {
    private const val TAG = "YuvToBitmap"

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

        // ByteBuffer.get(idx) 직접 호출은 느려, 일괄 복사 후 인덱스 접근.
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

                // BT.601: 1.402 / -0.344-0.714 / 1.772
                val r1 = y + ((91881 * v) shr 16)
                val g1 = y - ((22554 * u + 46802 * v) shr 16)
                val b1 = y + ((116130 * u) shr 16)

                val r = if (r1 < 0) 0 else if (r1 > 255) 255 else r1
                val g = if (g1 < 0) 0 else if (g1 > 255) 255 else g1
                val b = if (b1 < 0) 0 else if (b1 > 255) 255 else b1

                out[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

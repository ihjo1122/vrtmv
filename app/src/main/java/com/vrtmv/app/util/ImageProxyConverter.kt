package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageProxy] (RGBA_8888) → upright [Bitmap] 변환.
 *
 * 호출자가 반환된 비트맵의 recycle 책임을 진다.
 */
object ImageProxyConverter {
    private const val TAG = "ImageProxyConv"

    /**
     * RGBA_8888 출력 포맷의 ImageProxy 픽셀 버퍼를 ARGB_8888 Bitmap으로 복사하고,
     * 센서 회전(`imageInfo.rotationDegrees`)을 적용하여 upright 상태로 반환한다.
     *
     * @return 변환 성공 시 신규 Bitmap, 실패 시 null.
     */
    fun toUprightBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val raw = copyPixels(imageProxy) ?: return null
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation == 0) {
                raw
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                rotated
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageProxy→Bitmap 변환 실패", e)
            null
        }
    }

    private fun copyPixels(imageProxy: ImageProxy): Bitmap? {
        val planes = imageProxy.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
            if (cropped != bitmap) bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }
}

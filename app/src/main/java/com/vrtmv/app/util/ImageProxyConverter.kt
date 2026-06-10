package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy

object ImageProxyConverter {
    private const val TAG = "ImageProxyConv"

    // imageInfo.rotationDegrees 만큼 회전해 upright 로 반환. 호출자가 recycle 책임.
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
        // RGBA_8888 plane 은 행마다 padding 이 있을 수 있어 너비를 한 번 늘려 복사 후 재크롭.
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

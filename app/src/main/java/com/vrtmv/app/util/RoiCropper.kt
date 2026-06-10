package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RoiCropper {

    const val DEFAULT_PADDING_RATIO = 0.25f

    /** 박스+패딩이 적용된 source 영역(픽셀 좌표). [crop] 호출 없이 좌표만 알고 싶을 때 사용. */
    fun calcCropRect(source: Bitmap, boundingBox: RectF, paddingRatio: Float = DEFAULT_PADDING_RATIO): Rect {
        val safeRatio = max(0f, paddingRatio)
        val padW = boundingBox.width() * safeRatio
        val padH = boundingBox.height() * safeRatio
        val left = max(0, (boundingBox.left - padW).roundToInt())
        val top = max(0, (boundingBox.top - padH).roundToInt())
        val right = min(source.width, (boundingBox.right + padW).roundToInt())
        val bottom = min(source.height, (boundingBox.bottom + padH).roundToInt())
        return Rect(left, top, right, bottom)
    }

    fun crop(source: Bitmap, boundingBox: RectF, paddingRatio: Float = DEFAULT_PADDING_RATIO): Bitmap {
        val r = calcCropRect(source, boundingBox, paddingRatio)
        val w = max(1, r.width())
        val h = max(1, r.height())
        return Bitmap.createBitmap(source, r.left, r.top, w, h)
    }
}

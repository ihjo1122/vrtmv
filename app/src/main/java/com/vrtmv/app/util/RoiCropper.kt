package com.vrtmv.app.util

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 관심 영역(ROI) 이미지 크롭 유틸리티.
 * 선택된 객체의 바운딩박스를 기준으로 이미지를 잘라내어
 * VLM 추론 입력으로 사용할 크롭 이미지를 생성한다.
 */
object RoiCropper {

    /** 바운딩박스 주변에 추가하는 여백 비율 (15%) */
    private const val PADDING_RATIO = 0.15f

    /**
     * 원본 비트맵에서 바운딩박스 영역을 크롭한다.
     * 바운딩박스 주변에 15% 패딩을 추가하고, 이미지 경계를 넘지 않도록 클램핑.
     *
     * @param source 원본 비트맵 (회전 적용 완료된 upright 이미지)
     * @param boundingBox 크롭할 영역의 바운딩박스 (이미지 픽셀 좌표)
     * @return 크롭된 새 Bitmap (호출자가 recycle 책임)
     */
    fun crop(source: Bitmap, boundingBox: RectF): Bitmap {
        // 패딩 계산
        val padW = boundingBox.width() * PADDING_RATIO
        val padH = boundingBox.height() * PADDING_RATIO

        // 이미지 경계 내로 클램핑
        val left = max(0, (boundingBox.left - padW).roundToInt())
        val top = max(0, (boundingBox.top - padH).roundToInt())
        val right = min(source.width, (boundingBox.right + padW).roundToInt())
        val bottom = min(source.height, (boundingBox.bottom + padH).roundToInt())

        // 최소 1px 보장
        val w = max(1, right - left)
        val h = max(1, bottom - top)

        return Bitmap.createBitmap(source, left, top, w, h)
    }
}

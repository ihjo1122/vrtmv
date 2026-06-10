package com.vrtmv.app.data.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class MetricRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "MetricRec"
        const val RECORDS_DIR = "vrtmv-records"
        private const val OUTPUT_WIDTH = 1080

        private const val BG_COLOR = 0xFF0A0F18.toInt()
        private const val PANEL_COLOR = 0xFF101520.toInt()
        private const val ACCENT_COLOR = 0xFF4FE2FF.toInt()
        private const val DIVIDER_COLOR = 0xFF1F2937.toInt()
        private const val TEXT_PRIMARY = 0xFFE5E7EB.toInt()
        private const val TEXT_SECONDARY = 0xFF9CA3AF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        private val HEADER_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun recordsDir(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, RECORDS_DIR).apply { if (!exists()) mkdirs() }
        }
    }

    suspend fun saveRecord(capturedBitmap: Bitmap, metric: ExperimentMetric): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = recordsDir(context)
                val target = File(dir, "record_${metric.epochMs}.png")
                val composed = composeBitmap(capturedBitmap, metric)
                try {
                    FileOutputStream(target).use { out ->
                        composed.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } finally {
                    composed.recycle()
                }
                writeSidecar(target, metric)
                Log.i(TAG, "기록 저장: ${target.absolutePath} (${target.length()} bytes)")
                target.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "기록 저장 실패", e)
                null
            }
        }

    private fun composeBitmap(captured: Bitmap, metric: ExperimentMetric): Bitmap {
        val scaled = scaleToWidth(captured, OUTPUT_WIDTH)
        val w = OUTPUT_WIDTH
        val imgH = scaled.height
        val headerH = 96

        val comparisonRows = metric.toComparisonRows()
        val resultRows = metric.toResultSummary()
        val rowH = 50
        val tableHeaderH = 60
        val sectionGapH = 24
        val resultRowH = 40
        val resultHeadingH = 44

        val tableH = tableHeaderH + comparisonRows.size * rowH + 16
        val resultH = resultHeadingH + resultRows.size * resultRowH + 16
        val metricsH = tableH + sectionGapH + resultH

        val responseText = metric.vlmResponseText.ifBlank { "(응답 없음)" }
        val responsePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 32f
            color = WHITE
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val responseInnerWidth = w - 96
        val responseLayout = buildStaticLayout(responseText, responsePaint, responseInnerWidth)
        val responseH = responseLayout.height + 80

        val totalH = imgH + headerH + metricsH + responseH
        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BG_COLOR)

        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled != captured) scaled.recycle()

        val accentPaint = Paint().apply { color = ACCENT_COLOR }
        canvas.drawRect(0f, imgH.toFloat() - 1f, w.toFloat(), imgH.toFloat(), accentPaint)

        // 헤더 패널
        val panelPaint = Paint().apply { color = PANEL_COLOR }
        canvas.drawRect(0f, imgH.toFloat(), w.toFloat(), (imgH + headerH).toFloat(), panelPaint)
        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 38f
            color = ACCENT_COLOR
            isFakeBoldText = true
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        val subTitlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 26f
            color = TEXT_SECONDARY
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        canvas.drawText("VRTMV Experiment Record", 32f, (imgH + 44).toFloat(), titlePaint)
        val sub = "${HEADER_FMT.format(Date(metric.epochMs))}  ·  ${metric.captureMode.displayName.uppercase()}  ·  ${metric.capturedRegionShort}"
        canvas.drawText(sub, 32f, (imgH + 80).toFloat(), subTitlePaint)

        // ── 비교 표 (항목 / 설명 / 시작 / 종료) ──
        val tableHeaderPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 22f
            color = ACCENT_COLOR
            isFakeBoldText = true
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        val labelPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 22f
            color = TEXT_SECONDARY
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val descPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 18f
            color = TEXT_SECONDARY
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val valPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 22f
            color = TEXT_PRIMARY
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val dividerPaint = Paint().apply { color = DIVIDER_COLOR }

        val outerPad = 24
        val labelW = 140
        val descW = 410
        val valColW = (w - outerPad * 2 - labelW - descW) / 2
        val descColLeft = outerPad + labelW
        val startColLeft = descColLeft + descW
        val endColLeft = startColLeft + valColW
        val tableTop = imgH + headerH + 8

        // 헤더 행: [항목] [설명] [시작] [종료]
        val headerY = (tableTop + tableHeaderH - 18).toFloat()
        canvas.drawText("항목", outerPad.toFloat(), headerY, tableHeaderPaint)
        canvas.drawText("설명", descColLeft.toFloat(), headerY, tableHeaderPaint)
        drawCenteredText(canvas, "시작", startColLeft, valColW, headerY, tableHeaderPaint)
        drawCenteredText(canvas, "종료", endColLeft, valColW, headerY, tableHeaderPaint)
        canvas.drawRect(
            outerPad.toFloat(),
            (tableTop + tableHeaderH - 4).toFloat(),
            (w - outerPad).toFloat(),
            (tableTop + tableHeaderH - 2).toFloat(),
            Paint().apply { color = ACCENT_COLOR }
        )

        comparisonRows.forEachIndexed { idx, row ->
            val y = tableTop + tableHeaderH + idx * rowH + rowH - 16
            canvas.drawText(row.label, outerPad.toFloat(), y.toFloat(), labelPaint)
            val descTrunc = ellipsizeToWidth(row.description, descPaint, (descW - 12).toFloat())
            canvas.drawText(descTrunc, descColLeft.toFloat(), y.toFloat(), descPaint)
            drawCenteredText(canvas, row.startValue, startColLeft, valColW, y.toFloat(), valPaint)
            drawCenteredText(canvas, row.endValue, endColLeft, valColW, y.toFloat(), valPaint)
            if (idx < comparisonRows.size - 1) {
                val dy = (tableTop + tableHeaderH + (idx + 1) * rowH).toFloat()
                canvas.drawRect(outerPad.toFloat(), dy - 1f, (w - outerPad).toFloat(), dy, dividerPaint)
            }
        }

        // 세로 디바이더
        val dividerYTop = (tableTop + tableHeaderH - 8).toFloat()
        val dividerYBottom = (tableTop + tableHeaderH + comparisonRows.size * rowH).toFloat()
        for (xLine in listOf(descColLeft, startColLeft, endColLeft)) {
            canvas.drawRect(xLine.toFloat() - 0.5f, dividerYTop, xLine.toFloat() + 0.5f, dividerYBottom, dividerPaint)
        }

        // ── 결과 요약 (항목 / 설명 / 값) ──
        val resultTop = tableTop + tableH + sectionGapH
        val resultHeadingPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 22f
            color = ACCENT_COLOR
            isFakeBoldText = true
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        canvas.drawText("결과 요약", outerPad.toFloat(), (resultTop + resultHeadingH - 16).toFloat(), resultHeadingPaint)
        canvas.drawRect(
            outerPad.toFloat(),
            (resultTop + resultHeadingH - 4).toFloat(),
            (w - outerPad).toFloat(),
            (resultTop + resultHeadingH - 2).toFloat(),
            Paint().apply { color = ACCENT_COLOR.and(0x80FFFFFF.toInt()) }
        )

        val resultKeyPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 20f
            color = TEXT_SECONDARY
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val resultDescPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 16f
            color = TEXT_SECONDARY
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val resultValPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 20f
            color = TEXT_PRIMARY
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val resultLabelW = 130
        val resultDescW = 540
        val resultDescLeft = outerPad + resultLabelW
        val resultValLeft = resultDescLeft + resultDescW
        val resultValW = w - outerPad - resultValLeft
        resultRows.forEachIndexed { idx, row ->
            val y = resultTop + resultHeadingH + idx * resultRowH + resultRowH - 12
            canvas.drawText(row.label, outerPad.toFloat(), y.toFloat(), resultKeyPaint)
            val descTrunc = ellipsizeToWidth(row.description, resultDescPaint, (resultDescW - 12).toFloat())
            canvas.drawText(descTrunc, resultDescLeft.toFloat(), y.toFloat(), resultDescPaint)
            val valTrunc = ellipsizeToWidth(row.value, resultValPaint, (resultValW - 4).toFloat())
            val vw = resultValPaint.measureText(valTrunc)
            canvas.drawText(valTrunc, (w - outerPad).toFloat() - vw, y.toFloat(), resultValPaint)
        }

        // 응답 영역
        val respTop = imgH + headerH + metricsH
        canvas.drawRect(0f, respTop.toFloat(), w.toFloat(), (respTop + responseH).toFloat(), panelPaint)
        canvas.drawRect(32f, (respTop + 32).toFloat(), 38f, (respTop + responseH - 32).toFloat(), accentPaint)

        canvas.save()
        canvas.translate(64f, (respTop + 32).toFloat())
        responseLayout.draw(canvas)
        canvas.restore()

        return out
    }

    private fun drawCenteredText(canvas: Canvas, text: String, columnLeft: Int, columnW: Int, baselineY: Float, paint: TextPaint) {
        val truncated = ellipsizeToWidth(text, paint, (columnW - 16).toFloat())
        val tw = paint.measureText(truncated)
        val x = columnLeft + (columnW - tw) / 2
        canvas.drawText(truncated, x, baselineY, paint)
    }

    private fun ellipsizeToWidth(text: String, paint: TextPaint, maxWidth: Float): String {
        if (maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val cand = text.substring(0, mid) + "…"
            if (paint.measureText(cand) <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo <= 0) "…" else text.substring(0, lo) + "…"
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        val safeWidth = max(1, width)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(8f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        if (source.width == targetWidth) return source
        val ratio = targetWidth.toFloat() / source.width
        val newH = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, newH, true)
    }

    private fun writeSidecar(pngFile: File, metric: ExperimentMetric) {
        try {
            val sidecar = File(pngFile.parentFile, pngFile.nameWithoutExtension + ".txt")
            val sb = StringBuilder()
            sb.append("isoTimestamp=").append(metric.isoTimestamp).append('\n')
            sb.append("epochMs=").append(metric.epochMs).append('\n')
            sb.append("endEpochMs=").append(metric.endEpochMs).append('\n')
            sb.append("captureMode=").append(metric.captureMode.id).append('\n')

            sb.append("\n# comparison (label\tdescription\tstart\tend)\n")
            for (row in metric.toComparisonRows()) {
                sb.append(row.label).append('\t')
                    .append(row.description).append('\t')
                    .append(row.startValue).append('\t')
                    .append(row.endValue).append('\n')
            }
            sb.append("\n# result summary (label\tdescription\tvalue)\n")
            for (row in metric.toResultSummary()) {
                sb.append(row.label).append('\t')
                    .append(row.description).append('\t')
                    .append(row.value).append('\n')
            }
            sb.append("\nresponse:\n").append(metric.vlmResponseText)
            sidecar.writeText(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "사이드카 .txt 저장 실패", e)
        }
    }

    @Suppress("unused")
    private fun panelBgColor(): Int = Color.argb(255, 16, 21, 32)
}

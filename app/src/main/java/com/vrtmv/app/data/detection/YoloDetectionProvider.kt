package com.vrtmv.app.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.util.AssetPathResolver
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv11n TFLite 검출기.
 * 입력: 640x640 letterbox float32 NHWC. 출력 텐서 레이아웃은 Ultralytics export 버전마다
 * [1,84,8400] / [1,8400,84] / 픽셀 또는 정규화 좌표가 섞여 나오므로 setup·첫 parse 에서 자동 감지.
 */
class YoloDetectionProvider(
    private val context: Context,
    private val assetPathResolver: AssetPathResolver
) : DetectionProvider {

    companion object {
        private const val TAG = "YoloDet"
        private const val MODEL_FILE = "yolo11n_float16.tflite"
        private const val LABELS_FILE = "coco80_labels.txt"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_CHANNELS = 4 + NUM_CLASSES  // 84
        // YOLOv8/11 공식 예제 기본값 — MediaPipe(0.3) 보다 낮게 잡아 recall 확보 후 NMS 가 중복 필터.
        private const val SCORE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_RESULTS = 20
        // 30fps 입력을 ~10fps 로 다운샘플링 — 검출은 터치 시점에만 실행되므로 레이턴시 영향 없음.
        private const val FRAME_SKIP = 3
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private var frameSkipCounter = 0
    @Volatile override var paused: Boolean = false

    // 출력 텐서 레이아웃 — channelFirst=true 면 [1,84,N], false 면 [1,N,84].
    private var channelFirst: Boolean = true
    private var numAnchors: Int = 8400

    // bbox 좌표계 — Ultralytics export 에 따라 픽셀(0~640) 또는 정규화(0~1). 첫 parseOutput 에서 감지.
    private var coordsChecked: Boolean = false
    private var coordScale: Float = 1f

    init {
        setup()
    }

    private fun setup() {
        try {
            labels = loadLabels()

            val model = loadModelFile()
            if (model == null) {
                Log.w(TAG, "YOLO 모델 파일 없음 — 검출 비활성화. (Intro 다운로드 미완료?)")
                return
            }

            // GPU Delegate 제거 — YOLOv11n 은 Android TFLite GPU delegate 에서 낮은 confidence/크래시
            // 이슈 (Ultralytics #17837, #18245). CPU 4스레드로만 실행.
            val options = Interpreter.Options().apply {
                numThreads = 4
            }

            val itp = Interpreter(model, options)
            interpreter = itp

            val inputShape = itp.getInputTensor(0).shape()
            val outputShape = itp.getOutputTensor(0).shape()
            Log.i(TAG, "YOLO 입력 shape=${inputShape.toList()}, 출력 shape=${outputShape.toList()}")

            when {
                outputShape.size == 3 && outputShape[1] == NUM_CHANNELS -> {
                    channelFirst = true
                    numAnchors = outputShape[2]
                }
                outputShape.size == 3 && outputShape[2] == NUM_CHANNELS -> {
                    channelFirst = false
                    numAnchors = outputShape[1]
                }
                else -> {
                    Log.e(TAG, "지원되지 않는 YOLO 출력 shape: ${outputShape.toList()} — 검출 비활성화")
                    itp.close()
                    interpreter = null
                    return
                }
            }
            Log.i(TAG, "YOLO Interpreter 초기화 완료: $MODEL_FILE labels=${labels.size} backend=CPU(4thr) channelFirst=$channelFirst anchors=$numAnchors")
        } catch (e: Exception) {
            Log.e(TAG, "YOLO 초기화 실패", e)
        }
    }

    private fun loadModelFile(): MappedByteBuffer? {
        val path = assetPathResolver.findAssetPath(MODEL_FILE) ?: return null
        return RandomAccessFile(path, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    private fun loadLabels(): List<String> {
        return context.assets.open(LABELS_FILE).use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    override fun updateFrame(bitmap: Bitmap, timestampMs: Long) {
        if (paused) return
        if (frameSkipCounter++ % FRAME_SKIP != 0) return

        try {
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = copy
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 업데이트 실패", e)
        }
    }

    override fun detectNow(): DetectionResult? {
        val frameCopy = synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val objects = runInference(frameCopy) ?: return null
        return DetectionResult(
            objects = objects,
            bitmap = frameCopy,
            imageWidth = frameCopy.width,
            imageHeight = frameCopy.height
        )
    }

    override fun detectOnBitmap(bitmap: Bitmap): List<DetectedObject> {
        return runInference(bitmap) ?: emptyList()
    }

    private fun runInference(source: Bitmap): List<DetectedObject>? {
        val itp = interpreter ?: return null
        return try {
            val srcW = source.width
            val srcH = source.height
            val letterbox = letterboxToInputSize(source)

            val input = bitmapToFloatBuffer(letterbox)
            letterbox.recycle()

            val (dim1, dim2) = if (channelFirst) NUM_CHANNELS to numAnchors else numAnchors to NUM_CHANNELS
            val output = Array(1) { Array(dim1) { FloatArray(dim2) } }
            itp.run(input, output)

            val scale = min(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
            val padX = (INPUT_SIZE - srcW * scale) / 2f
            val padY = (INPUT_SIZE - srcH * scale) / 2f

            val candidates = parseOutput(output[0], scale, padX, padY, srcW, srcH)
            val finalDetections = nonMaxSuppression(candidates, IOU_THRESHOLD, MAX_RESULTS)

            Log.d(TAG, "${finalDetections.size}개 객체 검출 (YOLO, ${srcW}x${srcH})")
            finalDetections
        } catch (e: Exception) {
            Log.e(TAG, "YOLO 검출 실패", e)
            null
        }
    }

    override fun captureFrame(): Bitmap? {
        return synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun letterboxToInputSize(src: Bitmap): Bitmap {
        val scale = min(INPUT_SIZE.toFloat() / src.width, INPUT_SIZE.toFloat() / src.height)
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        val padX = (INPUT_SIZE - newW) / 2
        val padY = (INPUT_SIZE - newH) / 2

        val result = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), null)
        if (resized != src) resized.recycle()
        return result
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    private fun parseOutput(
        raw: Array<FloatArray>,
        scale: Float,
        padX: Float,
        padY: Float,
        srcW: Int,
        srcH: Int
    ): List<DetectedObject> {
        val get: (Int, Int) -> Float = if (channelFirst) {
            { a, c -> raw[c][a] }
        } else {
            { a, c -> raw[a][c] }
        }

        // 좌표계 자동 감지 — cx 최대값 < 2 이면 정규화 출력으로 간주.
        if (!coordsChecked) {
            var maxCx = 0f
            for (i in 0 until numAnchors) {
                val v = get(i, 0)
                if (v > maxCx) maxCx = v
            }
            coordScale = if (maxCx < 2f) INPUT_SIZE.toFloat() else 1f
            coordsChecked = true
            Log.i(TAG, "YOLO 좌표계 감지: maxCx=$maxCx → scale=$coordScale (${if (coordScale > 1f) "정규화" else "픽셀"})")
        }

        val results = ArrayList<DetectedObject>()
        for (i in 0 until numAnchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val score = get(i, 4 + c)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < SCORE_THRESHOLD || bestClass < 0) continue

            val cx = get(i, 0) * coordScale
            val cy = get(i, 1) * coordScale
            val w = get(i, 2) * coordScale
            val h = get(i, 3) * coordScale

            // letterbox 역변환: 입력 640 좌표 → 패딩 제거 → 스케일 역산 = 원본 비트맵 좌표
            val x1 = ((cx - w / 2f) - padX) / scale
            val y1 = ((cy - h / 2f) - padY) / scale
            val x2 = ((cx + w / 2f) - padX) / scale
            val y2 = ((cy + h / 2f) - padY) / scale

            val left = x1.coerceIn(0f, srcW.toFloat())
            val top = y1.coerceIn(0f, srcH.toFloat())
            val right = x2.coerceIn(0f, srcW.toFloat())
            val bottom = y2.coerceIn(0f, srcH.toFloat())
            if (right - left < 1 || bottom - top < 1) continue

            val label = labels.getOrNull(bestClass) ?: "unknown"
            results.add(
                DetectedObject(
                    boundingBox = RectF(left, top, right, bottom),
                    label = label,
                    confidence = bestScore
                )
            )
        }
        return results
    }

    private fun nonMaxSuppression(
        detections: List<DetectedObject>,
        iouThreshold: Float,
        maxResults: Int
    ): List<DetectedObject> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<DetectedObject>()
        while (sorted.isNotEmpty() && kept.size < maxResults) {
            val best = sorted.removeAt(0)
            kept.add(best)
            val iter = sorted.iterator()
            while (iter.hasNext()) {
                val other = iter.next()
                if (other.label == best.label && iou(best.boundingBox, other.boundingBox) > iouThreshold) {
                    iter.remove()
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight < interLeft || interBottom < interTop) return 0f
        val inter = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.w(TAG, "YOLO 해제 오류", e)
        }
        interpreter = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}

package com.vrtmv.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Debug
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.vrtmv.app.data.camera.ArCoreFrameSource
import com.vrtmv.app.data.camera.ArFrameCallback
import com.vrtmv.app.data.detection.CascadeDetectionPipeline
import com.vrtmv.app.data.detection.DetectionProvider
import com.vrtmv.app.data.detection.DetectionProviderRegistry
import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.data.recording.DescriptionResult
import com.vrtmv.app.data.recording.ExperimentMetric
import com.vrtmv.app.data.recording.FpsMeter
import com.vrtmv.app.data.recording.MetricRecorder
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.util.AnchorProjector
import com.vrtmv.app.util.AssetPathResolver
import com.vrtmv.app.util.CoordinateMapper
import com.vrtmv.app.util.GazeTargetResolver
import com.vrtmv.app.util.RoiCropper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class CameraUiState(
    val detectedObjects: List<DetectedObject> = emptyList(),
    val selectedObject: DetectedObject? = null,
    val tapPoint: Offset? = null,
    val inferenceState: InferenceState = InferenceState.Idle,
    val capturedBitmap: Bitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val captureMode: CaptureMode = CaptureMode.OBJECT_DETECTION,
    val coordinateMapper: CoordinateMapper? = null,
    val modelDisplayName: String = "",
    val inferenceTimeMs: Long = 0L,
    /**
     * ARCore anchor 활성 시 매 프레임 갱신되는 화면 좌표.
     * - non-null: anchor 가 뷰프러스텀 안 → 태그를 이 위치에
     * - null + [arAnchorActive]=true: anchor 는 있으나 화면 밖 → 태그 숨김
     * - null + [arAnchorActive]=false: anchor 없음 → coordinateMapper 기반 정적 위치
     */
    val anchoredTagPosition: Offset? = null,
    val arAnchorActive: Boolean = false,
    /** 직전 추론 기록 파일 경로 (디버그/로그용) */
    val lastRecordPath: String? = null
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    @Suppress("unused") private val assetPathResolver: AssetPathResolver,
    private val detectionProviderRegistry: DetectionProviderRegistry,
    private val cascadePipeline: CascadeDetectionPipeline,
    private val fpsMeter: FpsMeter,
    private val metricRecorder: MetricRecorder,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private var inferenceJob: Job? = null

    @Volatile private var inferenceCooldownUntil: Long = 0L
    private val inferenceCooldownMs = 500L

    private fun isInferenceBusy(): Boolean {
        if (inferenceJob?.isActive == true) return true
        if (System.currentTimeMillis() < inferenceCooldownUntil) return true
        return false
    }

    @Volatile private var arSource: ArCoreFrameSource? = null
    @Volatile private var selectedAnchor: Anchor? = null

    private val arFrameCallback = ArFrameCallback { frame, w, h ->
        val anchor = selectedAnchor ?: run {
            if (_uiState.value.anchoredTagPosition != null) {
                _uiState.value = _uiState.value.copy(anchoredTagPosition = null)
            }
            return@ArFrameCallback
        }
        val pos = AnchorProjector.project(anchor, frame, w, h)
        if (pos != _uiState.value.anchoredTagPosition) {
            _uiState.value = _uiState.value.copy(anchoredTagPosition = pos)
        }
    }

    fun attachArCoreSource(source: ArCoreFrameSource) {
        arSource = source
        source.arFrameCallback = arFrameCallback
        Log.i(TAG, "ARCore 소스 부착 — anchor 추종 활성화")
    }

    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    /** OBJECT/FULL_FRAME 모두에서 프레임 버퍼 공급 — 항상 MediaPipe provider 가 frame source 의 updateFrame 수신. */
    val frameProvider: DetectionProvider
    val captureMode: CaptureMode

    companion object {
        private const val TAG = "CameraVM"
        private const val INFERENCE_TIMEOUT_MS = 60_000L
        private const val PADDED_RATIO = 0.25f
        private const val NO_PADDING_RATIO = 0.0f
        private const val RECORD_BOX_COLOR = 0xFF4FE2FF.toInt() // ArCyan
    }

    /** record PNG 의 캡처 영역에 객체 박스를 시각화 — 추론 입력은 별도 crop. */
    private fun renderBitmapWithBox(src: Bitmap, box: RectF): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = max(4f, min(out.width, out.height) * 0.006f)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = RECORD_BOX_COLOR
            isAntiAlias = true
        }
        canvas.drawRect(box, paint)
        return out
    }

    init {
        val modelId = savedStateHandle.get<String>("modelId") ?: ModelRegistry.DEFAULT_MODEL_ID
        val modelInfo = ModelRegistry.getModel(modelId) ?: ModelRegistry.getDefaultModel()

        captureMode = CaptureMode.fromId(savedStateHandle.get<String>("captureMode"))
        frameProvider = detectionProviderRegistry.get(DetectorKind.MEDIAPIPE)
        // Registry Singleton 이라 이전 세션 paused 플래그 초기화 필수
        frameProvider.paused = false
        // Cascade 가 YOLO 도 사용하므로 미리 인스턴스화 (Intro 에서 초기화 완료 가정 — 안전망)
        detectionProviderRegistry.get(DetectorKind.YOLO).paused = false

        Log.i(TAG, "captureMode=${captureMode.displayName}, model=${modelInfo.displayName}")

        _uiState.value = _uiState.value.copy(
            modelDisplayName = modelInfo.displayName,
            captureMode = captureMode
        )

        viewModelScope.launch {
            try {
                val warmup = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                inferenceEngine.describeScene(warmup)
                warmup.recycle()
                Log.d(TAG, "VLM 워밍업 완료(백그라운드)")
            } catch (e: Exception) {
                Log.w(TAG, "VLM 워밍업 중 오류 (무시): ${e.message}")
            }
        }
    }

    private data class TriggerSnapshot(
        val startEpochMs: Long,
        val startCameraFps: Float,
        val triggerSource: String,
        val tapPointInView: Pair<Float, Float>?,
        val frameWidth: Int,
        val frameHeight: Int,
        val startJavaHeapMb: Float,
        val startNativeHeapMb: Float,
        val maxJavaHeapMb: Float
    )

    private data class EndSnapshot(
        val endEpochMs: Long,
        val endCameraFps: Float,
        val endJavaHeapMb: Float,
        val endNativeHeapMb: Float
    )

    private fun javaHeapUsedMb(): Float {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024f / 1024f
    }

    private fun nativeHeapMb(): Float =
        Debug.getNativeHeapAllocatedSize() / 1024f / 1024f

    private fun maxJavaHeapMb(): Float =
        Runtime.getRuntime().maxMemory() / 1024f / 1024f

    /**
     * 사용자가 트리거(탭/버튼)한 순간의 시각·FPS·메모리 스냅샷.
     * frame 정보가 확보되기 전(예: 검출 실행 전)에 측정해 둔다.
     */
    private data class StartSnapshot(
        val startEpochMs: Long,
        val startCameraFps: Float,
        val startJavaHeapMb: Float,
        val startNativeHeapMb: Float,
        val maxJavaHeapMb: Float
    )

    private fun captureStartSnapshot(): StartSnapshot = StartSnapshot(
        startEpochMs = System.currentTimeMillis(),
        startCameraFps = fpsMeter.currentFps(),
        startJavaHeapMb = javaHeapUsedMb(),
        startNativeHeapMb = nativeHeapMb(),
        maxJavaHeapMb = maxJavaHeapMb()
    )

    private fun toTrigger(
        start: StartSnapshot,
        triggerSource: String,
        tapPointInView: Pair<Float, Float>?,
        frame: Bitmap
    ): TriggerSnapshot = TriggerSnapshot(
        startEpochMs = start.startEpochMs,
        startCameraFps = start.startCameraFps,
        triggerSource = triggerSource,
        tapPointInView = tapPointInView,
        frameWidth = frame.width,
        frameHeight = frame.height,
        startJavaHeapMb = start.startJavaHeapMb,
        startNativeHeapMb = start.startNativeHeapMb,
        maxJavaHeapMb = start.maxJavaHeapMb
    )

    fun onTapDetect(tapPoint: Offset, viewWidth: Float, viewHeight: Float) {
        Log.d(TAG, "▶ onTapDetect 진입 (${tapPoint.x},${tapPoint.y}) mode=${captureMode.id}")
        if (_modelLoading.value || _uiState.value.inferenceState is InferenceState.Loading || isInferenceBusy()) {
            Log.d(TAG, "▶ 추론 중/cooldown — 탭 무시")
            return
        }
        // ★ 진짜 "터치한 순간"을 기록 — 이후 검출 + VLM 시간이 모두 elapsed 에 포함.
        val start = captureStartSnapshot()

        when (captureMode) {
            CaptureMode.OBJECT_DETECTION ->
                handleObjectMode(tapPoint, viewWidth, viewHeight, PADDED_RATIO, start)
            CaptureMode.OBJECT_DETECTION_NO_PADDING ->
                handleObjectMode(tapPoint, viewWidth, viewHeight, NO_PADDING_RATIO, start)
            CaptureMode.FULL_FRAME -> {
                Log.d(TAG, "▶ FULL_FRAME 모드 — 탭 무시 (시작 버튼으로만 트리거)")
            }
        }
    }

    /** 전체 이미지 모드의 명시적 트리거 — 화면 하단 "시작" 버튼이 호출. */
    fun startFullFrameCapture(viewWidth: Float, viewHeight: Float) {
        Log.d(TAG, "▶ startFullFrameCapture 진입 view=${viewWidth}x$viewHeight")
        if (captureMode != CaptureMode.FULL_FRAME) {
            Log.w(TAG, "▶ startFullFrameCapture: 현재 모드 ${captureMode.id} — 무시")
            return
        }
        if (_modelLoading.value || _uiState.value.inferenceState is InferenceState.Loading || isInferenceBusy()) {
            Log.d(TAG, "▶ 추론 중/cooldown — 시작 버튼 무시")
            return
        }
        // ★ 시작 버튼을 누른 순간 — captureFrame() 이후 VLM 시간 전부 포함.
        val start = captureStartSnapshot()
        handleFullFrameMode(viewWidth, viewHeight, start)
    }

    private fun handleObjectMode(
        tapPoint: Offset,
        viewWidth: Float,
        viewHeight: Float,
        paddingRatio: Float,
        start: StartSnapshot
    ) {
        val cascade = cascadePipeline.runForTap(tapPoint, viewWidth, viewHeight)
        val mpResult = cascade.mediaPipeResult
        val frame = mpResult?.bitmap ?: frameProvider.captureFrame() ?: run {
            Log.w(TAG, "▶ 프레임 없음 — 탭 무시")
            return
        }
        val objects = mpResult?.objects ?: emptyList()
        val mapper = CoordinateMapper(frame.width, frame.height, viewWidth, viewHeight)
        val selected = cascade.selected

        replaceAnchor(createAnchorAt(tapPoint.x, tapPoint.y))

        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = _uiState.value.copy(
            detectedObjects = objects,
            selectedObject = selected,
            tapPoint = tapPoint,
            capturedBitmap = frame,
            imageWidth = frame.width,
            imageHeight = frame.height,
            coordinateMapper = mapper,
            inferenceState = if (selected != null) InferenceState.Loading else _uiState.value.inferenceState
        )
        oldBitmap?.recycle()

        if (selected == null) {
            Log.i(
                TAG,
                "▶ OBJECT 객체 미선택 — 토스트 안내, 추론 스킵. mpObjects=${objects.size} frame=${frame.width}x${frame.height}"
            )
            _userMessages.tryEmit("객체탐지에 실패했습니다. 다시 시도해주세요.")
            // 박스 표시는 유지하되 selectedObject 없이 두어 ResultCard 가 Idle 상태를 그대로 유지하도록.
            return
        }

        val trigger = toTrigger(
            start = start,
            triggerSource = "tap",
            tapPointInView = tapPoint.x to tapPoint.y,
            frame = frame
        )

        val mediaPipeLabel = mpResult?.objects?.let { list ->
            GazeTargetResolver.resolve(tapPoint, list, mapper)
        }

        val box = selected.boundingBox
        Log.i(
            TAG,
            "▶ OBJECT crop 분기(pad=$paddingRatio): ${selected.label}(%.2f) box=(%d,%d,%d,%d) frame=%dx%d".format(
                selected.confidence,
                box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(),
                frame.width, frame.height
            )
        )
        val cropRect = RoiCropper.calcCropRect(frame, box, paddingRatio)
        val crop = RoiCropper.crop(frame, box, paddingRatio)
        Log.i(TAG, "▶ crop 생성: ${crop.width}x${crop.height} (record 영역 = crop)")
        runInference(
            logTag = "object[${selected.label}]",
            cleanup = { crop.recycle() },
            buildMetric = { result, end ->
                buildObjectMetric(
                    trigger = trigger,
                    end = end,
                    captureMode = captureMode,
                    cascade = cascade,
                    mpSelected = mediaPipeLabel,
                    finalSelected = selected,
                    result = result
                )
            },
            bitmapForRecord = {
                if (paddingRatio > 0f) {
                    // 패딩 있음: 크롭 안에서 객체 경계를 시각화 — 박스 좌표를 crop 좌표계로 변환.
                    val boxInCrop = RectF(
                        box.left - cropRect.left,
                        box.top - cropRect.top,
                        box.right - cropRect.left,
                        box.bottom - cropRect.top
                    )
                    val rec = renderBitmapWithBox(crop, boxInCrop)
                    Log.d(TAG, "▶ record 사본: ${rec.width}x${rec.height} (CROP + BOX)")
                    rec
                } else {
                    // 패딩 없음: 박스 = 크롭 경계이므로 박스 미시각화.
                    val rec = crop.copy(Bitmap.Config.ARGB_8888, false)
                    Log.d(TAG, "▶ record 사본: ${rec.width}x${rec.height} (CROP only)")
                    rec
                }
            }
        ) { describe(crop, selected.label, selected.confidence) }
    }

    private fun handleFullFrameMode(viewWidth: Float, viewHeight: Float, start: StartSnapshot) {
        val frame = frameProvider.captureFrame() ?: run {
            Log.w(TAG, "▶ 프레임 없음 — 시작 버튼 무시")
            return
        }
        val mapper = CoordinateMapper(frame.width, frame.height, viewWidth, viewHeight)

        // 화면 중앙을 결과 태그/앵커의 기준점으로 사용 (전체 이미지 모드는 탭 좌표 없음)
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        replaceAnchor(createAnchorAt(centerX, centerY))

        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = _uiState.value.copy(
            detectedObjects = emptyList(),
            selectedObject = null,
            tapPoint = Offset(centerX, centerY),
            capturedBitmap = frame,
            imageWidth = frame.width,
            imageHeight = frame.height,
            coordinateMapper = mapper,
            inferenceState = InferenceState.Loading
        )
        oldBitmap?.recycle()

        val trigger = toTrigger(
            start = start,
            triggerSource = "button",
            tapPointInView = null,
            frame = frame
        )

        runInference(
            logTag = "scene-full",
            buildMetric = { result, end ->
                buildFullFrameMetric(trigger, end, result)
            },
            bitmapForRecord = { frame.copy(Bitmap.Config.ARGB_8888, false) }
        ) { describeScene(frame) }
    }

    private fun createAnchorAt(screenX: Float, screenY: Float): Anchor? {
        val source = arSource ?: return null
        val frame = source.latestFrame ?: return null
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.d(TAG, "▶ tracking 미초기화 — anchor 생성 보류")
            return null
        }
        return try {
            // 1순위: 일반 hitTest (평면/feature point)
            val hits = frame.hitTest(screenX, screenY)
            val hit = hits.firstOrNull()
            if (hit != null) {
                Log.d(TAG, "▶ anchor: hitTest plane/feature 성공")
                return hit.createAnchor()
            }
            // 2순위: Instant Placement — 평면이 없어도 SLAM 추종 가능한 instant anchor
            val instantHits = frame.hitTestInstantPlacement(screenX, screenY, 1.5f)
            val instantHit = instantHits.firstOrNull()
            if (instantHit != null) {
                Log.d(TAG, "▶ anchor: instantPlacement 성공")
                return instantHit.createAnchor()
            }
            // 3순위: 카메라 전방 free anchor (가장 약함)
            Log.d(TAG, "▶ anchor: forwardAnchor 폴백")
            forwardAnchor(frame, source, distanceMeters = 1.5f)
        } catch (e: Exception) {
            Log.w(TAG, "anchor 생성 예외", e)
            null
        }
    }

    private fun forwardAnchor(frame: Frame, source: ArCoreFrameSource, distanceMeters: Float): Anchor? {
        val cameraPose = frame.camera.pose
        val forward = floatArrayOf(0f, 0f, -distanceMeters)
        val rotated = FloatArray(3)
        cameraPose.rotateVector(forward, 0, rotated, 0)
        val pose = Pose.makeTranslation(
            cameraPose.tx() + rotated[0],
            cameraPose.ty() + rotated[1],
            cameraPose.tz() + rotated[2]
        )
        return source.createAnchor(pose)
    }

    private fun replaceAnchor(newAnchor: Anchor?) {
        val old = selectedAnchor
        selectedAnchor = newAnchor
        try { old?.detach() } catch (e: Exception) { Log.w(TAG, "이전 anchor detach 실패", e) }
        val active = newAnchor != null
        if (_uiState.value.arAnchorActive != active ||
            (newAnchor == null && _uiState.value.anchoredTagPosition != null)
        ) {
            _uiState.value = _uiState.value.copy(
                arAnchorActive = active,
                anchoredTagPosition = if (active) _uiState.value.anchoredTagPosition else null
            )
        }
    }

    /**
     * 추론 + 메트릭 저장 통합 헬퍼. block 이 DescriptionResult 를 반환하면 buildMetric 으로
     * 메트릭 조립 → MetricRecorder 에 비동기 저장. cleanup 은 try/catch/finally 모두에서 실행.
     */
    private fun runInference(
        logTag: String,
        cleanup: () -> Unit = {},
        buildMetric: ((DescriptionResult, EndSnapshot) -> ExperimentMetric)? = null,
        bitmapForRecord: (() -> Bitmap)? = null,
        block: suspend InferenceEngine.() -> DescriptionResult
    ) {
        if (isInferenceBusy()) {
            Log.d(TAG, "▶ 추론 busy/cooldown — $logTag 스킵")
            cleanup()
            return
        }
        frameProvider.paused = true
        inferenceJob = viewModelScope.launch {
            try {
                if (!inferenceEngine.ensureLoaded()) {
                    cleanup()
                    _uiState.value = _uiState.value.copy(
                        inferenceState = InferenceState.Error("모델이 로드되지 않았습니다")
                    )
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val result = withTimeout(INFERENCE_TIMEOUT_MS) { inferenceEngine.block() }
                val endEpochMs = System.currentTimeMillis()
                val elapsed = endEpochMs - startTime
                val end = EndSnapshot(
                    endEpochMs = endEpochMs,
                    endCameraFps = fpsMeter.currentFps(),
                    endJavaHeapMb = javaHeapUsedMb(),
                    endNativeHeapMb = nativeHeapMb()
                )
                Log.d(TAG, "▶ $logTag 추론 완료: ${elapsed}ms text='${result.text.take(40)}…'")

                val recordBitmap = try { bitmapForRecord?.invoke() } catch (e: Exception) { null }
                cleanup()

                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(result.text),
                    inferenceTimeMs = elapsed
                )

                if (buildMetric != null && recordBitmap != null) {
                    val metric = buildMetric(result, end)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val path = metricRecorder.saveRecord(recordBitmap, metric)
                            if (path != null) {
                                _uiState.value = _uiState.value.copy(lastRecordPath = path)
                            }
                        } finally {
                            recordBitmap.recycle()
                        }
                    }
                } else {
                    recordBitmap?.recycle()
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                cleanup()
                Log.d(TAG, "▶ $logTag 추론 취소됨")
            } catch (e: Exception) {
                cleanup()
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            } finally {
                frameProvider.paused = false
                inferenceCooldownUntil = System.currentTimeMillis() + inferenceCooldownMs
            }
        }
    }

    private fun buildObjectMetric(
        trigger: TriggerSnapshot,
        end: EndSnapshot,
        captureMode: CaptureMode,
        cascade: CascadeDetectionPipeline.Result,
        mpSelected: DetectedObject?,
        finalSelected: DetectedObject?,
        result: DescriptionResult
    ): ExperimentMetric {
        return ExperimentMetric(
            epochMs = trigger.startEpochMs,
            isoTimestamp = ExperimentMetric.isoNow(trigger.startEpochMs),
            captureMode = captureMode,
            startCameraFps = trigger.startCameraFps,
            triggerSource = trigger.triggerSource,
            tapPointInView = trigger.tapPointInView,
            frameWidth = trigger.frameWidth,
            frameHeight = trigger.frameHeight,
            startJavaHeapMb = trigger.startJavaHeapMb,
            startNativeHeapMb = trigger.startNativeHeapMb,
            maxJavaHeapMb = trigger.maxJavaHeapMb,
            endEpochMs = end.endEpochMs,
            endCameraFps = end.endCameraFps,
            endJavaHeapMb = end.endJavaHeapMb,
            endNativeHeapMb = end.endNativeHeapMb,
            detectionTotalMs = cascade.mediaPipeMs + cascade.yoloMs,
            mediaPipeMs = cascade.mediaPipeMs,
            yoloMs = cascade.yoloMs,
            mediaPipeObjectCount = cascade.mediaPipeResult?.objects?.size ?: 0,
            selectedLabelMediaPipe = mpSelected?.label,
            selectedConfidenceMediaPipe = mpSelected?.confidence,
            selectedLabelFinal = finalSelected?.label ?: mpSelected?.label,
            selectedConfidenceFinal = finalSelected?.confidence ?: mpSelected?.confidence,
            cascadeUsedYolo = cascade.cascadeUsedYolo,
            vlmInputWidth = result.inputWidth,
            vlmInputHeight = result.inputHeight,
            vlmPreprocessMs = result.timings.preprocessMs,
            vlmCreateConvMs = result.timings.createConvMs,
            vlmSendMessageMs = result.timings.sendMessageMs,
            vlmTotalMs = result.timings.totalMs,
            vlmResponseCharCount = result.text.length,
            vlmResponseText = result.text
        )
    }

    private fun buildFullFrameMetric(
        trigger: TriggerSnapshot,
        end: EndSnapshot,
        result: DescriptionResult
    ): ExperimentMetric {
        return ExperimentMetric(
            epochMs = trigger.startEpochMs,
            isoTimestamp = ExperimentMetric.isoNow(trigger.startEpochMs),
            captureMode = CaptureMode.FULL_FRAME,
            startCameraFps = trigger.startCameraFps,
            triggerSource = trigger.triggerSource,
            tapPointInView = trigger.tapPointInView,
            frameWidth = trigger.frameWidth,
            frameHeight = trigger.frameHeight,
            startJavaHeapMb = trigger.startJavaHeapMb,
            startNativeHeapMb = trigger.startNativeHeapMb,
            maxJavaHeapMb = trigger.maxJavaHeapMb,
            endEpochMs = end.endEpochMs,
            endCameraFps = end.endCameraFps,
            endJavaHeapMb = end.endJavaHeapMb,
            endNativeHeapMb = end.endNativeHeapMb,
            detectionTotalMs = 0L,
            mediaPipeMs = 0L,
            yoloMs = 0L,
            mediaPipeObjectCount = 0,
            selectedLabelMediaPipe = null,
            selectedConfidenceMediaPipe = null,
            selectedLabelFinal = null,
            selectedConfidenceFinal = null,
            cascadeUsedYolo = false,
            vlmInputWidth = result.inputWidth,
            vlmInputHeight = result.inputHeight,
            vlmPreprocessMs = result.timings.preprocessMs,
            vlmCreateConvMs = result.timings.createConvMs,
            vlmSendMessageMs = result.timings.sendMessageMs,
            vlmTotalMs = result.timings.totalMs,
            vlmResponseCharCount = result.text.length,
            vlmResponseText = result.text
        )
    }

    fun clearSelection() {
        inferenceJob?.cancel()
        replaceAnchor(null)
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = CameraUiState(
            captureMode = captureMode,
            modelDisplayName = _uiState.value.modelDisplayName
        )
        oldBitmap?.recycle()
    }

    fun reportFrame(timestampMs: Long) {
        fpsMeter.onFrame(timestampMs)
    }

    override fun onCleared() {
        super.onCleared()
        arSource?.arFrameCallback = null
        arSource = null
        replaceAnchor(null)
        _uiState.value.capturedBitmap?.recycle()
        frameProvider.paused = true
        fpsMeter.reset()
    }
}

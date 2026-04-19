package com.vrtmv.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.detection.DetectionProvider
import com.vrtmv.app.data.detection.DetectionProviderRegistry
import com.vrtmv.app.data.detection.HandGestureDetector
import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.data.inference.VlmMode
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.domain.model.ModelRegistry
import com.vrtmv.app.util.AssetPathResolver
import com.vrtmv.app.util.CoordinateMapper
import com.vrtmv.app.util.GazeTargetResolver
import com.vrtmv.app.util.RoiCropper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 카메라 화면의 전체 UI 상태.
 * 검출 결과, 선택된 객체, VLM 추론 상태 등을 포함.
 */
data class CameraUiState(
    val detectedObjects: List<DetectedObject> = emptyList(),
    val selectedObject: DetectedObject? = null,
    val tapPoint: Offset? = null,
    val inferenceState: InferenceState = InferenceState.Idle,
    val capturedBitmap: Bitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val vlmMode: VlmMode = VlmMode.OFF,
    val coordinateMapper: CoordinateMapper? = null,
    val modelDisplayName: String = "",
    val detectorDisplayName: String = "",
    val inferenceTimeMs: Long = 0L,
    /** 포인팅 홀드 중 검지 끝 위치 (정규화 0~1) — UI 피드백용 */
    val pointingPosition: Offset? = null,
    /** 포인팅 홀드 진행률 0~1, 1.0 도달 시 onTapDetect 자동 발화 */
    val pointingProgress: Float = 0f
)

/**
 * 카메라 화면의 중앙 상태 관리 ViewModel.
 * 터치→검출→객체선택→VLM추론의 전체 파이프라인을 관리한다.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val assetPathResolver: AssetPathResolver,
    private val detectionProviderRegistry: DetectionProviderRegistry,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var inferenceJob: Job? = null

    /**
     * 모델 로딩 완료 여부.
     * Intro 단계에서 이미 loadModel + 워밍업이 수행되므로 Camera 진입 시점에는 항상 false.
     * 과거 호환을 위해 필드는 유지하지만 항상 준비된 상태.
     */
    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    /** 선택된 검출기 구현체 — Registry에서 캐시된 Singleton 인스턴스를 획득 */
    val detectionProvider: DetectionProvider

    companion object {
        private const val TAG = "CameraVM"
    }

    init {
        // Navigation argument에서 modelId / detectorId 읽기
        val modelId = savedStateHandle.get<String>("modelId") ?: ModelRegistry.DEFAULT_MODEL_ID
        val modelInfo = ModelRegistry.getModel(modelId) ?: ModelRegistry.getDefaultModel()

        val detectorKind = DetectorKind.fromId(savedStateHandle.get<String>("detectorId"))
        detectionProvider = detectionProviderRegistry.get(detectorKind)
        // Registry Singleton은 재사용되므로 이전 세션 paused 플래그를 초기화
        detectionProvider.paused = false
        Log.i(TAG, "검출기 선택: ${detectorKind.displayName}")

        _uiState.value = _uiState.value.copy(
            modelDisplayName = modelInfo.displayName,
            detectorDisplayName = detectorKind.displayName
        )
    }

    /** VLM 모드 토글 (OFF ↔ ON) */
    fun toggleVlmMode() {
        val next = if (_uiState.value.vlmMode == VlmMode.OFF) VlmMode.ON else VlmMode.OFF
        _uiState.value = _uiState.value.copy(vlmMode = next)
    }

    /**
     * 사용자 터치 시 호출 — 객체 검출 후 탭 좌표의 객체를 크롭하여 VLM에 전달.
     * detectNow() → GazeTargetResolver.resolve() → 매칭 시 RoiCropper.crop() + describe(),
     * 미매칭 시 describeScene(frame) fallback. OFF 모드에서도 검출 실행하여 오버레이 표시.
     */
    fun onTapDetect(
        tapPoint: Offset,
        viewWidth: Float,
        viewHeight: Float
    ) {
        if (_modelLoading.value) {
            Log.d(TAG, "▶ 모델 로딩 중 — 탭 무시")
            return
        }
        if (_uiState.value.inferenceState is InferenceState.Loading) {
            Log.d(TAG, "▶ 추론 중 — 탭 무시")
            return
        }

        // 검출 실행 — 실패 시 captureFrame fallback
        val detectionResult = detectionProvider.detectNow()
        val frame = detectionResult?.bitmap ?: detectionProvider.captureFrame() ?: run {
            Log.w(TAG, "▶ 프레임 없음 — 탭 무시")
            return
        }
        val objects = detectionResult?.objects ?: emptyList()

        val mapper = CoordinateMapper(
            imageWidth = frame.width,
            imageHeight = frame.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        // 탭 좌표에 해당하는 객체 선택
        val resolved = GazeTargetResolver.resolve(tapPoint, objects, mapper)

        val vlmOn = _uiState.value.vlmMode == VlmMode.ON
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = _uiState.value.copy(
            detectedObjects = objects,
            selectedObject = resolved,
            tapPoint = tapPoint,
            capturedBitmap = frame,
            imageWidth = frame.width,
            imageHeight = frame.height,
            coordinateMapper = mapper,
            inferenceState = if (vlmOn) InferenceState.Loading else InferenceState.Idle
        )
        oldBitmap?.recycle()

        if (!vlmOn) return

        if (resolved != null) {
            val crop = RoiCropper.crop(frame, resolved.boundingBox)
            runObjectInference(crop, resolved.label, resolved.confidence)
        } else {
            runSceneInference(frame)
        }
    }

    /** 전체 장면 추론. 캡처된 프레임을 그대로 VLM에 전달. */
    private fun runSceneInference(bitmap: Bitmap) {
        inferenceJob?.cancel()
        // GPU/CPU를 VLM decode에 온전히 할당
        detectionProvider.paused = true

        inferenceJob = viewModelScope.launch {
            try {
                // 유휴 언로드 후 재진입이면 hot reload. cacheDir 덕분에 cold start보다 짧다.
                if (!inferenceEngine.ensureLoaded()) {
                    _uiState.value = _uiState.value.copy(
                        inferenceState = InferenceState.Error("모델이 로드되지 않았습니다")
                    )
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val description = withTimeout(60_000) {
                    inferenceEngine.describeScene(bitmap)
                }
                val elapsed = System.currentTimeMillis() - startTime

                Log.d(TAG, "▶ 장면 추론 완료: ${elapsed}ms")

                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(description),
                    inferenceTimeMs = elapsed
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                Log.d(TAG, "▶ 장면 추론 취소됨")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            } finally {
                detectionProvider.paused = false
            }
        }
    }

    /** 객체 크롭 추론. 검출 라벨을 힌트로 포함하여 describe() 호출. */
    private fun runObjectInference(crop: Bitmap, label: String, confidence: Float) {
        inferenceJob?.cancel()
        detectionProvider.paused = true
        inferenceJob = viewModelScope.launch {
            try {
                if (!inferenceEngine.ensureLoaded()) {
                    crop.recycle()
                    _uiState.value = _uiState.value.copy(
                        inferenceState = InferenceState.Error("모델이 로드되지 않았습니다")
                    )
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val description = withTimeout(60_000) {
                    inferenceEngine.describe(crop, label, confidence)
                }
                val elapsed = System.currentTimeMillis() - startTime
                crop.recycle()
                Log.d(TAG, "▶ 객체 추론 완료: ${elapsed}ms label=$label")
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(description),
                    inferenceTimeMs = elapsed
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                crop.recycle()
                Log.d(TAG, "▶ 객체 추론 취소됨")
            } catch (e: Exception) {
                crop.recycle()
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            } finally {
                detectionProvider.paused = false
            }
        }
    }

    /**
     * CameraScreen에서 Composable 내 `remember` 로 HandGestureDetector 인스턴스를 생성할 때
     * 사용하는 팩토리. AssetPathResolver 주입을 ViewModel로 일원화.
     */
    fun createGestureDetector(
        onUpdate: (Float, Float, Float) -> Unit,
        onConfirmed: (Float, Float) -> Unit,
        onLost: () -> Unit
    ): HandGestureDetector = HandGestureDetector(
        context = appContext,
        assetPathResolver = assetPathResolver,
        onPointingUpdate = onUpdate,
        onPointingConfirmed = onConfirmed,
        onPointingLost = onLost
    )

    /** 제스처 포인팅 업데이트 — HandGestureDetector 콜백 */
    fun onPointingUpdate(normX: Float, normY: Float, progress: Float) {
        if (_uiState.value.inferenceState is InferenceState.Loading) return
        _uiState.value = _uiState.value.copy(
            pointingPosition = Offset(normX, normY),
            pointingProgress = progress
        )
    }

    /**
     * 제스처 홀드 완료 — 객체 검출 후 포인팅 좌표의 객체를 크롭하여 VLM에 전달.
     * "person" 라벨은 필터링 (본인 손 오탐 차단). 미매칭 시 35% 크롭 fallback.
     */
    fun onPointingConfirmed(normX: Float, normY: Float, viewWidth: Float, viewHeight: Float) {
        if (_modelLoading.value) {
            Log.d(TAG, "▶ 모델 로딩 중 — 제스처 무시")
            _uiState.value = _uiState.value.copy(pointingPosition = null, pointingProgress = 0f)
            return
        }
        if (_uiState.value.inferenceState is InferenceState.Loading) return

        // 검출 실행 — 실패 시 captureFrame fallback
        val detectionResult = detectionProvider.detectNow()
        val frame = detectionResult?.bitmap ?: detectionProvider.captureFrame() ?: run {
            Log.w(TAG, "▶ 제스처 추론: 프레임 없음")
            _uiState.value = _uiState.value.copy(pointingPosition = null, pointingProgress = 0f)
            return
        }

        // "person" 필터링: 제스처 시 본인 손이 "person"으로 오탐되는 문제 차단
        val filteredObjects = (detectionResult?.objects ?: emptyList())
            .filter { it.label != "person" }

        val screenPoint = Offset(normX * viewWidth, normY * viewHeight)
        val mapper = CoordinateMapper(
            imageWidth = frame.width,
            imageHeight = frame.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        // 포인팅 좌표에 해당하는 객체 선택
        val resolved = GazeTargetResolver.resolve(screenPoint, filteredObjects, mapper)

        val vlmOn = _uiState.value.vlmMode == VlmMode.ON
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = _uiState.value.copy(
            detectedObjects = filteredObjects,
            selectedObject = resolved,
            tapPoint = screenPoint,
            capturedBitmap = frame,
            imageWidth = frame.width,
            imageHeight = frame.height,
            coordinateMapper = mapper,
            pointingPosition = null,
            pointingProgress = 0f,
            inferenceState = if (vlmOn) InferenceState.Loading else InferenceState.Idle
        )
        oldBitmap?.recycle()

        if (!vlmOn) return

        if (resolved != null) {
            val crop = RoiCropper.crop(frame, resolved.boundingBox)
            runObjectInference(crop, resolved.label, resolved.confidence)
        } else {
            // 미매칭 fallback: 포인팅 중심 35% 크롭
            val cropSize = (minOf(frame.width, frame.height) * 0.35f).toInt().coerceAtLeast(64)
            val cx = (normX * frame.width).toInt()
            val cy = (normY * frame.height).toInt()
            val left = (cx - cropSize / 2).coerceIn(0, frame.width - cropSize)
            val top = (cy - cropSize / 2).coerceIn(0, frame.height - cropSize)
            val w = cropSize.coerceAtMost(frame.width - left)
            val h = cropSize.coerceAtMost(frame.height - top)
            val crop = Bitmap.createBitmap(frame, left, top, w, h)
            runCroppedInference(crop)
        }
    }

    /** 제스처 경로용: 이미 크롭된 비트맵을 describeScene에 전달. */
    private fun runCroppedInference(crop: Bitmap) {
        inferenceJob?.cancel()
        detectionProvider.paused = true
        inferenceJob = viewModelScope.launch {
            try {
                if (!inferenceEngine.ensureLoaded()) {
                    crop.recycle()
                    _uiState.value = _uiState.value.copy(
                        inferenceState = InferenceState.Error("모델이 로드되지 않았습니다")
                    )
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val description = withTimeout(60_000) {
                    inferenceEngine.describeScene(crop)
                }
                val elapsed = System.currentTimeMillis() - startTime
                crop.recycle()
                Log.d(TAG, "▶ 제스처 추론 완료: ${elapsed}ms")
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(description),
                    inferenceTimeMs = elapsed
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                crop.recycle()
                Log.d(TAG, "▶ 제스처 추론 취소됨")
            } catch (e: Exception) {
                crop.recycle()
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            } finally {
                detectionProvider.paused = false
            }
        }
    }

    /** 제스처 소실 — 진행 링 초기화 */
    fun onPointingLost() {
        if (_uiState.value.pointingPosition != null) {
            _uiState.value = _uiState.value.copy(pointingPosition = null, pointingProgress = 0f)
        }
    }

    /** 선택 해제 / 추론 중지. 롱프레스 시 호출. */
    fun clearSelection() {
        inferenceJob?.cancel()
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = CameraUiState(
            vlmMode = _uiState.value.vlmMode,
            modelDisplayName = _uiState.value.modelDisplayName,
            detectorDisplayName = _uiState.value.detectorDisplayName
        )
        oldBitmap?.recycle()
    }

    /**
     * ViewModel 소멸 시 캡처 비트맵만 해제.
     * InferenceEngine과 DetectionProvider는 Singleton이므로 Camera 재진입 시 재사용된다.
     * 다음 진입 시 paused 플래그만 init에서 초기화.
     */
    override fun onCleared() {
        super.onCleared()
        _uiState.value.capturedBitmap?.recycle()
        // 검출기는 Singleton이므로 Analyzer가 종료된 후 프레임 처리를 중단하도록 paused 플래그만 세팅
        detectionProvider.paused = true
    }
}

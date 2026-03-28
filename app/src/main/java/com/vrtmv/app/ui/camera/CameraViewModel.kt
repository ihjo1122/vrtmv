package com.vrtmv.app.ui.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.detection.ObjectDetectionManager
import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.data.inference.VlmMode
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.util.CoordinateMapper
import com.vrtmv.app.util.GazeTargetResolver
import com.vrtmv.app.util.RoiCropper
import androidx.lifecycle.SavedStateHandle
import com.vrtmv.app.domain.model.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val inferenceTimeMs: Long = 0L
)

/**
 * 카메라 화면의 중앙 상태 관리 ViewModel.
 * 터치→검출→객체선택→VLM추론의 전체 파이프라인을 관리한다.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var inferenceJob: Job? = null

    /** 모델 로딩 완료 여부 */
    private val _modelLoading = MutableStateFlow(true)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    companion object {
        private const val TAG = "CameraVM"
    }

    init {
        // Navigation argument에서 modelId 읽기
        val modelId = savedStateHandle.get<String>("modelId") ?: ModelRegistry.DEFAULT_MODEL_ID
        val modelInfo = ModelRegistry.getModel(modelId) ?: ModelRegistry.getDefaultModel()

        _uiState.value = _uiState.value.copy(modelDisplayName = modelInfo.displayName)

        // 모델 로드 (5-15초 소요 가능)
        viewModelScope.launch {
            Log.d(TAG, "모델 로드 시작: ${modelInfo.displayName}")
            val success = inferenceEngine.loadModel(modelInfo)
            if (!success) {
                Log.w(TAG, "모델 로드 실패: ${modelInfo.displayName}")
            }
            _modelLoading.value = false
            Log.d(TAG, "모델 로드 완료: ${modelInfo.displayName}, success=$success")
        }
    }

    /** VLM 모드 토글 (OFF ↔ ON) */
    fun toggleVlmMode() {
        val next = if (_uiState.value.vlmMode == VlmMode.OFF) VlmMode.ON else VlmMode.OFF
        _uiState.value = _uiState.value.copy(vlmMode = next)
    }

    /**
     * 사용자 터치 시 호출.
     * 1) 현재 프레임에서 객체 검출
     * 2) 터치 좌표에 해당하는 객체 선택
     * 3) VLM ON이면 온디바이스 추론 실행
     */
    fun onTapDetect(
        tapPoint: Offset,
        detectionManager: ObjectDetectionManager,
        viewWidth: Float,
        viewHeight: Float
    ) {
        // 추론 중이면 터치 무시
        if (_uiState.value.inferenceState is InferenceState.Loading) {
            Log.d(TAG, "▶ 추론 중 — 터치 무시")
            return
        }

        inferenceJob?.cancel()

        val result = detectionManager.detectNow() ?: run {
            Log.w(TAG, "▶ detectNow() 반환 null — 프레임 없음")
            return
        }

        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "▶ TAP: (${tapPoint.x}, ${tapPoint.y})")
        Log.d(TAG, "▶ VIEW: ${viewWidth} x ${viewHeight}")
        Log.d(TAG, "▶ IMAGE: ${result.imageWidth} x ${result.imageHeight}")
        Log.d(TAG, "▶ 검출 객체: ${result.objects.size}개")

        val mapper = CoordinateMapper(
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        // 좌표 매핑 디버그 로그
        Log.d(TAG, "▶ MAPPER: scale=${mapper.debugScale()}, offset=(${mapper.debugOffsetX()}, ${mapper.debugOffsetY()})")

        result.objects.forEachIndexed { i, obj ->
            val viewRect = mapper.mapToView(obj.boundingBox)
            Log.d(TAG, "  [$i] ${obj.label}(${(obj.confidence * 100).toInt()}%)" +
                " img=(${obj.boundingBox.left.toInt()},${obj.boundingBox.top.toInt()},${obj.boundingBox.right.toInt()},${obj.boundingBox.bottom.toInt()})" +
                " → view=(${viewRect.left.toInt()},${viewRect.top.toInt()},${viewRect.right.toInt()},${viewRect.bottom.toInt()})" +
                " contains=${viewRect.contains(tapPoint)}")
        }

        val selected = GazeTargetResolver.resolve(
            gazePoint = tapPoint,
            detectedObjects = result.objects,
            coordinateMapper = mapper
        )

        Log.d(TAG, "▶ SELECTED: ${selected?.label ?: "없음"}")
        Log.d(TAG, "════════════════════════════════════════")

        val oldBitmap = _uiState.value.capturedBitmap

        _uiState.value = _uiState.value.copy(
            detectedObjects = result.objects,
            selectedObject = selected,
            tapPoint = tapPoint,
            capturedBitmap = result.bitmap,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            coordinateMapper = mapper,
            inferenceState = if (selected != null) {
                InferenceState.Success(
                    "${selected.label} (${(selected.confidence * 100).toInt()}%)"
                )
            } else {
                InferenceState.Idle
            }
        )

        // 새 상태 설정 후 이전 비트맵 해제
        oldBitmap?.recycle()

        // VLM ON이면 추론 실행
        if (_uiState.value.vlmMode == VlmMode.ON) {
            if (selected != null) {
                runInference(result.bitmap, selected)
            } else {
                runSceneInference(result.bitmap)
            }
        }
    }

    /**
     * 온디바이스 VLM 추론을 실행한다.
     * ROI 크롭 → 프롬프트 생성 → LLM 추론 → 결과 UI 반영.
     * 15초 타임아웃, IO 디스패처에서 비동기 실행.
     */
    private fun runInference(bitmap: Bitmap, obj: DetectedObject) {
        _uiState.value = _uiState.value.copy(inferenceState = InferenceState.Loading)

        inferenceJob = viewModelScope.launch {
            try {
                val cropped = RoiCropper.crop(bitmap, obj.boundingBox)

                val startTime = System.currentTimeMillis()
                val description = withTimeout(60_000) {
                    inferenceEngine.describe(cropped, obj.label, obj.confidence)
                }
                val elapsed = System.currentTimeMillis() - startTime

                cropped.recycle()
                Log.d(TAG, "▶ 추론 완료: ${elapsed}ms")

                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(description),
                    inferenceTimeMs = elapsed
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // 사용자 중지 — 상태 업데이트 안 함
                Log.d(TAG, "▶ 추론 취소됨")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            }
        }
    }

    /** 전체 장면 추론. 객체 미검출 시 호출. */
    private fun runSceneInference(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(inferenceState = InferenceState.Loading)

        inferenceJob = viewModelScope.launch {
            try {
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
            }
        }
    }

    /** 선택 해제 / 추론 중지. 롱프레스 시 호출. */
    fun clearSelection() {
        inferenceJob?.cancel()
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = CameraUiState(
            vlmMode = _uiState.value.vlmMode,
            modelDisplayName = _uiState.value.modelDisplayName
        )
        oldBitmap?.recycle()
    }

    /** ViewModel 소멸 시 비트맵 + 엔진 메모리 해제 */
    override fun onCleared() {
        super.onCleared()
        _uiState.value.capturedBitmap?.recycle()
        // GPU 메모리 해제 (카메라 화면 종료 시)
        inferenceEngine.release()
    }
}

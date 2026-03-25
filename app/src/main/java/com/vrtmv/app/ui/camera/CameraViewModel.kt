package com.vrtmv.app.ui.camera

import android.graphics.Bitmap
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
    val vlmMode: VlmMode = VlmMode.OFF
)

/**
 * 카메라 화면의 중앙 상태 관리 ViewModel.
 * 터치→검출→객체선택→VLM추론의 전체 파이프라인을 관리한다.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val inferenceEngine: InferenceEngine  // Hilt에 의해 GeminiNanoEngine 주입
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var inferenceJob: Job? = null  // 현재 실행 중인 추론 코루틴

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
        inferenceJob?.cancel()

        val result = detectionManager.detectNow() ?: return

        val mapper = CoordinateMapper(
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        val selected = GazeTargetResolver.resolve(
            gazePoint = tapPoint,
            detectedObjects = result.objects,
            coordinateMapper = mapper
        )

        _uiState.value.capturedBitmap?.recycle()

        _uiState.value = CameraUiState(
            detectedObjects = result.objects,
            selectedObject = selected,
            tapPoint = tapPoint,
            capturedBitmap = result.bitmap,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            vlmMode = _uiState.value.vlmMode,
            inferenceState = if (selected != null) {
                InferenceState.Success(
                    "${selected.label} (${(selected.confidence * 100).toInt()}%)"
                )
            } else {
                InferenceState.Idle
            }
        )

        // VLM ON이면 온디바이스 추론 실행
        if (selected != null && _uiState.value.vlmMode == VlmMode.ON) {
            runInference(result.bitmap, selected)
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

                val description = withTimeout(15_000) {
                    inferenceEngine.describe(cropped, obj.label, obj.confidence)
                }

                cropped.recycle()

                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Success(description)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    inferenceState = InferenceState.Error(e.message ?: "추론 실패")
                )
            }
        }
    }

    /** 선택 해제. 롱프레스 시 호출 */
    fun clearSelection() {
        inferenceJob?.cancel()
        _uiState.value.capturedBitmap?.recycle()
        _uiState.value = CameraUiState(vlmMode = _uiState.value.vlmMode)
    }

    /** ViewModel 소멸 시 비트맵 메모리 해제 */
    override fun onCleared() {
        super.onCleared()
        _uiState.value.capturedBitmap?.recycle()
    }
}

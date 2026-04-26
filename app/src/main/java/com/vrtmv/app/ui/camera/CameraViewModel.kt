package com.vrtmv.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import com.vrtmv.app.data.detection.DetectionProvider
import com.vrtmv.app.data.detection.DetectionProviderRegistry
import com.vrtmv.app.data.detection.HandGestureDetector
import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.data.inference.VlmMode
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
    val vlmMode: VlmMode = VlmMode.ON,
    val coordinateMapper: CoordinateMapper? = null,
    val modelDisplayName: String = "",
    val detectorDisplayName: String = "",
    val inferenceTimeMs: Long = 0L,
    // `vlmMode` 기본 ON — 과제 시연 목적이 VLM 설명 생성이므로 첫 진입부터 설명이 나오도록.
    /** 포인팅 홀드 중 검지 끝 위치 (정규화 0~1) — UI 피드백용 */
    val pointingPosition: Offset? = null,
    /** 포인팅 홀드 진행률 0~1, 1.0 도달 시 onTapDetect 자동 발화 */
    val pointingProgress: Float = 0f,
    /**
     * ARCore 앵커가 활성일 때 매 프레임 갱신되는 화면 좌표.
     * - non-null: 앵커가 현재 카메라 뷰프러스텀 안 → 태그를 이 위치에 표시
     * - null + [arAnchorActive]=true: 앵커는 존재하지만 화면 밖 → 태그 숨김 (정적 박스로 폴백하지 않음)
     * - null + [arAnchorActive]=false: 앵커 없음(CameraX 모드 또는 미탭) → coordinateMapper 기반 정적 위치 사용
     */
    val anchoredTagPosition: Offset? = null,
    /** ARCore 백엔드에서 anchor 생성 상태. true 면 태그 위치는 전적으로 anchor 에 의존. */
    val arAnchorActive: Boolean = false
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
     * 외부에서 주입되는 ARCore 프레임 소스 — CameraScreen 이 set.
     * useArCore=true 이고 가용한 경우에만 non-null.
     */
    @Volatile private var arSource: ArCoreFrameSource? = null

    /** 현재 추적 중인 월드 앵커. 새 선택 시 이전 앵커는 detach. */
    @Volatile private var selectedAnchor: Anchor? = null

    /** GL thread 에서 매 프레임 호출 — anchor 를 화면 좌표로 투영하여 UI state 갱신. */
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

    /** CameraScreen 에서 ARCore 백엔드 활성 시 호출 — 소스를 등록하고 프레임 콜백 연결. */
    fun attachArCoreSource(source: ArCoreFrameSource) {
        arSource = source
        source.arFrameCallback = arFrameCallback
        Log.i(TAG, "ARCore 소스 부착 — anchor 추종 활성화")
    }

    /**
     * 모델 로딩 완료 여부.
     * Intro 단계에서 이미 loadModel + 워밍업이 수행되므로 Camera 진입 시점에는 항상 false.
     * 과거 호환을 위해 필드는 유지하지만 항상 준비된 상태.
     */
    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    /** 선택된 검출기 구현체 — Registry에서 캐시된 Singleton 인스턴스를 획득 */
    val detectionProvider: DetectionProvider

    /** Main 에서 토글된 ARCore 사용 여부. CameraScreen 이 FrameSource 선택 시 사용. */
    val useArCore: Boolean

    /** Main 에서 토글된 "VLM 전체 이미지 분석" 플래그. true 면 탭 시 객체 크롭 대신 전체 프레임 사용. */
    val useFullFrameVlm: Boolean

    companion object {
        private const val TAG = "CameraVM"
    }

    init {
        // Navigation argument에서 modelId / detectorId / useArCore 읽기
        val modelId = savedStateHandle.get<String>("modelId") ?: ModelRegistry.DEFAULT_MODEL_ID
        val modelInfo = ModelRegistry.getModel(modelId) ?: ModelRegistry.getDefaultModel()

        val detectorKind = DetectorKind.fromId(savedStateHandle.get<String>("detectorId"))
        detectionProvider = detectionProviderRegistry.get(detectorKind)
        // Registry Singleton은 재사용되므로 이전 세션 paused 플래그를 초기화
        detectionProvider.paused = false

        useArCore = savedStateHandle.get<Boolean>("useArCore") ?: false
        useFullFrameVlm = savedStateHandle.get<Boolean>("fullFrameVlm") ?: true
        Log.i(TAG, "검출기 선택: ${detectorKind.displayName} arcore=$useArCore fullFrameVlm=$useFullFrameVlm")

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

        // ARCore 활성 시 anchor 생성 (탭 위치에 hitTest, 실패 시 카메라 전방 1.5m fallback)
        replaceAnchor(createAnchorAt(tapPoint.x, tapPoint.y))

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

        // Main 에서 "전체 이미지 분석" 토글이 ON 이면 객체 crop 을 건너뛰고 전체 프레임 사용.
        // 상황 설명 품질이 안정적(맥락 보존). 선택 객체 정보는 anchor/오버레이에만 쓰임.
        if (useFullFrameVlm || resolved == null) {
            runSceneInference(frame)
        } else {
            val crop = RoiCropper.crop(frame, resolved.boundingBox)
            runObjectInference(crop, resolved.label, resolved.confidence)
        }
    }

    /**
     * 화면 좌표 [screenX, screenY] 에 해당하는 월드 위치에 anchor 생성을 시도한다.
     * - ARCore 미활성 → null
     * - tracking 미초기화 → null
     * - hitTest 결과 있음 → 가장 가까운 hit 의 anchor
     * - hitTest 결과 없음 → 카메라 전방 1.5m 위치에 fallback anchor
     */
    private fun createAnchorAt(screenX: Float, screenY: Float): Anchor? {
        val source = arSource ?: return null
        val frame = source.latestFrame ?: return null
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.d(TAG, "▶ tracking 미초기화 — anchor 생성 보류")
            return null
        }
        return try {
            val hits = frame.hitTest(screenX, screenY)
            val hit = hits.firstOrNull()
            if (hit != null) {
                Log.d(TAG, "▶ hitTest 성공 (${hits.size}개) — anchor 생성")
                hit.createAnchor()
            } else {
                Log.d(TAG, "▶ hitTest 결과 없음 — 전방 1.5m fallback anchor")
                forwardAnchor(frame, source, distanceMeters = 1.5f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "anchor 생성 예외", e)
            null
        }
    }

    /** 카메라가 향하는 방향으로 [distanceMeters] 만큼 떨어진 점에 anchor 생성. */
    private fun forwardAnchor(frame: Frame, source: ArCoreFrameSource, distanceMeters: Float): Anchor? {
        val cameraPose = frame.camera.pose
        val forward = floatArrayOf(0f, 0f, -distanceMeters)  // 카메라 로컬 -Z
        val rotated = FloatArray(3)
        cameraPose.rotateVector(forward, 0, rotated, 0)
        val pose = Pose.makeTranslation(
            cameraPose.tx() + rotated[0],
            cameraPose.ty() + rotated[1],
            cameraPose.tz() + rotated[2]
        )
        return source.createAnchor(pose)
    }

    /** 이전 anchor detach 후 새 anchor 로 교체. anchor 가 없으면 화면 좌표 + 플래그 모두 클리어. */
    private fun replaceAnchor(newAnchor: Anchor?) {
        val old = selectedAnchor
        selectedAnchor = newAnchor
        try {
            old?.detach()
        } catch (e: Exception) {
            Log.w(TAG, "이전 anchor detach 실패", e)
        }
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
        onConfirmed: (normX: Float, normY: Float, dirX: Float, dirY: Float) -> Unit,
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
     * "person" 라벨은 필터링 (본인 손 오탐 차단). 미매칭 시 "뒷 배경" 크롭 fallback —
     * 손이 프레임에 잡히지 않도록 포인팅 방향으로 크롭 중심을 오프셋한다.
     *
     * @param dirX 검지 방향 x (0 이면 방향 불명 — 오프셋 없이 포인팅 좌표 사용)
     * @param dirY 검지 방향 y
     */
    fun onPointingConfirmed(
        normX: Float, normY: Float,
        dirX: Float, dirY: Float,
        viewWidth: Float, viewHeight: Float
    ) {
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

        // ARCore 활성 시 anchor 생성 (포인팅 좌표에 hitTest)
        replaceAnchor(createAnchorAt(screenPoint.x, screenPoint.y))

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

        // 전체 프레임 모드: 제스처에서도 객체 크롭/손 오프셋 크롭 대신 전체 프레임 사용.
        // "상황 설명" 일관성 확보.
        if (useFullFrameVlm) {
            runSceneInference(frame)
            return
        }

        if (resolved != null) {
            val crop = RoiCropper.crop(frame, resolved.boundingBox)
            runObjectInference(crop, resolved.label, resolved.confidence)
        } else {
            // 미매칭 fallback: 손가락 끝에서 포인팅 방향으로 한 크롭 사이즈만큼 더 나아간 지점을 중심으로 크롭 →
            // 손이 프레임 밖(또는 경계)으로 빠지고 "뒷 배경"만 VLM 에 전달된다.
            // MediaPipe 의 dirX/dirY 는 정규화 이미지 좌표(0~1) 단위 벡터이므로, 픽셀 공간으로
            // 다시 변환 후 재정규화해야 세로 이미지에서도 방향이 맞다.
            val cropSize = (minOf(frame.width, frame.height) * 0.35f).toInt().coerceAtLeast(64)
            val dxPx = dirX * frame.width
            val dyPx = dirY * frame.height
            val magPx = kotlin.math.hypot(dxPx, dyPx)
            val unitPxX = if (magPx > 1e-3f) dxPx / magPx else 0f
            val unitPxY = if (magPx > 1e-3f) dyPx / magPx else 0f
            val offsetPx = cropSize.toFloat() * 0.65f  // 크롭 반 + 손 반경 여유
            val cxRaw = (normX * frame.width) + unitPxX * offsetPx
            val cyRaw = (normY * frame.height) + unitPxY * offsetPx
            val cx = cxRaw.toInt()
            val cy = cyRaw.toInt()
            val left = (cx - cropSize / 2).coerceIn(0, frame.width - cropSize)
            val top = (cy - cropSize / 2).coerceIn(0, frame.height - cropSize)
            val w = cropSize.coerceAtMost(frame.width - left)
            val h = cropSize.coerceAtMost(frame.height - top)
            val crop = Bitmap.createBitmap(frame, left, top, w, h)
            Log.d(TAG, "▶ 제스처 fallback 크롭: center=(${cx},${cy}) dir=(${"%.2f".format(unitPxX)},${"%.2f".format(unitPxY)}) offsetPx=${offsetPx.toInt()}")
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
        replaceAnchor(null)
        val oldBitmap = _uiState.value.capturedBitmap
        _uiState.value = CameraUiState(
            vlmMode = _uiState.value.vlmMode,
            modelDisplayName = _uiState.value.modelDisplayName,
            detectorDisplayName = _uiState.value.detectorDisplayName
        )
        oldBitmap?.recycle()
    }

    /**
     * ViewModel 소멸 시 캡처 비트맵·anchor 해제 + ARCore 콜백 분리.
     * InferenceEngine과 DetectionProvider는 Singleton이므로 Camera 재진입 시 재사용된다.
     * 다음 진입 시 paused 플래그만 init에서 초기화.
     */
    override fun onCleared() {
        super.onCleared()
        arSource?.arFrameCallback = null
        arSource = null
        replaceAnchor(null)
        _uiState.value.capturedBitmap?.recycle()
        // 검출기는 Singleton이므로 Analyzer가 종료된 후 프레임 처리를 중단하도록 paused 플래그만 세팅
        detectionProvider.paused = true
    }
}

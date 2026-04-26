package com.vrtmv.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ar.core.ArCoreApk
import com.vrtmv.app.data.camera.ArCoreFrameSource
import com.vrtmv.app.data.camera.CameraXFrameSource
import com.vrtmv.app.data.camera.FrameListener
import com.vrtmv.app.data.camera.FrameSource
import com.vrtmv.app.data.inference.VlmMode
import com.vrtmv.app.ui.overlay.DetectionOverlay
import com.vrtmv.app.ui.overlay.GazeCrosshair
import com.vrtmv.app.ui.overlay.PointingProgressRing
import com.vrtmv.app.ui.components.ResultCard
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.ArTeal
import com.vrtmv.app.ui.theme.OverlayTagBg
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceElevated
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    if (hasPermission) {
        CameraContent(viewModel = viewModel)
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "카메라 권한이 필요합니다.\n설정에서 권한을 허용해주세요.",
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * 사용자 토글([useArCore])과 ARCore 가용성을 함께 고려하여 [FrameSource] 를 생성한다.
 * - useArCore=false → 항상 CameraX
 * - useArCore=true + SUPPORTED + 정상 Session 생성 → ARCore 백엔드
 * - useArCore=true + 미지원/Session 실패 → CameraX 자동 폴백
 *
 * 결과는 logcat 에 명시적으로 기록 — 과제 시연/검증 시 어느 백엔드인지 즉시 확인.
 */
private fun selectFrameSource(context: Context, useArCore: Boolean): FrameSource {
    if (!useArCore) {
        Log.i(TAG, "FrameSource=CameraX (사용자 토글 OFF)")
        return CameraXFrameSource(context)
    }

    val availability = try {
        ArCoreApk.getInstance().checkAvailability(context)
    } catch (e: Throwable) {
        Log.w(TAG, "ARCore checkAvailability 실패", e)
        null
    }
    Log.i(TAG, "ARCore availability=$availability")

    if (availability != null && availability.isSupported && !availability.isTransient) {
        try {
            val source = ArCoreFrameSource(context)
            Log.i(TAG, "FrameSource=ArCore (월드 앵커 활성화)")
            return source
        } catch (e: Throwable) {
            Log.w(TAG, "ARCore Session 생성 실패 — CameraX 폴백", e)
        }
    } else {
        Log.i(TAG, "ARCore 미지원/미준비 — CameraX 폴백 (avail=$availability)")
    }
    return CameraXFrameSource(context).also {
        Log.i(TAG, "FrameSource=CameraX (폴백 경로)")
    }
}

@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val modelLoading by viewModel.modelLoading.collectAsState()

    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    // 검출기 인스턴스는 ViewModel 소유 (onCleared에서 해제)
    val detectionProvider = viewModel.detectionProvider

    // 손 제스처 검출 — 터치와 병행. AssetPathResolver 주입은 ViewModel 팩토리가 담당.
    val gestureDetector = remember {
        viewModel.createGestureDetector(
            onUpdate = { x, y, progress -> viewModel.onPointingUpdate(x, y, progress) },
            onConfirmed = { x, y, dx, dy ->
                viewModel.onPointingConfirmed(x, y, dx, dy, viewSize.width, viewSize.height)
            },
            onLost = { viewModel.onPointingLost() }
        )
    }

    // FrameSource 선택 — Composition 동안 1회. ViewModel 의 useArCore 토글 + 가용성에 따라 결정.
    val frameSource = remember { selectFrameSource(context, viewModel.useArCore) }

    // 두 소비자(제스처 → 검출기) 를 단일 리스너로 묶어 등록.
    val frameListener = remember {
        FrameListener { bitmap, ts ->
            gestureDetector.process(bitmap, ts)
            detectionProvider.updateFrame(bitmap, ts)
        }
    }

    DisposableEffect(Unit) {
        frameSource.addListener(frameListener)
        // ARCore 백엔드인 경우 VM 에 소스를 전달해 anchor 추종 콜백 연결
        if (frameSource is ArCoreFrameSource) {
            viewModel.attachArCoreSource(frameSource)
        }
        frameSource.start(lifecycleOwner)
        onDispose {
            frameSource.removeListener(frameListener)
            frameSource.close()
            gestureDetector.close()
        }
    }

    val coordinateMapper = uiState.coordinateMapper

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it.toSize() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        viewModel.onTapDetect(
                            tapPoint = offset,
                            viewWidth = viewSize.width,
                            viewHeight = viewSize.height
                        )
                    },
                    onLongPress = {
                        viewModel.clearSelection()
                    }
                )
            }
    ) {
        // Layer 1: Camera preview — FrameSource 가 PreviewView 또는 GLSurfaceView 를 노출
        AndroidView(
            factory = { frameSource.view },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: AR overlay
        val showOverlay = coordinateMapper != null && (
            uiState.detectedObjects.isNotEmpty() ||
            (uiState.tapPoint != null && uiState.inferenceState !is com.vrtmv.app.domain.model.InferenceState.Idle)
        )
        if (showOverlay) {
            DetectionOverlay(
                detectedObjects = uiState.detectedObjects,
                selectedObject = uiState.selectedObject,
                inferenceState = uiState.inferenceState,
                coordinateMapper = coordinateMapper!!,
                tapPoint = uiState.tapPoint,
                anchoredTagPosition = uiState.anchoredTagPosition,
                arAnchorActive = uiState.arAnchorActive,
                vlmMode = uiState.vlmMode,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Layer 3: Tap crosshair
        uiState.tapPoint?.let { point ->
            GazeCrosshair(
                position = point,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Layer 3b: 포인팅 홀드 진행률 링 (손 제스처 캡처 중)
        uiState.pointingPosition?.let { normPos ->
            if (viewSize.width > 0 && viewSize.height > 0) {
                val screenPos = androidx.compose.ui.geometry.Offset(
                    normPos.x * viewSize.width,
                    normPos.y * viewSize.height
                )
                PointingProgressRing(
                    position = screenPos,
                    progress = uiState.pointingProgress,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Layer 4: Bottom hint / result card
        ResultCard(
            inferenceState = uiState.inferenceState,
            selectedObject = uiState.selectedObject,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        // Layer 4.5: Stop / Clear button
        val showStopButton = uiState.inferenceState is com.vrtmv.app.domain.model.InferenceState.Loading
        val showClearButton = uiState.inferenceState is com.vrtmv.app.domain.model.InferenceState.Success ||
            uiState.inferenceState is com.vrtmv.app.domain.model.InferenceState.Error ||
            (uiState.inferenceState is com.vrtmv.app.domain.model.InferenceState.Idle && uiState.selectedObject != null)

        if (showStopButton) {
            FilledTonalIconButton(
                onClick = { viewModel.clearSelection() },
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = OverlayTagBg,
                    contentColor = StatusError
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 104.dp, end = 16.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "추론 중지",
                    modifier = Modifier.size(22.dp)
                )
            }
        } else if (showClearButton) {
            FilledTonalIconButton(
                onClick = { viewModel.clearSelection() },
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = SurfaceElevated.copy(alpha = 0.85f),
                    contentColor = ArCyan
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 104.dp, end = 16.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "결과 지우기",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Layer 5: VLM mode toggle button
        VlmToggleButton(
            currentMode = uiState.vlmMode,
            onToggle = { viewModel.toggleVlmMode() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        )

        // Layer 6: Model name + inference time
        if (uiState.modelDisplayName.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 16.dp)
                    .background(
                        SurfaceElevated.copy(alpha = 0.8f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.modelDisplayName,
                    color = ArCyan,
                    style = MaterialTheme.typography.labelMedium
                )
                if (uiState.inferenceTimeMs > 0) {
                    val sec = (uiState.inferenceTimeMs + 500) / 1000
                    Text(
                        text = "약 ${sec}초",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Layer 7: 모델 로딩 칩 (논블로킹) — 카메라 프리뷰는 즉시 보여주고 상단에 작은 인디케이터만 표시
        if (modelLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp)
                    .background(
                        SurfaceElevated.copy(alpha = 0.85f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    color = ArCyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "모델 초기화 중",
                    color = TextPrimary.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun VlmToggleButton(
    currentMode: VlmMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOn = currentMode == VlmMode.ON
    val containerColor = if (isOn) ArTeal.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f)
    val iconColor = if (isOn) Color.White else Color.White.copy(alpha = 0.5f)

    FilledTonalIconButton(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor,
            contentColor = iconColor
        ),
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = "VLM Mode: ${currentMode.label}",
            modifier = Modifier.size(22.dp)
        )
    }
}

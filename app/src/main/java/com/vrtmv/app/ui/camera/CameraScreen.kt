package com.vrtmv.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.ui.overlay.DetectionOverlay
import com.vrtmv.app.ui.overlay.GazeCrosshair
import com.vrtmv.app.ui.components.ResultCard
import com.vrtmv.app.ui.theme.ArCyan
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

// ARCore 항상 우선 시도, 미지원/Session 생성 실패 시 CameraX 자동 폴백.
private fun selectFrameSource(context: Context): FrameSource {
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

    val frameProvider = viewModel.frameProvider
    val captureMode = viewModel.captureMode
    val isObjectMode = captureMode.isObjectMode
    val isFullFrameMode = captureMode == CaptureMode.FULL_FRAME

    val frameSource = remember { selectFrameSource(context) }

    val frameListener = remember {
        FrameListener { bitmap, ts ->
            viewModel.reportFrame(ts)
            frameProvider.updateFrame(bitmap, ts)
        }
    }

    DisposableEffect(Unit) {
        frameSource.addListener(frameListener)
        if (frameSource is ArCoreFrameSource) {
            viewModel.attachArCoreSource(frameSource)
        }
        frameSource.start(lifecycleOwner)
        onDispose {
            frameSource.removeListener(frameListener)
            frameSource.close()
        }
    }

    // 토스트 메시지 수신 — 객체 탐지 실패 안내 등
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val coordinateMapper = uiState.coordinateMapper

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it.toSize() }
            .pointerInput(isObjectMode) {
                detectTapGestures(
                    onTap = { offset ->
                        if (!isObjectMode) return@detectTapGestures
                        viewModel.onTapDetect(
                            tapPoint = offset,
                            viewWidth = viewSize.width,
                            viewHeight = viewSize.height
                        )
                    },
                    onLongPress = { viewModel.clearSelection() }
                )
            }
    ) {
        AndroidView(
            factory = { frameSource.view },
            modifier = Modifier.fillMaxSize()
        )

        // FULL_FRAME 에서도 AR anchored tag / scene tag 가 떠야 하므로 isObjectMode 필터 제거.
        // FULL 모드는 detectedObjects 가 빈 리스트라 박스는 어차피 그려지지 않음.
        val showOverlay = coordinateMapper != null && (
            uiState.detectedObjects.isNotEmpty() ||
            (uiState.tapPoint != null && uiState.inferenceState !is InferenceState.Idle)
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
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isObjectMode) {
            uiState.tapPoint?.let { point ->
                GazeCrosshair(
                    position = point,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // FULL_FRAME 모드는 시작 버튼이 액션 어포던스 역할을 하므로 HINT 카드를 생략한다.
        if (!isFullFrameMode) {
            ResultCard(
                inferenceState = uiState.inferenceState,
                selectedObject = uiState.selectedObject,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }

        if (isFullFrameMode) {
            val isLoading = uiState.inferenceState is InferenceState.Loading
            Button(
                onClick = {
                    viewModel.startFullFrameCapture(viewSize.width, viewSize.height)
                },
                enabled = !isLoading && !modelLoading,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ArCyan,
                    contentColor = SurfaceElevated,
                    disabledContainerColor = ArCyan.copy(alpha = 0.35f),
                    disabledContentColor = SurfaceElevated.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp)
                    .size(width = 200.dp, height = 60.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = if (isLoading) "추론 중..." else "시작",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        val showStopButton = uiState.inferenceState is InferenceState.Loading
        // 모든 모드/상태에서 클린 버튼(X) 노출. Loading 시는 Stop 으로 대체.
        val showClearButton = !showStopButton

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
                    .padding(top = 48.dp, end = 16.dp)
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
                    .padding(top = 48.dp, end = 16.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "결과 지우기",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

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
                Text(
                    text = "· ${viewModel.captureMode.displayName}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
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

package com.vrtmv.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrtmv.app.data.detection.ObjectDetectionManager
import com.vrtmv.app.data.inference.VlmMode
import com.vrtmv.app.ui.overlay.DetectionOverlay
import com.vrtmv.app.ui.overlay.GazeCrosshair
import com.vrtmv.app.ui.components.ResultCard
import com.vrtmv.app.util.CoordinateMapper

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
            // 카메라 + 저장소 읽기 권한 동시 요청
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ))
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

@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    val detectionManager = remember { ObjectDetectionManager(context) }

    DisposableEffect(Unit) {
        onDispose { detectionManager.close() }
    }

    val coordinateMapper = remember(
        uiState.imageWidth, uiState.imageHeight, viewSize
    ) {
        if (uiState.imageWidth > 0 && viewSize.width > 0f) {
            CoordinateMapper(
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                viewWidth = viewSize.width,
                viewHeight = viewSize.height
            )
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it.toSize() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        viewModel.onTapDetect(
                            tapPoint = offset,
                            detectionManager = detectionManager,
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
        // Layer 1: Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    @Suppress("DEPRECATION")
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(640, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(
                                ContextCompat.getMainExecutor(ctx)
                            ) { imageProxy ->
                                detectionManager.updateFrame(imageProxy)
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: AR overlay
        if (coordinateMapper != null && uiState.detectedObjects.isNotEmpty()) {
            DetectionOverlay(
                detectedObjects = uiState.detectedObjects,
                selectedObject = uiState.selectedObject,
                inferenceState = uiState.inferenceState,
                coordinateMapper = coordinateMapper,
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

        // Layer 4: Bottom hint / result card
        ResultCard(
            inferenceState = uiState.inferenceState,
            selectedObject = uiState.selectedObject,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        // Layer 5: VLM mode toggle button (top-right)
        VlmToggleButton(
            currentMode = uiState.vlmMode,
            onToggle = { viewModel.toggleVlmMode() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        )
    }
}

@Composable
private fun VlmToggleButton(
    currentMode: VlmMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOn = currentMode == VlmMode.ON
    val containerColor = if (isOn) Color(0xFF00897B).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f)
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

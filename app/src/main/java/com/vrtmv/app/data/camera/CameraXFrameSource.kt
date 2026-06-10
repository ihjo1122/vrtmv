package com.vrtmv.app.data.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vrtmv.app.util.ImageProxyConverter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXFrameSource(private val context: Context) : FrameSource {

    private val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    override val view: View get() = previewView

    companion object {
        private const val TAG = "CameraXFrameSrc"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<FrameListener>()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var closed: Boolean = false

    override fun start(lifecycleOwner: LifecycleOwner) {
        if (closed) {
            Log.w(TAG, "이미 close 된 소스 — start 무시")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = try { future.get() } catch (e: Exception) {
                Log.e(TAG, "ProcessCameraProvider 획득 실패", e)
                return@addListener
            }
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // setTargetResolution 은 1.4 에서 deprecated 지만 ResolutionSelector 가 일부 기기에서
            // RGBA_8888 출력 형식과 충돌해 폴백 필요 — 호환성 우선해 명시적으로 deprecation 억제.
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, ::dispatchFrame) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                Log.i(TAG, "CameraX 바인딩 완료")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun dispatchFrame(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }
        var bitmap = ImageProxyConverter.toUprightBitmap(imageProxy)
        imageProxy.close()
        if (bitmap == null) return

        val ts = System.currentTimeMillis()
        try {
            for (listener in listeners) {
                try {
                    listener.onFrame(bitmap, ts)
                } catch (e: Exception) {
                    Log.e(TAG, "리스너 처리 실패", e)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun stop() {
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll 실패", e)
        }
        provider = null
    }

    override fun addListener(listener: FrameListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    override fun close() {
        closed = true
        stop()
        executor.shutdown()
        listeners.clear()
    }
}

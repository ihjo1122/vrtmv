package com.vrtmv.app.data.inference

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.util.ModelPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM 기반 온디바이스 추론 엔진.
 * Gemma 3n 등 .litertlm 모델을 로드하여 멀티모달(이미지+텍스트) 추론을 수행한다.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    private val pathResolver: ModelPathResolver
) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRtLm"
    }

    private var engine: Engine? = null
    private var currentModelId: String? = null
    private var modelAvailable: Boolean? = null
    private val mutex = Mutex()

    /**
     * 지정된 모델을 로드한다.
     * @return true=로드 성공, false=실패
     */
    override suspend fun loadModel(modelInfo: ModelInfo): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            // 이미 같은 모델이 로드되어 있으면 스킵
            if (currentModelId == modelInfo.id && engine != null) {
                Log.d(TAG, "이미 로드된 모델: ${modelInfo.displayName}")
                return@withContext true
            }

            // 기존 엔진 해제
            releaseInternal()

            val modelPath = pathResolver.findModelPath(modelInfo)
            if (modelPath == null) {
                Log.w(TAG, "모델 파일 없음: ${modelInfo.fileName}")
                modelAvailable = false
                return@withContext false
            }

            try {
                // GPU 비전 백엔드 우선 시도, 실패 시 CPU 폴백
                val newEngine = try {
                    val gpuConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU,
                        visionBackend = Backend.GPU
                    )
                    Engine(gpuConfig).also { it.initialize() }
                } catch (gpuError: Exception) {
                    Log.w(TAG, "GPU 비전 백엔드 실패, CPU 폴백: ${gpuError.message}")
                    val cpuConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU,
                        visionBackend = Backend.CPU
                    )
                    Engine(cpuConfig).also { it.initialize() }
                }

                engine = newEngine
                currentModelId = modelInfo.id
                modelAvailable = true
                Log.d(TAG, "모델 로드 완료: ${modelInfo.displayName} ($modelPath)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "모델 로드 실패: ${modelInfo.displayName}", e)
                modelAvailable = false
                false
            }
        }
    }

    /** 현재 로드된 모델 ID 반환 */
    fun getCurrentModelId(): String? = currentModelId

    /**
     * 크롭된 이미지와 검출 정보를 기반으로 객체 설명을 생성한다.
     * 멀티모달: 이미지를 직접 VLM에 전달.
     */
    override suspend fun describe(image: Bitmap, label: String, confidence: Float): String {
        val prompt = PromptBuilder.buildVisionPrompt(label, confidence)
        val fallback = "${label}이(가) 감지되었습니다."
        return infer(image, prompt, fallback)
    }

    override suspend fun describeScene(image: Bitmap): String {
        val prompt = PromptBuilder.buildScenePrompt()
        return infer(image, prompt, "장면을 분석할 수 없습니다.")
    }

    private suspend fun infer(image: Bitmap, prompt: String, fallback: String): String {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val currentEngine = engine
                if (currentEngine == null) {
                    return@withContext "모델을 찾을 수 없습니다.\n" +
                        "Download/vrtmv/ 폴더에 .litertlm 모델 파일을 넣어주세요."
                }

                try {
                    val resized = resizeForVlm(image)
                    val imageBytes = bitmapToJpegBytes(resized)
                    if (resized != image) resized.recycle()

                    currentEngine.createConversation().use { conversation ->
                        val message = Message.of(
                            Content.ImageBytes(imageBytes),
                            Content.Text(prompt)
                        )
                        val response = conversation.sendMessage(message)
                        val text = response.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        text.trim().ifEmpty { fallback }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "추론 실패", e)
                    "추론 실패: ${e.message}"
                }
            }
        }
    }

    override fun isAvailable(): Boolean {
        if (modelAvailable != null) return modelAvailable!!
        return true
    }

    /** 엔진 리소스 해제 (카메라 화면 종료 시 호출, 동기) */
    override fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "엔진 해제 중 오류", e)
        }
        engine = null
        currentModelId = null
        modelAvailable = null
    }

    /**
     * VLM 입력 최적화: 최대 512px로 리사이즈.
     * Gemma 3n 비전 인코더 내부적으로 224~256px 사용하므로 512px 이상은 불필요.
     */
    private fun resizeForVlm(bitmap: Bitmap, maxDim: Int = 512): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        Log.d(TAG, "이미지 리사이즈: ${w}x${h} → ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }
}

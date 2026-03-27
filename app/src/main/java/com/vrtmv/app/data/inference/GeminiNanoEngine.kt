package com.vrtmv.app.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 온디바이스 LLM 추론 엔진 (멀티 모델 지원).
 *
 * Singleton으로 유지하되 loadModel()로 모델 교체 가능.
 * 모델별 내부 저장소 경로를 분리하여 잘못된 모델 로드 방지.
 * mutex로 추론 중 모델 교체를 차단.
 */
@Singleton
class GeminiNanoEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {

    companion object {
        private const val TAG = "OnDeviceLLM"
    }

    private var llmInference: LlmInference? = null
    private var currentModelId: String? = null
    private var modelAvailable: Boolean? = null
    private val mutex = Mutex()

    /**
     * 지정된 모델을 로드한다.
     * 기존 모델이 있으면 해제 후 새 모델로 교체.
     * CameraScreen 밖에서만 호출할 것 (OOM 방지).
     *
     * @return true=로드 성공, false=실패
     */
    suspend fun loadModel(modelInfo: ModelInfo): Boolean = mutex.withLock {
        return@withLock withContext(Dispatchers.IO) {
            // 이미 같은 모델이 로드되어 있으면 스킵
            if (currentModelId == modelInfo.id && llmInference != null) {
                Log.d(TAG, "이미 로드된 모델: ${modelInfo.displayName}")
                return@withContext true
            }

            // 기존 모델 해제
            releaseModelInternal()

            val modelPath = findModelPath(modelInfo)
            if (modelPath == null) {
                Log.w(TAG, "모델 파일 없음: ${modelInfo.fileName}")
                modelAvailable = false
                return@withContext false
            }

            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(256)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
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

    /**
     * 모델 파일 경로를 탐색한다.
     * 모델별로 내부 저장소 경로를 분리: files/{modelId}.task
     */
    private fun findModelPath(modelInfo: ModelInfo): String? {
        // 1순위: 앱 내부 저장소 (모델별 분리)
        val internalModel = File(context.filesDir, "${modelInfo.id}.task")
        if (internalModel.exists() && internalModel.length() > 100_000_000) {
            return internalModel.absolutePath
        }

        // 2순위: Download/vrtmv/{fileName}
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val vrtmvModel = File(downloadDir, "vrtmv/${modelInfo.fileName}")
            if (vrtmvModel.exists() && vrtmvModel.length() > 100_000_000) {
                Log.d(TAG, "Download/vrtmv에서 모델 발견: ${modelInfo.fileName}")
                // 앱 내부 저장소로 복사 (모델별 경로)
                vrtmvModel.copyTo(internalModel, overwrite = true)
                Log.d(TAG, "→ 내부 저장소로 복사: ${internalModel.name}")
                return internalModel.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download 폴더 탐색 실패", e)
        }

        return null
    }

    /** 현재 모델 해제 (내부용, mutex 잠금 상태에서 호출) */
    private fun releaseModelInternal() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "모델 해제 중 오류", e)
        }
        llmInference = null
        currentModelId = null
        modelAvailable = null
    }

    /** 현재 로드된 모델 ID 반환 */
    fun getCurrentModelId(): String? = currentModelId

    /**
     * 객체 설명을 생성한다.
     * mutex로 추론 중 모델 교체를 차단.
     */
    override suspend fun describe(image: Bitmap, label: String, confidence: Float): String {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val inference = llmInference

                if (inference == null) {
                    // 모델이 아직 로드되지 않았으면 기본 모델 시도
                    val defaultModel = ModelRegistry.getDefaultModel()
                    val loaded = loadModelWithoutLock(defaultModel)
                    if (!loaded || llmInference == null) {
                        return@withContext "모델을 찾을 수 없습니다.\n" +
                            "Download 폴더에 .task 모델 파일을 넣어주세요."
                    }
                }

                try {
                    val prompt = PromptBuilder.buildDescriptionPrompt(label, confidence)
                    val result = llmInference!!.generateResponse(prompt)
                    result.trim().ifEmpty { "${label}이(가) 감지되었습니다." }
                } catch (e: Exception) {
                    Log.e(TAG, "추론 실패", e)
                    "${label} (${(confidence * 100).toInt()}%) - 추론 실패: ${e.message}"
                }
            }
        }
    }

    /** loadModel의 내부 버전 (이미 mutex 잠금 상태에서 호출) */
    private suspend fun loadModelWithoutLock(modelInfo: ModelInfo): Boolean {
        return withContext(Dispatchers.IO) {
            if (currentModelId == modelInfo.id && llmInference != null) return@withContext true

            releaseModelInternal()

            val modelPath = findModelPath(modelInfo) ?: return@withContext false

            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(256)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                currentModelId = modelInfo.id
                modelAvailable = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "모델 로드 실패: ${modelInfo.displayName}", e)
                modelAvailable = false
                false
            }
        }
    }

    override fun isAvailable(): Boolean {
        if (modelAvailable != null) return modelAvailable!!
        return true
    }
}

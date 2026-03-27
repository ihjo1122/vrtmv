package com.vrtmv.app.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 온디바이스 LLM 추론 엔진.
 * MediaPipe LLM Inference API를 사용하여 기기 내에서 직접 추론.
 * 네트워크 없이 완전 오프라인 동작.
 *
 * 모델 배치 방법:
 * 1. HuggingFace에서 .task 파일 다운로드
 *    (예: gemma3-1b-it-int4.task)
 * 2. 기기의 Download 폴더에 넣기 (USB/브라우저 등)
 * 3. 앱이 자동으로 Download 폴더에서 .task 파일을 탐색하여 사용
 */
class GeminiNanoEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {

    companion object {
        private const val TAG = "OnDeviceLLM"
        private const val MODEL_FILENAME = "model.task" // 앱 내부 저장소의 모델 파일명
    }

    private var llmInference: LlmInference? = null
    private var modelAvailable: Boolean? = null

    /**
     * 모델 파일 경로를 탐색한다.
     * 우선순위:
     * 1. 앱 내부 저장소 (files/model.task)
     * 2. Download 폴더에서 .task 파일 자동 탐색
     *    → 발견 시 앱 내부 저장소로 복사
     */
    private suspend fun getModelPath(): String? {
        // 1순위: 앱 내부 저장소에 이미 있는 경우
        val internalModel = File(context.filesDir, MODEL_FILENAME)
        if (internalModel.exists()) return internalModel.absolutePath

        // 2순위: Download/vrtmv/ 폴더의 정확한 경로 확인
        return withContext(Dispatchers.IO) {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadDir.exists()) return@withContext null

                // Download/vrtmv/gemma3-1b-it-int4.task 확인
                val vrtmvModel = File(downloadDir, "vrtmv/gemma3-1b-it-int4.task")
                if (vrtmvModel.exists() && vrtmvModel.length() > 100_000_000) {
                    Log.d(TAG, "Download/vrtmv에서 모델 발견: ${vrtmvModel.length() / 1024 / 1024}MB")
                    vrtmvModel.copyTo(internalModel, overwrite = true)
                    Log.d(TAG, "모델을 앱 내부 저장소로 복사 완료")
                    return@withContext internalModel.absolutePath
                }

                // 3순위: Download 루트 폴더에서 .task 파일 탐색 (폴백)
                val taskFile = downloadDir.listFiles()
                    ?.filter { it.extension == "task" && it.length() > 100_000_000 }
                    ?.maxByOrNull { it.length() }

                if (taskFile != null) {
                    Log.d(TAG, "Download 폴더에서 모델 발견: ${taskFile.name} (${taskFile.length() / 1024 / 1024}MB)")

                    // 앱 내부 저장소로 복사
                    taskFile.copyTo(internalModel, overwrite = true)
                    Log.d(TAG, "모델을 앱 내부 저장소로 복사 완료")
                    internalModel.absolutePath
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download 폴더 탐색 실패", e)
                null
            }
        }
    }

    /** 모델을 초기화한다. 이미 초기화됐으면 기존 인스턴스 반환 */
    private suspend fun initializeModel(): LlmInference? {
        if (llmInference != null) return llmInference

        val modelPath = getModelPath()
        if (modelPath == null) {
            Log.w(TAG, "모델 파일을 찾을 수 없습니다")
            modelAvailable = false
            return null
        }

        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(256)
                .build()

            val inference = LlmInference.createFromOptions(context, options)
            llmInference = inference
            modelAvailable = true
            Log.d(TAG, "온디바이스 LLM 초기화 완료: $modelPath")
            inference
        } catch (e: Exception) {
            Log.e(TAG, "온디바이스 LLM 초기화 실패", e)
            modelAvailable = false
            null
        }
    }

    /**
     * 객체 설명을 생성한다.
     * 텍스트 전용 모델이므로 라벨+신뢰도 기반 프롬프트 사용.
     */
    override suspend fun describe(image: Bitmap, label: String, confidence: Float): String {
        return withContext(Dispatchers.IO) {
            val inference = initializeModel()

            if (inference == null) {
                return@withContext "모델을 찾을 수 없습니다.\n" +
                    "Download 폴더에 .task 모델 파일을 넣어주세요.\n" +
                    "(예: gemma3-1b-it-int4.task)"
            }

            try {
                val prompt = PromptBuilder.buildDescriptionPrompt(label, confidence)
                val result = inference.generateResponse(prompt)
                result.trim().ifEmpty { "${label}이(가) 감지되었습니다." }
            } catch (e: Exception) {
                Log.e(TAG, "추론 실패", e)
                "${label} (${(confidence * 100).toInt()}%) - 추론 실패: ${e.message}"
            }
        }
    }

    override fun isAvailable(): Boolean {
        if (modelAvailable != null) return modelAvailable!!
        return true // 첫 호출 시까지 가용으로 가정
    }
}

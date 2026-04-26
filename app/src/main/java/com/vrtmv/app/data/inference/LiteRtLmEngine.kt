package com.vrtmv.app.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.util.ModelPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM 기반 온디바이스 추론 엔진.
 * Gemma 3n E2B-IT 등 .litertlm 멀티모달 모델을 로드하여 이미지+텍스트 추론을 수행한다.
 *
 * 운영 축:
 *   1) 백엔드 프로파일: [BackendProfile] 로 GPU/CPU 분배를 전환한다. 로드 시점에만
 *      반영돼 세션 중 재초기화 비용을 피한다. PowerManager thermal/저전력 상태에 따라
 *      자동 강등된다.
 *   2) 프롬프트 기반 출력 길이 제약으로 decode 토큰 수를 줄인다 (PromptBuilder).
 *
 * 유휴 언로드는 LiteRT-LM 0.10.0이 `Engine.close()` 후 재초기화를 안정적으로 지원하는지
 * 검증되지 않아 현재 비활성화. 메모리 회수는 화면 이탈 시 [release]로만 수행한다.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathResolver: ModelPathResolver
) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRtLm"

        // 전체 컨텍스트 토큰 예산 (입력 이미지 토큰 + 프롬프트 + 출력 포함).
        // LiteRT-LM `EngineConfig.maxNumTokens`는 전체 컨텍스트 길이이고,
        // 0.10.0 API에는 별도의 output token 한도가 없음 — 출력은 EOS/cancel로만 종료.
        // 실측(S23 Ultra, Gemma 3n E2B int4): 512 유지가 최적.
        private const val MAX_CONTEXT_TOKENS = 512

        // VLM 입력 이미지 최대 변 — Gemma 3n 비전 인코더 내부 해상도(256px)에 맞춤
        private const val VLM_IMAGE_MAX_DIM = 256

        // JPEG 품질 — 온디바이스 추론이므로 전송 비용 없음. 세부 특징 보존을 위해 75 유지
        private const val VLM_JPEG_QUALITY = 75

        // 그리디 디코딩용 샘플러 설정 (topK=1은 temperature 무시)
        private val GREEDY_SAMPLER = SamplerConfig(
            /* topK = */ 1,
            /* topP = */ 1.0,
            /* temperature = */ 0.0,
            /* seed = */ 0
        )
    }

    /** 엔진 로드 상태 */
    sealed class LoadState {
        data object NotLoaded : LoadState()
        data object Ready : LoadState()
        data object FileMissing : LoadState()
        data class Failed(val reason: String) : LoadState()
    }

    /**
     * 백엔드 프로파일. 속도/발열 트레이드오프를 명시적으로 선택.
     *
     * - [PERFORMANCE]: 디코더·비전 모두 GPU. 최고 속도, 최고 발열.
     * - [BALANCED]:   CPU 디코더 + GPU 비전. prefill(비전 토큰 지배)은 GPU로 빠르게,
     *                 decode는 CPU로 발열을 낮춘다. 속도 손실 ~1-2초.
     * - [COOL]:       전체 CPU. 가장 낮은 발열, 가장 느린 응답.
     */
    enum class BackendProfile { PERFORMANCE, BALANCED, COOL }

    private var engine: Engine? = null
    private var currentModelId: String? = null
    private var lastModelInfo: ModelInfo? = null
    private var modelAvailable: Boolean? = null
    private var activeBackend: String = "unknown"
    private var loadState: LoadState = LoadState.NotLoaded
    private val mutex = Mutex()

    /** 사용자 지정 프로파일. thermal/저전력 상태에 따라 런타임에 강등될 수 있다. */
    @Volatile
    private var requestedProfile: BackendProfile = BackendProfile.PERFORMANCE

    /** 외부에서 프로파일 변경. 즉시 반영하려면 다음 로드에서 적용되므로 엔진을 해제한다. */
    fun setBackendProfile(profile: BackendProfile) {
        if (profile == requestedProfile) return
        requestedProfile = profile
        Log.i(TAG, "프로파일 변경 요청: $profile → 다음 로드에서 적용")
        // 동기적으로 엔진 해제(다음 ensureLoaded가 새 프로파일로 로드)
        synchronized(this) {
            engine?.let {
                try { it.close() } catch (_: Exception) {}
            }
            engine = null
            activeBackend = "unknown"
            loadState = LoadState.NotLoaded
        }
    }

    /**
     * PowerManager 상태를 읽어 유효 프로파일을 결정한다.
     * 저전력 모드 → BALANCED, thermal SEVERE+ → COOL, MODERATE → BALANCED.
     */
    private fun effectiveProfile(): BackendProfile {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return requestedProfile
            if (pm.isPowerSaveMode) {
                Log.d(TAG, "저전력 모드 감지 → BALANCED 강등")
                return BackendProfile.BALANCED
            }
            val thermal = pm.currentThermalStatus
            when {
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> {
                    Log.d(TAG, "thermal=SEVERE+ → COOL 강등")
                    BackendProfile.COOL
                }
                thermal >= PowerManager.THERMAL_STATUS_MODERATE -> {
                    Log.d(TAG, "thermal=MODERATE → BALANCED 강등")
                    BackendProfile.BALANCED
                }
                else -> requestedProfile
            }
        } catch (e: Exception) {
            Log.w(TAG, "PowerManager 조회 실패, 요청 프로파일 유지: ${e.message}")
            requestedProfile
        }
    }

    /** 프로파일별 EngineConfig 팩토리 */
    private fun buildConfig(modelPath: String, profile: BackendProfile, cacheDirPath: String): EngineConfig {
        return when (profile) {
            BackendProfile.PERFORMANCE -> EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirPath
            )
            BackendProfile.BALANCED -> EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirPath
            )
            BackendProfile.COOL -> EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirPath
            )
        }
    }

    override suspend fun loadModel(modelInfo: ModelInfo): Boolean = mutex.withLock {
        lastModelInfo = modelInfo
        withContext(Dispatchers.IO) {
            if (currentModelId == modelInfo.id && engine != null) {
                Log.d(TAG, "이미 로드된 모델: ${modelInfo.displayName}")
                return@withContext true
            }

            releaseInternalLocked()

            val modelPath = pathResolver.findModelPath(modelInfo)
            if (modelPath == null) {
                Log.w(TAG, "모델 파일 없음: ${modelInfo.fileName}")
                modelAvailable = false
                loadState = LoadState.FileMissing
                return@withContext false
            }

            val cacheDirPath = context.cacheDir.absolutePath
            val targetProfile = effectiveProfile()
            Log.i(TAG, "로드 시작: ${modelInfo.displayName}, 프로파일=$targetProfile")

            try {
                // 요청 프로파일 → 실패 시 더 보수적인 쪽으로 폴백
                val profiles = when (targetProfile) {
                    BackendProfile.PERFORMANCE -> listOf(
                        BackendProfile.PERFORMANCE,
                        BackendProfile.BALANCED,
                        BackendProfile.COOL
                    )
                    BackendProfile.BALANCED -> listOf(
                        BackendProfile.BALANCED,
                        BackendProfile.COOL
                    )
                    BackendProfile.COOL -> listOf(BackendProfile.COOL)
                }

                var lastError: Exception? = null
                var loaded: Pair<Engine, BackendProfile>? = null
                for (p in profiles) {
                    try {
                        val cfg = buildConfig(modelPath, p, cacheDirPath)
                        val e = Engine(cfg).also { it.initialize() }
                        loaded = e to p
                        break
                    } catch (ex: Exception) {
                        lastError = ex
                        Log.w(TAG, "프로파일 $p 실패, 다음으로 폴백: ${ex.message}")
                    }
                }

                val (newEngine, usedProfile) = loaded ?: run {
                    throw lastError ?: IllegalStateException("모든 프로파일 로드 실패")
                }

                engine = newEngine
                currentModelId = modelInfo.id
                modelAvailable = true
                activeBackend = usedProfile.name
                loadState = LoadState.Ready
                Log.i(TAG, "모델 로드 완료: ${modelInfo.displayName} profile=$usedProfile maxTokens=$MAX_CONTEXT_TOKENS")
                true
            } catch (e: Exception) {
                Log.e(TAG, "모델 로드 실패: ${modelInfo.displayName}", e)
                modelAvailable = false
                loadState = LoadState.Failed(e.message ?: "알 수 없는 오류")
                false
            }
        }
    }

    /**
     * 유휴 언로드 후에도 이전에 로드한 모델을 재로드한다.
     * - 이미 로드돼 있으면 즉시 true.
     * - [lastModelInfo] 가 없으면(최초 호출 전) false.
     */
    override suspend fun ensureLoaded(): Boolean {
        if (engine != null) return true
        val info = lastModelInfo ?: run {
            Log.w(TAG, "ensureLoaded 호출됐으나 lastModelInfo 없음")
            return false
        }
        Log.d(TAG, "hot reload: ${info.displayName}")
        return loadModel(info)
    }

    fun getCurrentModelId(): String? = currentModelId
    fun getActiveBackend(): String = activeBackend

    /** 모든 추론 경로 공통 fallback — "X이(가) 감지되었습니다" 같은 trivial 텍스트 제거. */
    private val commonFallback = "설명을 불러오지 못했습니다. 다시 시도해 주세요."

    override suspend fun describe(image: Bitmap, label: String, confidence: Float): String {
        val prompt = PromptBuilder.buildVisionPrompt(label)
        return infer(image, prompt, commonFallback)
    }

    override suspend fun describeScene(image: Bitmap): String {
        val prompt = PromptBuilder.buildScenePrompt()
        return infer(image, prompt, commonFallback)
    }

    private suspend fun infer(image: Bitmap, prompt: String, fallback: String): String {
        val result = mutex.withLock {
            withContext(Dispatchers.IO) {
                val currentEngine = engine
                if (currentEngine == null) {
                    return@withContext when (val s = loadState) {
                        LoadState.FileMissing ->
                            "모델 파일을 찾을 수 없습니다. 앱을 재시작해 다운로드를 확인해 주세요."
                        is LoadState.Failed ->
                            "모델 초기화 실패: ${s.reason}"
                        LoadState.NotLoaded ->
                            "모델이 아직 로드되지 않았습니다."
                        LoadState.Ready ->
                            fallback  // race condition — "설명을 불러오지 못했습니다"
                    }
                }

                try {
                    val t0 = System.currentTimeMillis()
                    val resized = resizeForVlm(image)
                    val imageBytes = bitmapToJpegBytes(resized)
                    if (resized != image) resized.recycle()
                    val t1 = System.currentTimeMillis()

                    val convConfig = ConversationConfig(samplerConfig = GREEDY_SAMPLER)
                    currentEngine.createConversation(convConfig).use { conversation ->
                        val t2 = System.currentTimeMillis()
                        val message = Message.user(
                            Contents.of(
                                Content.ImageBytes(imageBytes),
                                Content.Text(prompt)
                            )
                        )

                        // 동기 sendMessage — 검증된 경로. 길이 단축은 PromptBuilder 프롬프트 제약으로 유도.
                        // (이전에 스트리밍+cancelProcess 조기 종료를 시도했으나 Flow 의미론이 불명확해
                        //  응답이 빈 문자열로 수집되어 fallback이 반환되는 문제 발생 → 동기 방식 복귀)
                        val response = conversation.sendMessage(message)
                        val rawText = response.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }

                        val t3 = System.currentTimeMillis()
                        val cleaned = PromptBuilder.cleanResponse(rawText)
                        Log.i(
                            TAG,
                            "▶ 추론 완료 총=${t3 - t0}ms " +
                                "(preprocess=${t1 - t0}ms, createConv=${t2 - t1}ms, sendMessage=${t3 - t2}ms), " +
                                "원본=${rawText.length}자 → 정제=${cleaned.length}자"
                        )
                        Log.d(TAG, "원본: '${rawText.replace("\n", " ")}' → 정제: '$cleaned'")
                        // 정제가 모든 내용을 제거한 경우 → 원문이 할루시네이션(loop) 이면 fallback,
                        // 아니면 원문을 살려서 보여줌.
                        when {
                            cleaned.isNotEmpty() -> cleaned
                            rawText.isBlank() -> fallback
                            PromptBuilder.isLoopyHallucination(rawText) -> {
                                Log.w(TAG, "반복 할루시네이션 감지 — fallback 사용")
                                fallback
                            }
                            else -> {
                                Log.w(TAG, "정제 결과 비어있음 — 원문 사용")
                                rawText.trim().take(80)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "추론 실패", e)
                    "추론 실패: ${e.message}"
                }
            }
        }

        return result
    }

    override fun isAvailable(): Boolean {
        if (modelAvailable != null) return modelAvailable!!
        return true
    }

    /** 명시적 해제 (카메라 화면 종료 시) */
    override fun release() {
        synchronized(this) {
            try { engine?.close() } catch (e: Exception) { Log.w(TAG, "엔진 해제 중 오류", e) }
            engine = null
            currentModelId = null
            modelAvailable = null
            activeBackend = "unknown"
            loadState = LoadState.NotLoaded
            // lastModelInfo는 유지 — 이후 ensureLoaded로 재사용 가능
        }
    }

    /** mutex 이미 보유 상태에서 호출 */
    private fun releaseInternalLocked() {
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "엔진 해제 중 오류", e) }
        engine = null
        currentModelId = null
        modelAvailable = null
        activeBackend = "unknown"
        loadState = LoadState.NotLoaded
    }

    private fun resizeForVlm(bitmap: Bitmap, maxDim: Int = VLM_IMAGE_MAX_DIM): Bitmap {
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, VLM_JPEG_QUALITY, stream)
        return stream.toByteArray()
    }
}

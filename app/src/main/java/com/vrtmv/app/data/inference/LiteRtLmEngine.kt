package com.vrtmv.app.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.vrtmv.app.data.recording.DescriptionResult
import com.vrtmv.app.data.recording.VlmTimings
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.util.ModelPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM 0.11.0 기반 온디바이스 멀티모달 엔진 (Gemma 4 MTP).
 * [BackendProfile] 로 GPU/CPU 분배를 전환하며 PowerManager thermal/저전력 상태에
 * 따라 런타임 강등된다.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathResolver: ModelPathResolver
) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRtLm"

        // 전체 컨텍스트 토큰 예산. litertlm 0.11.0 API 에도 별도 출력 한도가 없어 EOS/cancel 로만 종료.
        private const val MAX_CONTEXT_TOKENS = 512

        // Gemma 4 비전 인코더 입력 해상도 (Gemma 3n 와 동일 256px, 다르면 실측 후 조정)
        private const val VLM_IMAGE_MAX_DIM = 256

        // 256px 작은 입력에서 JPEG 75 의 8x8 블록 아티팩트가 휴지심 구멍·주름 같은
        // 변별 단서를 지워 오인식(흰 원통→"테이프")을 유발하므로 90 으로 상향.
        private const val VLM_JPEG_QUALITY = 90

        private val GREEDY_SAMPLER = SamplerConfig(
            /* topK = */ 1,
            /* topP = */ 1.0,
            /* temperature = */ 0.0,
            /* seed = */ 0
        )
    }

    sealed class LoadState {
        data object NotLoaded : LoadState()
        data object Ready : LoadState()
        data object FileMissing : LoadState()
        data class Failed(val reason: String) : LoadState()
    }

    /**
     * 백엔드 프로파일. 속도/발열 트레이드오프를 명시 선택.
     * - [PERFORMANCE]: 디코더·비전 모두 GPU. 최고 속도, 최고 발열.
     * - [BALANCED]:   CPU 디코더 + GPU 비전. prefill(비전 토큰 지배)은 GPU 로 빠르게,
     *                 decode 는 CPU 로 발열 완화. 속도 손실 ~1-2초.
     * - [COOL]:       Gemma 4 도 vision 백엔드로 GPU 를 강제(`Vision backend constraint mismatch`)
     *                 하는 경우가 많아 vision=CPU 는 사용 불가로 가정 — 현실에선 BALANCED 와 동일.
     *                 (모델이 vision=CPU 를 허용하면 폴백 시퀀스에서 자동으로 채택됨.)
     */
    enum class BackendProfile { PERFORMANCE, BALANCED, COOL }

    private var engine: Engine? = null
    private var currentModelId: String? = null
    private var lastModelInfo: ModelInfo? = null
    private var modelAvailable: Boolean? = null
    private var activeBackend: String = "unknown"
    private var loadState: LoadState = LoadState.NotLoaded
    private val mutex = Mutex()

    @Volatile
    private var requestedProfile: BackendProfile = BackendProfile.PERFORMANCE

    fun setBackendProfile(profile: BackendProfile) {
        if (profile == requestedProfile) return
        requestedProfile = profile
        Log.i(TAG, "프로파일 변경 요청: $profile → 다음 로드에서 적용")
        synchronized(this) {
            engine?.let {
                try { it.close() } catch (_: Exception) {}
            }
            engine = null
            activeBackend = "unknown"
            loadState = LoadState.NotLoaded
        }
    }

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
                visionBackend = Backend.GPU(),  // Gemma 4 vision 도 GPU 우선 — CPU 면 로드 실패 가능
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
                // 어떤 시작점이든 vision=GPU 옵션을 최소 1회는 시도 — 모델이 vision GPU 강제일 때
                // 첫 시도가 실패해도 전체 로드가 무산되지 않도록.
                val profiles = when (targetProfile) {
                    BackendProfile.PERFORMANCE -> listOf(
                        BackendProfile.PERFORMANCE,
                        BackendProfile.BALANCED,
                        BackendProfile.COOL
                    )
                    BackendProfile.BALANCED -> listOf(
                        BackendProfile.BALANCED,
                        BackendProfile.COOL,
                        BackendProfile.PERFORMANCE
                    )
                    BackendProfile.COOL -> listOf(
                        BackendProfile.COOL,
                        BackendProfile.BALANCED,
                        BackendProfile.PERFORMANCE
                    )
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

    // "X이(가) 감지되었습니다" 같은 trivial 텍스트 대신 사용자에게 보여줄 공통 fallback.
    private val commonFallback = "설명을 불러오지 못했습니다. 다시 시도해 주세요."

    override suspend fun describe(image: Bitmap, label: String, confidence: Float): DescriptionResult {
        val prompt = PromptBuilder.buildVisionPrompt()
        return infer(image, prompt, commonFallback, resizeImage = true, keepFirstSentenceOnly = true)
    }

    override suspend fun describeScene(image: Bitmap): DescriptionResult {
        val prompt = PromptBuilder.buildScenePrompt()
        // 다운스케일은 시야를 자르지 않고 픽셀 밀도만 줄이므로 풀프레임 의도와 충돌 없음.
        // 풀프레임(640x480)을 그대로 보내면 비전 토큰이 폭증해 prefill 이 수 초 길어진다.
        return infer(image, prompt, commonFallback, resizeImage = true, keepFirstSentenceOnly = false)
    }

    private fun emptyResult(text: String): DescriptionResult =
        DescriptionResult(text = text, timings = VlmTimings(0L, 0L, 0L, 0L), inputWidth = 0, inputHeight = 0)

    private suspend fun infer(
        image: Bitmap,
        prompt: String,
        fallback: String,
        resizeImage: Boolean,
        keepFirstSentenceOnly: Boolean
    ): DescriptionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            inferLocked(image, prompt, fallback, resizeImage, keepFirstSentenceOnly, allowBackendDowngrade = true)
        }
    }

    /** [mutex] 보유 상태에서만 호출. invoke 실패 시 1회 백엔드 강등 후 재시도. */
    private suspend fun inferLocked(
        image: Bitmap,
        prompt: String,
        fallback: String,
        resizeImage: Boolean,
        keepFirstSentenceOnly: Boolean,
        allowBackendDowngrade: Boolean
    ): DescriptionResult {
        val currentEngine = engine
        if (currentEngine == null) {
            return when (val s = loadState) {
                LoadState.FileMissing ->
                    emptyResult("모델 파일을 찾을 수 없습니다. 앱을 재시작해 다운로드를 확인해 주세요.")
                is LoadState.Failed ->
                    emptyResult("모델 초기화 실패: ${s.reason}")
                LoadState.NotLoaded ->
                    emptyResult("모델이 아직 로드되지 않았습니다.")
                LoadState.Ready ->
                    emptyResult(fallback)  // race: Ready 인데 engine null — 호출 직전 release 와 경합
            }
        }

        try {
            val t0 = System.currentTimeMillis()
            val resized = if (resizeImage) resizeForVlm(image) else image
            val resizedW = resized.width
            val resizedH = resized.height
            val imageBytes = bitmapToJpegBytes(resized)
            if (resized != image) resized.recycle()
            val t1 = System.currentTimeMillis()

            val convConfig = ConversationConfig(samplerConfig = GREEDY_SAMPLER)
            return currentEngine.createConversation(convConfig).use { conversation ->
                val t2 = System.currentTimeMillis()
                val message = Message.user(
                    Contents.of(
                        Content.ImageBytes(imageBytes),
                        Content.Text(prompt)
                    )
                )

                val rawText = streamWithEarlyLoopCancel(conversation, message)

                val t3 = System.currentTimeMillis()
                val cleaned = PromptBuilder.cleanResponse(
                    rawText,
                    keepFirstSentenceOnly = keepFirstSentenceOnly
                )
                Log.i(
                    TAG,
                    "▶ 추론 완료 총=${t3 - t0}ms " +
                        "(preprocess=${t1 - t0}ms, createConv=${t2 - t1}ms, sendMessage=${t3 - t2}ms), " +
                        "원본=${rawText.length}자 → 정제=${cleaned.length}자"
                )
                Log.d(TAG, "원본: '${rawText.replace("\n", " ")}' → 정제: '$cleaned'")
                val text = when {
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
                DescriptionResult(
                    text = text,
                    timings = VlmTimings(
                        preprocessMs = t1 - t0,
                        createConvMs = t2 - t1,
                        sendMessageMs = t3 - t2,
                        totalMs = t3 - t0
                    ),
                    inputWidth = resizedW,
                    inputHeight = resizedH
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "추론 실패 (activeBackend=$activeBackend, allowDowngrade=$allowBackendDowngrade)", e)
            if (allowBackendDowngrade && isInvokeFailure(e)) {
                val info = lastModelInfo
                val nextProfile = nextBackendDowngrade(activeBackend)
                if (info != null && nextProfile != null) {
                    Log.w(TAG, "invoke 실패 → 백엔드 강등 ($activeBackend → $nextProfile) 후 재로드/재시도")
                    val reloaded = reloadWithProfileLocked(info, nextProfile)
                    if (reloaded) {
                        return inferLocked(image, prompt, fallback, resizeImage, keepFirstSentenceOnly, allowBackendDowngrade = false)
                    } else {
                        Log.w(TAG, "강등 재로드 실패 — 에러 메시지 반환")
                    }
                }
            }
            return emptyResult("추론 실패: ${e.message}")
        }
    }

    /** invoke 단계 실패(컴파일 그래프 invoke / 백엔드 호환성)로 보이는 예외만 강등 대상. */
    private fun isInvokeFailure(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase()
        return msg.contains("invoke") || msg.contains("status code: 13") ||
            msg.contains("compiled model") || msg.contains("gpu")
    }

    /** PERFORMANCE → BALANCED → COOL 순서. 이미 COOL 이면 null. */
    private fun nextBackendDowngrade(current: String): BackendProfile? = when (current) {
        BackendProfile.PERFORMANCE.name -> BackendProfile.BALANCED
        BackendProfile.BALANCED.name -> BackendProfile.COOL
        else -> null
    }

    /** [mutex] 보유 상태에서만 호출. 현재 엔진을 release 하고 명시 프로파일로 재로드. */
    private fun reloadWithProfileLocked(modelInfo: ModelInfo, profile: BackendProfile): Boolean {
        val modelPath = pathResolver.findModelPath(modelInfo) ?: run {
            loadState = LoadState.FileMissing
            modelAvailable = false
            return false
        }
        releaseInternalLocked()
        val cacheDirPath = context.cacheDir.absolutePath
        return try {
            val cfg = buildConfig(modelPath, profile, cacheDirPath)
            val e = Engine(cfg).also { it.initialize() }
            engine = e
            currentModelId = modelInfo.id
            modelAvailable = true
            activeBackend = profile.name
            loadState = LoadState.Ready
            Log.i(TAG, "강등 재로드 완료: profile=$profile")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "강등 재로드 실패 (profile=$profile)", ex)
            loadState = LoadState.Failed(ex.message ?: "강등 재로드 실패")
            modelAvailable = false
            false
        }
    }

    override fun isAvailable(): Boolean = modelAvailable ?: true

    override fun release() {
        synchronized(this) {
            try { engine?.close() } catch (e: Exception) { Log.w(TAG, "엔진 해제 중 오류", e) }
            engine = null
            currentModelId = null
            modelAvailable = null
            activeBackend = "unknown"
            loadState = LoadState.NotLoaded
            // lastModelInfo 는 의도적 보존 — 이후 ensureLoaded 로 재로드 가능
        }
    }

    private fun releaseInternalLocked() {
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "엔진 해제 중 오류", e) }
        engine = null
        currentModelId = null
        modelAvailable = null
        activeBackend = "unknown"
        loadState = LoadState.NotLoaded
    }

    /**
     * 스트리밍 추론 + 루프 할루시네이션 조기 cancel.
     *
     * litertlm 0.11.0 의 `sendMessageAsync` 콜백 변형은 partial Message 가 누적인지
     * 단편인지 SDK 명세가 모호하므로, 새 텍스트가 직전 누적의 prefix 면 교체 / 아니면 append.
     *
     * 누적 32자 마다 [PromptBuilder.isLoopyHallucination] 검사 → true 면 즉시 cancelProcess.
     * 정상 응답(~30~80자)은 1~2회 안에 검사를 통과한다. 12초 타임아웃은 isLoopyHallucination
     * 가 못 잡은 패턴 루프의 worst case 차단용 — 정상 추론은 충분히 안에 끝난다.
     */
    private suspend fun streamWithEarlyLoopCancel(
        conversation: Conversation,
        message: Message,
        timeoutMs: Long = 12_000
    ): String {
        val deferred = CompletableDeferred<String>()
        val accumulated = StringBuilder()
        var loopCheckedAt = 0
        var cancelled = false

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (cancelled) return
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isEmpty()) return

                if (text.length >= accumulated.length && text.startsWith(accumulated.toString())) {
                    accumulated.setLength(0)
                    accumulated.append(text)
                } else {
                    accumulated.append(text)
                }

                if (accumulated.length >= loopCheckedAt + 32) {
                    loopCheckedAt = accumulated.length
                    if (PromptBuilder.isLoopyHallucination(accumulated.toString())) {
                        cancelled = true
                        Log.w(TAG, "스트리밍 중 루프 감지 → cancelProcess (누적=${accumulated.length}자)")
                        try { conversation.cancelProcess() } catch (e: Exception) {
                            Log.w(TAG, "cancelProcess 실패", e)
                        }
                        deferred.complete(accumulated.toString())
                    }
                }
            }

            override fun onDone() {
                deferred.complete(accumulated.toString())
            }

            override fun onError(t: Throwable) {
                if (cancelled) {
                    deferred.complete(accumulated.toString())
                } else {
                    deferred.completeExceptionally(t)
                }
            }
        }

        conversation.sendMessageAsync(message, callback, emptyMap<String, Any>())

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                Log.w(TAG, "스트리밍 추론 timeout (${timeoutMs}ms) → cancelProcess")
                try { conversation.cancelProcess() } catch (_: Exception) {}
                accumulated.toString()
            }
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

package com.vrtmv.app.ui.intro

import android.app.DownloadManager
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.detection.DetectionProviderRegistry
import com.vrtmv.app.data.download.DownloadProgress
import com.vrtmv.app.data.download.ManualInstallRequiredException
import com.vrtmv.app.data.download.ModelDownloadManager
import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.domain.model.AssetInfo
import com.vrtmv.app.domain.model.AssetRegistry
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class IntroUiState {
    /** 패치 사항 확인중... */
    data object Checking : IntroUiState()

    /** 최신 상태입니다 */
    data object ModelReady : IntroUiState()

    /** 다운로드 큐의 한 항목을 다운로드하는 중 */
    data class Downloading(
        val currentItemName: String,
        val currentIndex: Int,   // 1-based
        val totalItems: Int,
        val progress: Float,
        val downloadedMB: Int,
        val totalMB: Int
    ) : IntroUiState()

    /** 다운로드 완료 후 엔진/검출기 초기화 단계 */
    data class Initializing(val message: String) : IntroUiState()

    /**
     * 다운로드 에러.
     * @param isCritical true면 VLM 모델 실패처럼 치명적(앱 사용 불가), false면 보조 자산 실패(건너뛰기 가능)
     */
    data class DownloadError(val message: String, val isCritical: Boolean) : IntroUiState()

    /** 메인 화면으로 이동 가능 */
    data object Ready : IntroUiState()
}

/**
 * 다운로드 큐 항목. 모델과 자산을 동일 인터페이스로 처리하기 위한 내부 타입.
 */
private sealed class DownloadItem {
    abstract val displayName: String
    abstract val isCritical: Boolean

    data class Model(val info: ModelInfo) : DownloadItem() {
        override val displayName: String = info.displayName
        // VLM은 앱의 핵심 — 실패 시 치명적
        override val isCritical: Boolean = true
    }

    data class Asset(val info: AssetInfo) : DownloadItem() {
        override val displayName: String = info.displayName
        // 보조 자산 실패 시 해당 기능만 비활성화되므로 건너뛸 수 있음
        override val isCritical: Boolean = false
    }
}

@HiltViewModel
class IntroViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val inferenceEngine: InferenceEngine,
    private val detectionProviderRegistry: DetectionProviderRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "IntroVM"
    }

    private val _uiState = MutableStateFlow<IntroUiState>(IntroUiState.Checking)
    val uiState: StateFlow<IntroUiState> = _uiState.asStateFlow()

    init {
        checkAndPrepareAll()
    }

    private fun checkAndPrepareAll() {
        viewModelScope.launch {
            _uiState.value = IntroUiState.Checking
            val startTime = System.currentTimeMillis()

            // 확인 중 상태를 잠시 보여줌 (브랜딩)
            delay(800)

            // 다운로드 순서: VLM(치명적) → YOLO(보조) → 제스처(보조)
            val queue: List<DownloadItem> = listOf(
                DownloadItem.Model(ModelRegistry.getDefaultModel()),
                DownloadItem.Asset(AssetRegistry.YOLO),
                DownloadItem.Asset(AssetRegistry.GESTURE)
            )

            val total = queue.size
            for ((index, item) in queue.withIndex()) {
                val exists = when (item) {
                    is DownloadItem.Model -> modelDownloadManager.modelExists(item.info)
                    is DownloadItem.Asset -> modelDownloadManager.assetExists(item.info)
                }
                if (exists) {
                    Log.d(TAG, "이미 존재: ${item.displayName}")
                    continue
                }

                val ok = downloadSingleItem(item, currentIndex = index + 1, total = total)
                if (!ok && item.isCritical) {
                    // 치명적 실패 — 에러 상태에 머물고 retry 대기
                    return@launch
                }
                // 비치명적 실패는 다음 항목으로 진행
            }

            // 다운로드 완료 → 엔진/검출기 초기화
            val initOk = runInitialization()
            if (!initOk) {
                // VLM 로드 실패 시 DownloadError 상태 유지 (runInitialization 내부에서 세팅됨)
                return@launch
            }

            // 모두 완료(또는 스킵)
            _uiState.value = IntroUiState.ModelReady
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 1500 - elapsed
            if (remaining > 0) delay(remaining)
            _uiState.value = IntroUiState.Ready
        }
    }

    /**
     * VLM 모델 로드 + 워밍업 + 검출기 선행 초기화.
     * 기존 CameraViewModel.init의 로딩 로직을 Intro로 이관해 Camera 진입 지연 제거.
     *
     * @return VLM 로드 성공 여부. 검출기 실패는 비치명적으로 간주하고 true 유지.
     */
    private suspend fun runInitialization(): Boolean {
        _uiState.value = IntroUiState.Initializing("VLM 모델 로딩 중")

        val modelInfo = ModelRegistry.getDefaultModel()
        val vlmOk = try {
            val success = inferenceEngine.loadModel(modelInfo)
            if (success) {
                // 1x1 워밍업 — GPU 셰이더 컴파일 및 KV 캐시 초기화
                try {
                    val warmup = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    inferenceEngine.describeScene(warmup)
                    warmup.recycle()
                    Log.d(TAG, "VLM 워밍업 완료")
                } catch (e: Exception) {
                    Log.w(TAG, "VLM 워밍업 중 오류 (무시): ${e.message}")
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "VLM 로드 중 예외", e)
            false
        }

        if (!vlmOk) {
            _uiState.value = IntroUiState.DownloadError(
                message = "VLM 모델 로드 실패 — 파일이 손상되었거나 포맷이 지원되지 않습니다",
                isCritical = true
            )
            return false
        }

        // 검출기 선행 초기화 (MediaPipe + YOLO) — 실패는 비치명적
        _uiState.value = IntroUiState.Initializing("검출기 준비 중")
        withContext(Dispatchers.IO) {
            try {
                detectionProviderRegistry.initAll()
            } catch (e: Exception) {
                Log.w(TAG, "검출기 초기화 일부 실패", e)
            }
        }

        return true
    }

    /**
     * 큐의 한 항목을 다운로드한다.
     * @return 성공 시 true, 실패 시 false (UI 상태는 실패 시 [DownloadError]로 세팅)
     */
    private suspend fun downloadSingleItem(
        item: DownloadItem,
        currentIndex: Int,
        total: Int
    ): Boolean {
        return try {
            val downloadId = when (item) {
                is DownloadItem.Model -> modelDownloadManager.startDownload(item.info)
                is DownloadItem.Asset -> modelDownloadManager.startAssetDownload(item.info)
            }

            var succeeded = false
            modelDownloadManager.observeProgress(downloadId).collect { progress ->
                when {
                    progress.isComplete -> {
                        succeeded = true
                        return@collect
                    }
                    progress.isFailed -> {
                        val message = reasonMessage(progress)
                        Log.w(TAG, "${item.displayName} 다운로드 실패: $message")
                        _uiState.value = IntroUiState.DownloadError(
                            message = "${item.displayName}: $message",
                            isCritical = item.isCritical
                        )
                        return@collect
                    }
                    else -> {
                        _uiState.value = IntroUiState.Downloading(
                            currentItemName = item.displayName,
                            currentIndex = currentIndex,
                            totalItems = total,
                            progress = progress.progress,
                            downloadedMB = progress.downloadedMB,
                            totalMB = progress.totalMB
                        )
                    }
                }
            }
            succeeded
        } catch (e: ManualInstallRequiredException) {
            // downloadUrl이 비어있는 모델 — 오직 VLM 모델에서만 발생. Ready로 바로 통과
            Log.w(TAG, "수동 배치 필요: ${item.displayName}")
            true  // 스킵으로 처리 — ModelPathResolver가 수동 배치 경로를 탐색
        } catch (e: Exception) {
            Log.e(TAG, "${item.displayName} 다운로드 중 예외", e)
            _uiState.value = IntroUiState.DownloadError(
                message = "${item.displayName}: ${e.message ?: "알 수 없는 오류"}",
                isCritical = item.isCritical
            )
            false
        }
    }

    private fun reasonMessage(progress: DownloadProgress): String = when (progress.reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "저장 공간이 부족합니다"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "저장소를 찾을 수 없습니다"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "네트워크 오류가 발생했습니다"
        DownloadManager.ERROR_CANNOT_RESUME -> "다운로드를 재개할 수 없습니다"
        else -> "다운로드 실패 (코드: ${progress.reason})"
    }

    fun retry() {
        checkAndPrepareAll()
    }

    /** 건너뛰고 Ready로 진행 — 비치명적 오류 또는 사용자 명시 스킵 */
    fun skip() {
        _uiState.value = IntroUiState.Ready
    }
}

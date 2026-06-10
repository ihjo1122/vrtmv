package com.vrtmv.app.ui.intro

import android.app.DownloadManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class IntroUiState {
    data object Checking : IntroUiState()
    data object ModelReady : IntroUiState()

    data class Downloading(
        val currentItemName: String,
        val currentIndex: Int,   // 1-based
        val totalItems: Int,
        val progress: Float,
        val downloadedMB: Int,
        val totalMB: Int
    ) : IntroUiState()

    data class Initializing(val message: String) : IntroUiState()

    /** isCritical=true 면 VLM 모델 실패처럼 앱 사용 불가, false 면 보조 자산 실패(스킵 가능). */
    data class DownloadError(val message: String, val isCritical: Boolean) : IntroUiState()

    data object Ready : IntroUiState()
}

private sealed class DownloadItem {
    abstract val displayName: String
    abstract val isCritical: Boolean

    data class Model(val info: ModelInfo) : DownloadItem() {
        override val displayName: String = info.displayName
        // VLM 은 앱의 핵심 — 실패 시 치명적
        override val isCritical: Boolean = true
    }

    data class Asset(val info: AssetInfo) : DownloadItem() {
        override val displayName: String = info.displayName
        // 보조 자산 실패 시 해당 기능만 비활성화되므로 스킵 가능
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

            // 브랜딩 표시 시간을 다운로드 존재 확인과 병렬 실행해 체감 지연 흡수
            val brandingDelay = launch { delay(500) }

            // VLM(치명적) → YOLO(보조)
            val queue: List<DownloadItem> = listOf(
                DownloadItem.Model(ModelRegistry.getDefaultModel()),
                DownloadItem.Asset(AssetRegistry.YOLO)
            )

            val existsFlags = coroutineScope {
                queue.map { item ->
                    async(Dispatchers.IO) {
                        when (item) {
                            is DownloadItem.Model -> modelDownloadManager.modelExists(item.info)
                            is DownloadItem.Asset -> modelDownloadManager.assetExists(item.info)
                        }
                    }
                }.awaitAll()
            }

            val total = queue.size
            for ((index, item) in queue.withIndex()) {
                if (existsFlags[index]) {
                    Log.d(TAG, "이미 존재: ${item.displayName}")
                    continue
                }

                val ok = downloadSingleItem(item, currentIndex = index + 1, total = total)
                if (!ok && item.isCritical) return@launch
            }

            brandingDelay.join()

            val initOk = runInitialization()
            if (!initOk) return@launch

            _uiState.value = IntroUiState.ModelReady
            // 빠른 케이스에서도 ModelReady 상태가 잠깐은 보이도록 800ms 최소 노출
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 800 - elapsed
            if (remaining > 0) delay(remaining)
            _uiState.value = IntroUiState.Ready
        }
    }

    /** VLM(1~3초) 과 검출기(0.5~1.3초) 가 독립이라 병렬 실행해 누적 시간 단축. */
    private suspend fun runInitialization(): Boolean {
        _uiState.value = IntroUiState.Initializing("모델·검출기 준비 중")

        val modelInfo = ModelRegistry.getDefaultModel()

        val (vlmOk, _) = coroutineScope {
            val vlmDeferred = async(Dispatchers.IO) {
                try {
                    inferenceEngine.loadModel(modelInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "VLM 로드 중 예외", e)
                    false
                }
            }
            val detectorsDeferred = async(Dispatchers.IO) {
                try {
                    detectionProviderRegistry.initAll()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "검출기 초기화 일부 실패", e)
                    false
                }
            }
            vlmDeferred.await() to detectorsDeferred.await()
        }

        if (!vlmOk) {
            _uiState.value = IntroUiState.DownloadError(
                message = "VLM 모델 로드 실패 — 파일이 손상되었거나 포맷이 지원되지 않습니다",
                isCritical = true
            )
            return false
        }

        return true
    }

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
            // VLM 의 downloadUrl 이 비어있는 경우 — ModelPathResolver 가 수동 배치 경로 탐색
            Log.w(TAG, "수동 배치 필요: ${item.displayName}")
            true
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

    fun skip() {
        _uiState.value = IntroUiState.Ready
    }
}

package com.vrtmv.app.ui.intro

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.download.ManualInstallRequiredException
import com.vrtmv.app.data.download.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class IntroUiState {
    /** 패치 사항 확인중... */
    data object Checking : IntroUiState()

    /** 최신 상태입니다 */
    data object ModelReady : IntroUiState()

    /** 모델 다운로드 중 */
    data class Downloading(
        val progress: Float,
        val downloadedMB: Int,
        val totalMB: Int
    ) : IntroUiState()

    /** 다운로드 에러 */
    data class DownloadError(val message: String) : IntroUiState()

    /** 메인 화면으로 이동 가능 */
    data object Ready : IntroUiState()
}

@HiltViewModel
class IntroViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<IntroUiState>(IntroUiState.Checking)
    val uiState: StateFlow<IntroUiState> = _uiState.asStateFlow()

    init {
        checkAndPrepareModel()
    }

    private fun checkAndPrepareModel() {
        viewModelScope.launch {
            _uiState.value = IntroUiState.Checking
            val startTime = System.currentTimeMillis()

            // 확인 중 상태를 잠시 보여줌
            delay(800)

            val exists = modelDownloadManager.modelExists()

            if (exists) {
                _uiState.value = IntroUiState.ModelReady

                // 최소 1.5초 대기 (브랜딩 표시)
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = 1500 - elapsed
                if (remaining > 0) delay(remaining)

                _uiState.value = IntroUiState.Ready
            } else {
                startModelDownload(startTime)
            }
        }
    }

    private fun startModelDownload(startTime: Long) {
        viewModelScope.launch {
            try {
                val downloadId = modelDownloadManager.startDownload()

                modelDownloadManager.observeProgress(downloadId).collect { progress ->
                    when {
                        progress.isComplete -> {
                            _uiState.value = IntroUiState.ModelReady

                            // 최소 1.5초 대기 (브랜딩 표시)
                            val elapsed = System.currentTimeMillis() - startTime
                            val remaining = 1500 - elapsed
                            if (remaining > 0) delay(remaining)

                            _uiState.value = IntroUiState.Ready
                        }
                        progress.isFailed -> {
                            val reason = when (progress.reason) {
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "저장 공간이 부족합니다"
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "저장소를 찾을 수 없습니다"
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "네트워크 오류가 발생했습니다"
                                DownloadManager.ERROR_CANNOT_RESUME -> "다운로드를 재개할 수 없습니다"
                                else -> "다운로드에 실패했습니다 (코드: ${progress.reason})"
                            }
                            _uiState.value = IntroUiState.DownloadError(reason)
                        }
                        else -> {
                            _uiState.value = IntroUiState.Downloading(
                                progress = progress.progress,
                                downloadedMB = progress.downloadedMB,
                                totalMB = progress.totalMB
                            )
                        }
                    }
                }
            } catch (e: ManualInstallRequiredException) {
                // 수동 배치 모델: 다운로드 불가, 바로 메인 진입 허용
                _uiState.value = IntroUiState.Ready
            } catch (e: Exception) {
                _uiState.value = IntroUiState.DownloadError(
                    e.message ?: "알 수 없는 오류가 발생했습니다"
                )
            }
        }
    }

    fun retry() {
        checkAndPrepareModel()
    }

    fun skip() {
        _uiState.value = IntroUiState.Ready
    }
}

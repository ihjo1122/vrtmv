package com.vrtmv.app.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.download.DownloadProgress
import com.vrtmv.app.data.download.InsufficientStorageException
import com.vrtmv.app.data.download.ManualInstallRequiredException
import com.vrtmv.app.data.download.ModelDownloadManager
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MainDownloadState {
    data object Idle : MainDownloadState()
    data class Downloading(val modelInfo: ModelInfo, val progress: DownloadProgress?) : MainDownloadState()
    data class Error(val message: String) : MainDownloadState()
    data class Ready(val modelId: String, val captureMode: CaptureMode) : MainDownloadState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager
) : ViewModel() {

    companion object {
        private const val TAG = "MainVM"
    }

    private val _downloadState = MutableStateFlow<MainDownloadState>(MainDownloadState.Idle)
    val downloadState: StateFlow<MainDownloadState> = _downloadState.asStateFlow()

    fun getDefaultModel(): ModelInfo = ModelRegistry.getDefaultModel()

    fun onModeSelected(captureMode: CaptureMode) {
        val modelInfo = ModelRegistry.getDefaultModel()
        viewModelScope.launch {
            val exists = downloadManager.modelExists(modelInfo)
            if (exists) {
                Log.d(TAG, "모델 이미 존재: ${modelInfo.displayName} mode=${captureMode.id}")
                _downloadState.value = MainDownloadState.Ready(modelInfo.id, captureMode)
            } else {
                startModelDownload(modelInfo, captureMode)
            }
        }
    }

    private fun startModelDownload(modelInfo: ModelInfo, captureMode: CaptureMode) {
        viewModelScope.launch {
            try {
                val downloadId = downloadManager.startDownload(modelInfo)
                _downloadState.value = MainDownloadState.Downloading(modelInfo, null)

                downloadManager.observeProgress(downloadId).collect { progress ->
                    when {
                        progress.isComplete -> {
                            Log.d(TAG, "다운로드 완료: ${modelInfo.displayName}")
                            _downloadState.value = MainDownloadState.Ready(modelInfo.id, captureMode)
                        }
                        progress.isFailed -> {
                            _downloadState.value = MainDownloadState.Error(
                                "다운로드 실패 (코드: ${progress.reason})"
                            )
                        }
                        else -> {
                            _downloadState.value = MainDownloadState.Downloading(modelInfo, progress)
                        }
                    }
                }
            } catch (e: ManualInstallRequiredException) {
                _downloadState.value = MainDownloadState.Error(
                    "수동 설치가 필요한 모델입니다.\nadb push ${e.modelInfo.fileName} /sdcard/Download/vrtmv/"
                )
            } catch (e: InsufficientStorageException) {
                _downloadState.value = MainDownloadState.Error(
                    "저장공간 부족: ${e.required}MB 필요, ${e.available}MB 사용 가능"
                )
            } catch (e: Exception) {
                _downloadState.value = MainDownloadState.Error(
                    "다운로드 시작 실패: ${e.message}"
                )
            }
        }
    }

    fun resetState() {
        _downloadState.value = MainDownloadState.Idle
    }
}

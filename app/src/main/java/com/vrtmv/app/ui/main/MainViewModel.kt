package com.vrtmv.app.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.download.DownloadProgress
import com.vrtmv.app.data.download.InsufficientStorageException
import com.vrtmv.app.data.download.ManualInstallRequiredException
import com.vrtmv.app.data.download.ModelDownloadManager
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.domain.model.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 메인 화면 다운로드 상태 */
sealed class MainDownloadState {
    data object Idle : MainDownloadState()
    data class Downloading(val modelInfo: ModelInfo, val progress: DownloadProgress?) : MainDownloadState()
    data class Error(val message: String) : MainDownloadState()
    data class Ready(
        val modelId: String,
        val detectorKind: DetectorKind,
        val useArCore: Boolean,
        val fullFrameVlm: Boolean
    ) : MainDownloadState()
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

    /** ARCore 백엔드 사용 여부 토글. 기본 ON — 월드 앵커 기반 AR 오버레이 활성. 미지원 기기는 런타임에 CameraX 자동 폴백. */
    private val _useArCore = MutableStateFlow(true)
    val useArCore: StateFlow<Boolean> = _useArCore.asStateFlow()

    fun toggleArCore() {
        _useArCore.value = !_useArCore.value
    }

    /**
     * VLM 추론 시 전체 프레임 vs 객체 크롭 사용 토글. 기본 ON (전체 프레임).
     * 전체 프레임이 VLM 에 더 풍부한 맥락을 주어 "상황 설명" 품질이 안정적.
     * 객체 크롭이 필요한 경우(예: 세밀한 객체 분석) OFF 로 전환.
     */
    private val _fullFrameVlm = MutableStateFlow(true)
    val fullFrameVlm: StateFlow<Boolean> = _fullFrameVlm.asStateFlow()

    fun toggleFullFrameVlm() {
        _fullFrameVlm.value = !_fullFrameVlm.value
    }

    /** 기본(단일) 모델 반환 */
    fun getDefaultModel(): ModelInfo = ModelRegistry.getDefaultModel()

    /**
     * 검출기 버튼 클릭 시 호출.
     * 모델이 있으면 바로 Ready, 없으면 다운로드 시작.
     */
    fun onDetectorSelected(detectorKind: DetectorKind) {
        val modelInfo = ModelRegistry.getDefaultModel()
        val ar = _useArCore.value
        val full = _fullFrameVlm.value
        viewModelScope.launch {
            val exists = downloadManager.modelExists(modelInfo)
            if (exists) {
                Log.d(TAG, "모델 이미 존재: ${modelInfo.displayName} + ${detectorKind.displayName} arcore=$ar fullFrame=$full")
                _downloadState.value = MainDownloadState.Ready(modelInfo.id, detectorKind, ar, full)
            } else {
                startModelDownload(modelInfo, detectorKind, ar, full)
            }
        }
    }

    private fun startModelDownload(modelInfo: ModelInfo, detectorKind: DetectorKind, useArCore: Boolean, fullFrame: Boolean) {
        viewModelScope.launch {
            try {
                val downloadId = downloadManager.startDownload(modelInfo)
                _downloadState.value = MainDownloadState.Downloading(modelInfo, null)

                downloadManager.observeProgress(downloadId).collect { progress ->
                    when {
                        progress.isComplete -> {
                            Log.d(TAG, "다운로드 완료: ${modelInfo.displayName}")
                            _downloadState.value = MainDownloadState.Ready(modelInfo.id, detectorKind, useArCore, fullFrame)
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

    /** 다운로드 상태 초기화 */
    fun resetState() {
        _downloadState.value = MainDownloadState.Idle
    }
}

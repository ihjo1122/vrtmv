# VRTMV - Gaze-Guided On-Device VLM for Real-Time Mobile Visual Understanding

## 프로젝트 개요
터치 기반 관심 영역 선택 + VLM을 결합한 모바일 시각 이해 시스템.
Android 앱에서 카메라 프리뷰 중 **사용자가 터치한 좌표의 객체를 탐지**하고, 해당 객체를 크롭하여 AI 설명을 생성, AR 오버레이로 표시한다.

## 핵심 동작 방식
- **상시 검출 아님** — 카메라는 프리뷰만 실행, 프레임을 버퍼에 저장
- **터치 시에만 검출** — 터치 → 현재 프레임 캡처 → MediaPipe 검출 → 터치 좌표의 객체 선택
- **VLM 추론** — 선택된 객체의 바운딩박스를 크롭(RoiCropper) → VLM에 이미지+프롬프트 전달 → 설명 생성
- **VLM 모드 전환** — 우상단 토글 버튼으로 Off / Mock / Cloud 전환 가능

## 기술 스택
- **언어**: Kotlin
- **UI**: Jetpack Compose + Material3
- **아키텍처**: MVVM + Hilt DI (단일 모듈)
- **카메라**: CameraX 1.4.1 (Preview + ImageAnalysis 프레임 버퍼링)
- **객체 검출**: MediaPipe Vision 0.10.20 (EfficientDet-Lite2, COCO 80 카테고리, threshold 0.3)
- **VLM**: MediaPipe LLM Inference (온디바이스, tasks-genai 0.10.24) — Gemma 2B 등 지원
- **빌드**: AGP 8.7.3, Kotlin 2.1.0, minSDK 31, targetSDK 35

## 테스트 기기
- Galaxy S23 Ultra

## 개발 단계 (4단계)

### 1단계: 카메라 + AR 오버레이 ✅ 완료
- CameraX 프리뷰, 런타임 권한 처리
- AR 스타일 오버레이 레이어 (코너 브래킷, 플로팅 태그, 스캐닝 라인)
- 크로스헤어, 하단 힌트 카드

### 2단계: MediaPipe Object Detector ✅ 완료
- **EfficientDet-Lite2** 모델 번들링 (assets/, 7.2MB)
- ObjectDetectionManager — **온디맨드 방식** (프레임 버퍼링 + 회전 적용 + 터치 시 검출)
- CoordinateMapper — 스케일+오프셋만 담당 (회전은 Bitmap에서 처리)

### 3단계: 터치 기반 ROI 선택 ✅ 완료
- GazeTargetResolver (포함 검사 → 최소 면적 → 최근접 중심)
- 터치 → 프레임 캡처 → 검출 → 터치 좌표 객체 매칭 → AR 태그 표시

### 4단계: VLM 추론 연결 ✅ 완료
- RoiCropper (바운딩박스 크롭 + 15% 패딩)
- GeminiNanoEngine (MediaPipe LLM Inference, 온디바이스 전용)
- PromptBuilder (한국어 설명 요청 프롬프트)
- VLM 모드 토글: Off / On (우상단 버튼)
- viewModelScope.launch로 비동기 추론, 15초 타임아웃
- Loading/Error 상태 UI (ResultCard, AR 태그)

## 패키지 구조
```
com.vrtmv.app/
├── di/InferenceModule.kt              # Hilt DI (@Named mock/cloud)
├── data/
│   ├── detection/ObjectDetectionManager.kt  # MediaPipe 래퍼 (온디맨드)
│   └── inference/
│       ├── InferenceEngine.kt         # 인터페이스
│       ├── MockInferenceEngine.kt     # 더미 (라벨만 반환)
│       ├── GeminiNanoEngine.kt         # 온디바이스 LLM (MediaPipe tasks-genai)
│       ├── PromptBuilder.kt           # 프롬프트 템플릿
│       └── VlmMode.kt                # OFF / ON enum
├── domain/model/
│   ├── DetectedObject.kt             # boundingBox, label, confidence
│   └── InferenceState.kt             # Idle, Loading, Success, Error, ModelUnavailable
├── ui/
│   ├── camera/
│   │   ├── CameraScreen.kt           # 메인 화면 (5개 레이어) + VlmToggleButton
│   │   └── CameraViewModel.kt        # 터치→검출→선택→VLM추론 총괄
│   ├── overlay/
│   │   ├── DetectionOverlay.kt        # AR 바운딩박스 + 플로팅 태그
│   │   └── GazeCrosshair.kt          # 터치 포인트 표시
│   ├── components/ResultCard.kt       # 하단 힌트/로딩/에러 카드
│   └── theme/Theme.kt                # Material3 다이나믹 컬러
├── util/
│   ├── CoordinateMapper.kt           # 이미지↔화면 좌표 변환
│   ├── GazeTargetResolver.kt         # 터치→객체 매칭 알고리즘
│   └── RoiCropper.kt                 # 바운딩박스 크롭 (15% 패딩)
├── MainActivity.kt
└── VrtmvApplication.kt
```

## 주요 데이터 흐름
```
CameraX ImageAnalysis (매 프레임)
  → ObjectDetectionManager.updateFrame(imageProxy)  // 버퍼링만

사용자 터치
  → CameraViewModel.onTapDetect(tapPoint, manager, viewW, viewH)
  → ObjectDetectionManager.detectNow()  // 현재 프레임에서 검출
  → GazeTargetResolver.resolve(tapPoint, objects, mapper)  // 터치 좌표 객체 매칭
  → selectedObject + capturedBitmap  // AR 태그 표시

VLM 추론 (모드가 OFF가 아닐 때)
  → RoiCropper.crop(bitmap, boundingBox)  // 누끼 크롭
  → InferenceEngine.describe(cropped, label, confidence)  // Mock 또는 Cloud
  → AR 태그 description 업데이트
```

## 인터랙션
- **터치**: 객체 검출 → 선택 → AR 태그 + (VLM ON이면) 온디바이스 추론
- **롱프레스**: 선택 해제
- **우상단 버튼**: VLM On/Off 토글
  - 검정 반투명: Off
  - 청록색: On

## AR 오버레이 디자인
- **선택 객체**: 시안색 코너 브래킷(펄스) + 점선 테두리 + 스캐닝 라인 + 플로팅 태그
- **비선택 객체**: 반투명 흰색 코너 브래킷 + 작은 라벨
- **크로스헤어**: 노란색 원+십자선 (터치 위치에만 표시)
- **하단 카드**: Idle→힌트, Loading→진행바, Error→에러 메시지

## 온디바이스 LLM 모델 설정
모델 형식: **`.task`** 파일 (MediaPipe LLM Inference 전용)

### 다운로드
HuggingFace에서 `gemma3-1b-it-int4.task` 다운로드:
https://huggingface.co/litert-community/Gemma3-1B-IT

### 기기 배치 (자동)
1. `.task` 파일을 기기의 **Download 폴더**에 넣기 (USB 연결 또는 브라우저 다운로드)
2. 앱이 자동으로 Download 폴더에서 `.task` 파일을 탐색
3. 발견 시 앱 내부 저장소로 복사 후 사용

모델 파일이 없으면 VLM ON 시 안내 메시지 표시.

## 빌드 & 실행
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

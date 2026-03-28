# VRTMV - Gaze-Guided On-Device VLM for Real-Time Mobile Visual Understanding

## 응답 언어
- **모든 응답은 한국어로 작성할 것.** gstack 스킬 실행 시에도 한국어로 출력한다.
- 코드, 명령어, 파일명 등 기술적 용어는 원문 그대로 유지하되, 설명과 질문은 한국어로 한다.

## 프로젝트 개요
터치 기반 관심 영역 선택 + VLM을 결합한 모바일 시각 이해 시스템.
Android 앱에서 카메라 프리뷰 중 **사용자가 터치한 좌표의 객체를 탐지**하고, 해당 객체를 크롭하여 AI 설명을 생성, AR 오버레이로 표시한다. 객체 미검출 시 전체 프레임으로 장면 추론.

## 핵심 동작 방식
- **상시 검출 아님** — 카메라는 프리뷰만 실행, 프레임을 버퍼에 저장
- **터치 시에만 검출** — 터치 → 현재 프레임 캡처 → MediaPipe 검출 → 터치 좌표의 객체 선택
- **VLM 추론** — 객체 검출 시 크롭 이미지, 미검출 시 전체 프레임을 VLM에 전달 → 설명 생성
- **추론 중 터치 차단** — Loading 상태에서 추가 터치 무시, 롱프레스로 중지 후 재질의 가능

## 기술 스택
- **언어**: Kotlin
- **UI**: Jetpack Compose + Material3
- **아키텍처**: MVVM + Hilt DI (단일 모듈)
- **카메라**: CameraX 1.4.1 (Preview + ImageAnalysis 프레임 버퍼링)
- **객체 검출**: MediaPipe Vision 0.10.20 (EfficientDet-Lite2, COCO 80 카테고리)
- **VLM**: LiteRT-LM 0.8.0 (온디바이스, litertlm-android) — Gemma 3n E2B-IT 멀티모달
- **빌드**: AGP 8.7.3, Kotlin 2.1.0, minSDK 31, targetSDK 35

## 테스트 기기
- Galaxy S23 Ultra (SDK 36)

## 패키지 구조
```
com.vrtmv.app/
├── di/InferenceModule.kt              # Hilt DI (LiteRtLmEngine 단일 엔진)
├── data/
│   ├── detection/ObjectDetectionManager.kt  # MediaPipe 래퍼 (온디맨드)
│   ├── download/ModelDownloadManager.kt     # 모델 다운로드 (DownloadManager)
│   └── inference/
│       ├── InferenceEngine.kt         # 인터페이스 (describe/describeScene/loadModel/release)
│       ├── LiteRtLmEngine.kt         # 온디바이스 VLM (LiteRT-LM, .litertlm, 멀티모달)
│       ├── PromptBuilder.kt           # 비전/장면 프롬프트 템플릿
│       └── VlmMode.kt                # OFF / ON enum
├── domain/model/
│   ├── DetectedObject.kt             # boundingBox, label, confidence
│   ├── InferenceState.kt             # Idle, Loading, Success, Error
│   ├── ModelInfo.kt                  # 모델 정보 (id, url, quantization)
│   └── ModelRegistry.kt              # 모델 목록 중앙 관리
├── navigation/AppNavHost.kt           # Intro → Main → Camera/{modelId}
├── ui/
│   ├── intro/IntroScreen.kt, IntroViewModel.kt
│   ├── main/MainScreen.kt, MainViewModel.kt
│   ├── camera/CameraScreen.kt, CameraViewModel.kt
│   ├── overlay/DetectionOverlay.kt, GazeCrosshair.kt
│   ├── components/AppHeader.kt, ResultCard.kt, DownloadProgressUI.kt
│   └── theme/Theme.kt
├── util/
│   ├── CoordinateMapper.kt           # 이미지↔화면 좌표 변환
│   ├── GazeTargetResolver.kt         # 터치→객체 매칭 알고리즘
│   ├── ModelPathResolver.kt          # 모델 파일 경로 탐색 (내부/Download 통합)
│   └── RoiCropper.kt                 # 바운딩박스 크롭 (15% 패딩)
├── MainActivity.kt
└── VrtmvApplication.kt
```

## 빌드 & 실행
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 상세 문서
- [개발 단계](docs/development-stages.md) — 1~6단계 진행 현황
- [아키텍처 상세](docs/architecture.md) — 앱 흐름, 데이터 흐름, 인터랙션, AR 디자인
- [모델 설정](docs/model-setup.md) — 모델 배치, 탐색 우선순위, 엔진 수명주기

## gstack (글로벌 스킬)
- **설치 경로**: `~/.claude/skills/gstack`
- **다른 PC 설치**: `git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack`
- **웹 브라우징**: `/browse` 스킬 사용, `mcp__claude-in-chrome__*` 도구 사용 금지
- **스킬 미작동 시**: `cd ~/.claude/skills/gstack && ./setup` 실행

## 문서 관리 규칙
- **코드 변경 시 이 문서를 반드시 동기화할 것**: 새 파일/패키지 추가, 기술 스택 변경, 데이터 흐름 변경, 개발 단계 진행 등 구조적 변경이 발생하면 해당 섹션을 즉시 업데이트한다.
- **문서가 200줄을 초과하면 분할**: 핵심 정보는 `CLAUDE.md`에 유지하고, 상세 내용은 `docs/` 폴더 하위 문서로 분리한 뒤 링크로 연결한다.

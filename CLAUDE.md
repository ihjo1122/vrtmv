# VRTMV - Gaze-Guided On-Device VLM for Real-Time Mobile Visual Understanding

## 응답 언어
- **모든 응답은 한국어로 작성할 것.** gstack 스킬 실행 시에도 한국어로 출력한다.
- 코드, 명령어, 파일명 등 기술적 용어는 원문 그대로 유지하되, 설명과 질문은 한국어로 한다.

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
- **네비게이션**: Jetpack Navigation Compose 2.8.5
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

### 5단계: 인트로 + 메인 화면 ✅ 완료
- **인트로 화면**: 앱 시작 시 "패치 사항 확인중..." 표시
  - 모델 존재 시: "최신 상태입니다" → 4초 후 메인 화면 이동
  - 모델 미존재 시: HuggingFace에서 자동 다운로드 (게이지바 + 퍼센트 표시)
  - 다운로드 경로: `Download/vrtmv/gemma3-1b-it-int4.task`
- **메인 화면**: AR Camera 버튼 → 기존 CameraScreen 실행
- **네비게이션**: Jetpack Navigation Compose (Intro → Main → Camera)
- **ModelDownloadManager**: Android DownloadManager 기반, 중복 다운로드 방지, 진행률 Flow

### 6단계: 모델 비교 + 폴리싱 🔧 진행중
- **멀티 모델 지원**: ModelInfo + ModelRegistry로 모델 목록 중앙 관리
- **모델별 AR Camera 버튼**: MainScreen에서 모델별 버튼 (INT4/INT8)
- **모델 자동 다운로드**: 미다운로드 모델 클릭 시 MainScreen에서 다이얼로그로 다운로드
- **GeminiNanoEngine 파라미터화**: loadModel(modelInfo) + 모델별 내부 경로 분리 + mutex
- **좌표 디버그 로깅**: CameraViewModel에서 터치→매핑→검출 좌표 전체 로그 출력
- **CoordinateMapper 통합**: ViewModel에서만 생성, CameraScreen 이중 생성 제거
- **추론 시간 표시**: CameraScreen 좌상단에 모델명 + 추론 시간(ms)
- **모델 초기화 로딩**: CameraScreen 진입 시 로딩 스피너 (5-15초)
- **저장공간 체크**: 다운로드 전 StatFs로 가용 공간 확인
- **DownloadProgressUI**: IntroScreen/MainScreen 공통 다운로드 진행률 컴포넌트
- **Navigation**: camera/{modelId} 라우트 + SavedStateHandle로 ViewModel에 전달

## 패키지 구조
```
com.vrtmv.app/
├── di/InferenceModule.kt              # Hilt DI (@Named mock/cloud)
├── data/
│   ├── detection/ObjectDetectionManager.kt  # MediaPipe 래퍼 (온디맨드)
│   ├── download/ModelDownloadManager.kt     # 모델 자동 다운로드 (DownloadManager)
│   └── inference/
│       ├── InferenceEngine.kt         # 인터페이스
│       ├── MockInferenceEngine.kt     # 더미 (라벨만 반환)
│       ├── GeminiNanoEngine.kt         # 온디바이스 LLM (MediaPipe tasks-genai)
│       ├── PromptBuilder.kt           # 프롬프트 템플릿
│       └── VlmMode.kt                # OFF / ON enum
├── domain/model/
│   ├── DetectedObject.kt             # boundingBox, label, confidence
│   ├── InferenceState.kt             # Idle, Loading, Success, Error, ModelUnavailable
│   ├── ModelInfo.kt                  # 모델 정보 데이터 클래스 (id, url, quantization 등)
│   └── ModelRegistry.kt              # 사용 가능한 모델 목록 중앙 관리
├── navigation/AppNavHost.kt           # Intro → Main → Camera/{modelId} 네비게이션
├── ui/
│   ├── intro/
│   │   ├── IntroScreen.kt            # 인트로 화면 (패치 확인 + 다운로드)
│   │   └── IntroViewModel.kt         # 모델 존재 확인 + 다운로드 관리
│   ├── main/
│   │   ├── MainScreen.kt             # 메인 화면 (모델별 AR Camera 버튼)
│   │   └── MainViewModel.kt          # 모델 다운로드 상태 관리
│   ├── camera/
│   │   ├── CameraScreen.kt           # AR 카메라 화면 (7개 레이어) + 모델명/추론시간 표시
│   │   └── CameraViewModel.kt        # 터치→검출→선택→VLM추론 + 모델 로드
│   ├── overlay/
│   │   ├── DetectionOverlay.kt        # AR 바운딩박스 + 플로팅 태그
│   │   └── GazeCrosshair.kt          # 터치 포인트 표시
│   ├── components/
│   │   ├── ResultCard.kt             # 하단 힌트/로딩/에러 카드
│   │   └── DownloadProgressUI.kt     # 공통 다운로드 진행률 컴포넌트
│   └── theme/Theme.kt                # Material3 다이나믹 컬러
├── util/
│   ├── CoordinateMapper.kt           # 이미지↔화면 좌표 변환
│   ├── GazeTargetResolver.kt         # 터치→객체 매칭 알고리즘
│   └── RoiCropper.kt                 # 바운딩박스 크롭 (15% 패딩)
├── MainActivity.kt
└── VrtmvApplication.kt
```

## 앱 흐름
```
앱 시작 → IntroScreen
  → "패치 사항 확인중..." (ModelDownloadManager.modelExists())
  → 모델 있음: "최신 상태입니다" → 4초 대기 → MainScreen
  → 모델 없음: DownloadManager로 다운로드 (게이지바) → 완료 → MainScreen

MainScreen
  → AR Camera 버튼 클릭 → CameraScreen (기존 기능)
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

### 모델 배치 (자동 다운로드)
1. 앱 첫 실행 시 인트로 화면에서 **자동 다운로드** (`Download/vrtmv/gemma3-1b-it-int4.task`)
2. 이미 해당 경로 또는 내부 저장소에 모델이 있으면 다운로드 스킵
3. VLM 사용 시 앱 내부 저장소(`files/model.task`)로 자동 복사

### 모델 탐색 우선순위 (GeminiNanoEngine)
1. 앱 내부 저장소 `files/model.task`
2. `Download/vrtmv/gemma3-1b-it-int4.task` (자동 다운로드 경로)
3. Download 루트 폴더에서 `.task` 파일 스캔 (폴백)

모델 파일이 없으면 VLM ON 시 안내 메시지 표시.

## 빌드 & 실행
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## gstack (글로벌 스킬)
- **설치 경로**: `~/.claude/skills/gstack` (모든 OS에서 `$HOME/.claude/skills/gstack`)
- **다른 PC 설치**: `git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack`
- **웹 브라우징**: `/browse` 스킬 사용, `mcp__claude-in-chrome__*` 도구 사용 금지
- **스킬 미작동 시**: `cd ~/.claude/skills/gstack && ./setup` 실행
- **사용 가능한 스킬**:
  - 기획: `/office-hours`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`
  - 디자인: `/design-consultation`, `/design-review`
  - 개발: `/review`, `/ship`, `/land-and-deploy`, `/canary`
  - 품질: `/qa`, `/qa-only`, `/benchmark`, `/cso` (보안 감사)
  - 운영: `/setup-deploy`, `/retro`, `/investigate`, `/document-release`
  - 브라우저: `/browse`, `/setup-browser-cookies`
  - 안전: `/careful`, `/freeze`, `/guard`, `/unfreeze`
  - 기타: `/codex`, `/autoplan`, `/gstack-upgrade`

## 문서 관리 규칙
- **코드 변경 시 이 문서를 반드시 동기화할 것**: 새 파일/패키지 추가, 기술 스택 변경, 데이터 흐름 변경, 개발 단계 진행 등 구조적 변경이 발생하면 해당 섹션을 즉시 업데이트한다.
- **문서가 200줄을 초과하면 분할**: 핵심 정보는 `CLAUDE.md`에 유지하고, 상세 내용은 `docs/` 폴더 하위 문서로 분리한 뒤 링크로 연결한다.

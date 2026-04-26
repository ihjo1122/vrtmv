# VRTMV - Gaze-Guided On-Device VLM for Real-Time Mobile Visual Understanding

## 응답 언어
- **모든 응답은 한국어로 작성할 것.** gstack 스킬 실행 시에도 한국어로 출력한다.
- 코드, 명령어, 파일명 등 기술적 용어는 원문 그대로 유지하되, 설명과 질문은 한국어로 한다.

## 프로젝트 개요
터치 기반 관심 영역 선택 + VLM을 결합한 모바일 시각 이해 시스템.
Android 앱에서 카메라 프리뷰 중 **사용자가 터치한 좌표의 객체를 탐지**하고, 해당 객체를 크롭하여 AI 설명을 생성, AR 오버레이로 표시한다. 객체 미검출 시 전체 프레임으로 장면 추론.

## 핵심 동작 방식
- **상시 검출 아님** — 카메라는 프리뷰만 실행, 프레임을 버퍼에 저장
- **탭/제스처 시 객체 검출 수행 → 탭 좌표의 객체 선택 → VLM에 크롭 전달**
  - 터치: 탭 → `DetectionProvider.detectNow()` → `GazeTargetResolver.resolve(tapPoint, objects)` → 선택 객체 `RoiCropper.crop()` → `describe(crop, label, confidence)` (라벨 힌트 포함). 선택 객체 없으면 전체 프레임 `describeScene(frame)` fallback
  - 제스처: 검지 포인팅 0.5초 홀드 → `detectNow()` → "person" 필터링(손 오탐 차단) → 포인팅 좌표로 객체 선택 → 동일 경로. 선택 없으면 포인팅 중심 박스(min변 35%) 크롭 `describeScene(crop)` fallback
  - **OFF 모드에서도 탭/제스처 시 검출을 수행**해 객체 박스·라벨을 오버레이로 표시 (VLM 미실행)
- **초기화는 Intro 단계에서 일괄 수행** — 다운로드 완료 후 `inferenceEngine.loadModel()` + 1x1 워밍업 + `DetectionProviderRegistry.initAll()`(MediaPipe + YOLO 동시) 실행. Main/Camera 진입 시 추가 로딩 없음.
- **검출기 듀얼 버전** — 메인 화면에서 MediaPipe / YOLO 중 선택. 두 구현 모두 Intro에서 선행 초기화되어 `DetectionProviderRegistry` Singleton에 상주, Camera 전환 시 즉시 재사용
- **카메라 백엔드 듀얼** — 메인 화면 토글로 ARCore / CameraX 선택. 검출기·VLM 등 하류 파이프라인은 동일 코드 재사용 — `FrameSource` 추상화가 백엔드 차이를 흡수
  - **CameraX (기본/폴백)**: `PreviewView` + `ImageAnalysis` → upright Bitmap 송출
  - **ARCore (옵션, 학술 과제)**: `Session` + `GLSurfaceView`(`BackgroundRenderer`) + YUV→Bitmap 변환. 탭/제스처 시 `Frame.hitTest()` → `Anchor` 생성, 매 프레임 `AnchorProjector` 로 화면 좌표 재투영하여 결과 태그가 실세계에 고정된 듯이 추종. tracking 미초기화/hitTest 실패 시 카메라 전방 1.5m fallback anchor. ARCore 미지원/Session 실패 시 자동으로 CameraX 폴백.
- **추론 중 입력 차단** — Loading 상태에서 추가 터치·제스처 무시, 롱프레스로 중지 후 재질의 가능

## 기술 스택
- **언어**: Kotlin 2.1.0 (+ `-Xskip-metadata-version-check` — litertlm 0.10.0이 Kotlin 2.3 메타데이터 사용)
- **UI**: Jetpack Compose + Material3
- **아키텍처**: MVVM + Hilt DI (단일 모듈)
- **카메라**:
  - CameraX 1.4.1 (Preview + ImageAnalysis 프레임 버퍼링) — 폴백 백엔드
  - ARCore 1.48.0 (Session + GLSurfaceView, optional 메타데이터로 미지원 기기 호환) — 월드 앵커 백엔드
  - `FrameSource` 인터페이스로 두 백엔드를 추상화, 검출기/제스처 모듈은 항상 upright Bitmap 만 수신 (ImageProxy/ARCore Image 양쪽 노출 안 함)
- **객체 검출**:
  - MediaPipe Vision 0.10.20 (EfficientDet-Lite2, COCO 80) — 기본 옵션
  - YOLOv11n TFLite (tensorflow-lite 2.16.1, CPU 4스레드) — 고정밀 옵션 (GPU Delegate는 Ultralytics 이슈로 제거)
- **손 제스처**: MediaPipe GestureRecognizer (`gesture_recognizer.task`) — 검지 포인팅 홀드 0.5초(시간 기반). 거리 기반 포인팅 판별(방향 무관), 롤링 평균 앵커, 미검출 시 UI 포인트 즉시 숨김
- **VLM**: LiteRT-LM 0.10.0 (온디바이스, litertlm-android) — **Gemma 3n E2B-IT** 멀티모달 (INT4, ~3.5GB). Gemma 4는 0.10.0이 포맷 미지원 + S23 Ultra에 AICore 없음 → 0.10.1 Maven 배포 대기 중 주석으로 보존
  - 샘플러: topK=1, temperature=0 (그리디)
  - **`maxNumTokens=512`** — 전체 컨텍스트 예산 (litertlm 0.10.0에는 별도 출력 한도 없음). 이미지 ~256 + 프롬프트 ~15 + 출력 여유 ~230. 384로 줄이면 KV 캐시 영향으로 더 느려짐 (실측)
  - **백엔드: `Backend.GPU()`** 전체 GPU (디코더+비전). 실패 시 CPU 디코더+GPU 비전 → 전체 CPU 3단계 폴백
  - **`cacheDir = context.cacheDir.absolutePath`** — 컴파일 그래프/커널 캐시 영속화
  - 비전 입력: **≤256px** (Gemma 3n 비전 인코더 내부 해상도), JPEG 품질 75
  - 프롬프트: "40자 이내 한 문장으로 구체적으로 설명해줘" + `PromptBuilder.cleanResponse()` 후처리 (마크다운/영어 번역/추가 문장 제거). 객체 검출 시 라벨 힌트 포함
  - **실측 S23 Ultra**: 평균 **4.1초**, 최소 3.58초, 응답 21-30자 한 문장. Prefill 고정 ~1.8초, Decode ~12 tok/s (병목)
- **모델 다운로드**: HuggingFace `joinhyeok/gemma` **private** 미러 리포(`HF_TOKEN` 필요). `ModelDownloadManager.resolveHfRedirect()`가 HEAD로 S3 pre-signed URL을 먼저 해석한 뒤 `DownloadManager`에 auth 헤더 없이 enqueue — 리다이렉트 시 Authorization 헤더와 S3 서명 충돌 회피
- **빌드**: AGP 8.7.3, Kotlin 2.1.0, minSDK 31, targetSDK 35

## 테스트 기기
- Galaxy S23 Ultra (SDK 36)

## 패키지 구조
```
com.vrtmv.app/
├── di/InferenceModule.kt                  # Hilt DI (LiteRtLmEngine 단일 엔진)
├── data/
│   ├── camera/
│   │   ├── FrameSource.kt                 # 백엔드 공통 인터페이스 + FrameListener + ArFrameCallback
│   │   ├── CameraXFrameSource.kt          # CameraX 백엔드 (PreviewView + ImageAnalysis)
│   │   ├── ArCoreFrameSource.kt           # ARCore 백엔드 (Session + GLSurfaceView)
│   │   └── BackgroundRenderer.kt          # ARCore 카메라 텍스처 → 풀스크린 GL 쿼드 렌더
│   ├── detection/
│   │   ├── DetectionProvider.kt           # 검출기 공통 인터페이스 + DetectionResult (입력은 Bitmap)
│   │   ├── DetectionProviderRegistry.kt   # Singleton 캐시 (MediaPipe + YOLO 상주, Intro에서 선행 초기화)
│   │   ├── MediaPipeDetectionProvider.kt  # MediaPipe EfficientDet-Lite2 구현
│   │   ├── YoloDetectionProvider.kt       # YOLOv11n TFLite 구현 (letterbox + NMS)
│   │   └── HandGestureDetector.kt         # MediaPipe GestureRecognizer 래퍼 (포인팅 홀드)
│   ├── download/ModelDownloadManager.kt   # HF 다운로드 (HEAD로 리다이렉트 선행 해석 → DownloadManager)
│   └── inference/
│       ├── InferenceEngine.kt             # 인터페이스 (describe/describeScene/isAvailable/loadModel/release)
│       ├── LiteRtLmEngine.kt              # 온디바이스 VLM (LiteRT-LM 0.10.0, 멀티모달)
│       ├── PromptBuilder.kt               # 비전/장면 프롬프트 템플릿 (짧은 한국어)
│       └── VlmMode.kt                     # OFF / ON enum
├── domain/model/
│   ├── AssetInfo.kt                       # 보조 자산 메타 + AssetRegistry (YOLO, GESTURE)
│   ├── DetectedObject.kt                  # boundingBox, label, confidence
│   ├── DetectorKind.kt                    # MEDIAPIPE / YOLO enum (메인 버튼 선택)
│   ├── InferenceState.kt                  # Idle, Loading, Success, Error
│   ├── ModelInfo.kt                       # 모델 정보 (id, url, quantization)
│   └── ModelRegistry.kt                   # 모델 목록 중앙 관리
├── navigation/AppNavHost.kt               # Intro → Main → Camera/{modelId}/{detectorId}/{useArCore}
├── ui/
│   ├── intro/IntroScreen.kt, IntroViewModel.kt
│   ├── main/MainScreen.kt, MainViewModel.kt   # 검출기 버튼 2종 + ARCore 사용 토글
│   ├── camera/CameraScreen.kt, CameraViewModel.kt  # 터치 + 제스처 병행, anchor 추종 로직
│   ├── overlay/DetectionOverlay.kt, GazeCrosshair.kt (PointingProgressRing 포함)
│   ├── components/AppHeader.kt, ResultCard.kt, DownloadProgressUI.kt
│   └── theme/Theme.kt, Color.kt, Type.kt
├── util/
│   ├── AnchorProjector.kt                 # ARCore Anchor pose → 화면 좌표 투영 (MVP 행렬)
│   ├── AssetPathResolver.kt               # 보조 자산 탐색 (getExternalFilesDir/vrtmv-assets/)
│   ├── CoordinateMapper.kt                # 이미지↔화면 좌표 변환 (CameraX 경로용)
│   ├── GazeTargetResolver.kt              # 터치→객체 매칭 알고리즘
│   ├── ImageProxyConverter.kt             # CameraX ImageProxy(RGBA_8888) → upright Bitmap
│   ├── ModelPathResolver.kt               # VLM 모델 파일 경로 탐색 (내부/Download 통합)
│   ├── RoiCropper.kt                      # 바운딩박스 크롭 (15% 패딩)
│   └── YuvToBitmapConverter.kt            # ARCore CPU 이미지(YUV_420_888) → upright Bitmap (BT.601, 90° 회전)
├── MainActivity.kt
└── VrtmvApplication.kt
```

## 자산 파일
### APK 번들 (`app/src/main/assets/`)
- `efficientdet_lite2.tflite` — MediaPipe EfficientDet-Lite2
- `coco80_labels.txt` — YOLO용 COCO 80 클래스명

### Intro 화면 자동 다운로드
최초 실행 시 `IntroScreen`이 순차 다운로드하여 아래 경로에 저장:

| 파일 | 저장 경로 | 소스 |
|---|---|---|
| `gemma-3n-E2B-it-int4.litertlm` | `/sdcard/Android/data/com.vrtmv.app/files/vrtmv/` (또는 legacy `/sdcard/Download/vrtmv/`) | HF `joinhyeok/gemma` (`HF_TOKEN` 필요) |
| `yolo11n_float16.tflite` | `/sdcard/Android/data/com.vrtmv.app/files/vrtmv-assets/` | HF `joinhyeok/gemma` (`HF_TOKEN` 필요) |
| `gesture_recognizer.task` | `/sdcard/Android/data/com.vrtmv.app/files/vrtmv-assets/` | Google CDN (공개) |

- VLM 모델 실패는 치명적(앱 사용 불가), 보조 자산 실패는 해당 기능만 비활성화 후 진행
- 경로 탐색: VLM은 `ModelPathResolver`, 보조 자산은 `AssetPathResolver`

## 빌드 & 실행
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 상세 문서
- [개발 단계](docs/development-stages.md) — 1~8단계 진행 현황
- [아키텍처 상세](docs/architecture.md) — 앱 흐름, 데이터 흐름, 인터랙션, AR 디자인
- [모델 설정](docs/model-setup.md) — 자동 다운로드, 경로 탐색, 엔진 수명주기

## gstack (글로벌 스킬)
- **설치 경로**: `~/.claude/skills/gstack`
- **다른 PC 설치**: `git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack`
- **웹 브라우징**: `/browse` 스킬 사용, `mcp__claude-in-chrome__*` 도구 사용 금지
- **스킬 미작동 시**: `cd ~/.claude/skills/gstack && ./setup` 실행

## 문서 관리 규칙
- **코드 변경 시 이 문서를 반드시 동기화할 것**: 새 파일/패키지 추가, 기술 스택 변경, 데이터 흐름 변경, 개발 단계 진행 등 구조적 변경이 발생하면 해당 섹션을 즉시 업데이트한다.
- **문서가 200줄을 초과하면 분할**: 핵심 정보는 `CLAUDE.md`에 유지하고, 상세 내용은 `docs/` 폴더 하위 문서로 분리한 뒤 링크로 연결한다.

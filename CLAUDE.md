# VRTMV - Gaze-Guided On-Device VLM for Real-Time Mobile Visual Understanding

## 응답 언어
- **모든 응답은 한국어로 작성할 것.** gstack 스킬 실행 시에도 한국어로 출력한다.
- 코드, 명령어, 파일명 등 기술적 용어는 원문 그대로 유지하되, 설명과 질문은 한국어로 한다.

## 프로젝트 개요
터치 기반 관심 영역 선택 + VLM을 결합한 모바일 시각 이해 시스템.
Android 앱에서 카메라 프리뷰 중 **사용자가 터치한 좌표의 객체를 탐지**하고, 해당 객체를 크롭하여 AI 설명을 생성, AR 오버레이로 표시한다. 객체 미검출 시 전체 프레임으로 장면 추론. **탭마다 추론 메트릭 + 입력 이미지를 합성한 PNG가 자동 기록**되어, OBJECT/FULL_FRAME 모드의 정량 비교가 가능하다.

## 핵심 동작 방식
- **상시 검출 아님** — 카메라는 프리뷰만 실행, 프레임을 버퍼에 저장
- **메인 화면 4-버튼 진입** — `객체 검출 모드` / `객체 검출(패딩 없음)` / `전체 이미지 모드` / `실험 기록 보기` (별도 Activity). 카메라 진입 시 `CaptureMode` 라우트 인자로 모드 전달.
- **탭 시 자동 캡처 + 메트릭 기록**
  - **OBJECT_DETECTION 모드 (Cascade, 25% 패딩)**: 탭 → `CascadeDetectionPipeline.runForTap()` — MediaPipe 1차 검출 + `GazeTargetResolver` 객체 선택 → 선택 객체 영역 20% 패딩 크롭을 YOLO에 재추론 → confidence 더 높은 쪽 라벨 채택(박스는 MediaPipe 원본 유지) → `RoiCropper.crop()` (25% 패딩) → `describe(crop, finalLabel, finalConfidence)`. 선택 객체 없으면 전체 프레임 `describeScene(frame)` fallback.
  - **OBJECT_DETECTION_NO_PADDING 모드**: OBJECT_DETECTION과 동일 경로, ROI 패딩만 0%.
  - **FULL_FRAME 모드**: 탭 → 검출 없이 `frameProvider.captureFrame()` → `describeScene(frame)`. 박스 비활성.
  - 모든 성공 추론은 `MetricRecorder`가 `vrtmv-records/record_{epochMs}.png` 한 장에 캡처 이미지 + 메트릭을 합성 저장 (사이드카 `.txt` 동봉).
- **초기화는 Intro 단계에서 일괄 수행** — 다운로드 완료 후 `inferenceEngine.loadModel()` + 1x1 워밍업 + `DetectionProviderRegistry.initAll()`(MediaPipe + YOLO 동시) 실행. Main/Camera 진입 시 추가 로딩 없음.
- **검출기 Cascade 자동 결합** — MediaPipe + YOLO 둘 다 Intro에서 선행 초기화되어 `DetectionProviderRegistry` Singleton에 상주. OBJECT 모드는 항상 Cascade를 사용하므로 사용자 선택 UI 없음.
- **카메라 백엔드** — ARCore 항상 우선 사용, 미지원/Session 실패 시 자동 CameraX 폴백. `FrameSource` 추상화가 두 백엔드를 흡수.
  - **CameraX (폴백)**: `PreviewView` + `ImageAnalysis` → upright Bitmap 송출
  - **ARCore (기본)**: `Session` + `GLSurfaceView`(`BackgroundRenderer`) + YUV→Bitmap 변환. 탭 시 `Frame.hitTest()` → `Anchor` 생성, 매 프레임 `AnchorProjector`로 화면 좌표 재투영. tracking 미초기화/hitTest 실패 시 카메라 전방 1.5m fallback anchor.
- **추론 중 입력 차단** — Loading 상태에서 추가 터치 무시, 롱프레스로 중지 후 재질의 가능

## 기술 스택
- **언어**: Kotlin 2.1.0 (+ `-Xskip-metadata-version-check` — litertlm 0.11.0이 Kotlin 2.3 메타데이터 사용)
- **UI**: Jetpack Compose + Material3
- **아키텍처**: MVVM + Hilt DI (단일 모듈)
- **카메라**:
  - CameraX 1.4.1 (Preview + ImageAnalysis 프레임 버퍼링) — 폴백 백엔드
  - ARCore 1.48.0 (Session + GLSurfaceView, optional 메타데이터로 미지원 기기 호환) — 월드 앵커 백엔드
  - `FrameSource` 인터페이스로 두 백엔드를 추상화, 검출기 모듈은 항상 upright Bitmap 만 수신 (ImageProxy/ARCore Image 양쪽 노출 안 함)
- **객체 검출 (Cascade 자동 결합)**:
  - MediaPipe Vision 0.10.20 (EfficientDet-Lite2, COCO 80) — 1차 검출 + 후보 박스 제공
  - YOLOv11n TFLite (tensorflow-lite 2.16.1, CPU 4스레드) — 선택된 객체 영역 ROI 한정 재확인 (`detectOnBitmap`)
  - `CascadeDetectionPipeline`이 둘을 직렬 결합, confidence 비교로 최종 라벨 결정 (GPU Delegate는 Ultralytics 이슈로 제거)
- **VLM**: LiteRT-LM 0.11.0 (온디바이스, litertlm-android) — **Gemma 4 E2B-IT** 멀티모달 (INT4, ~2.5GB). litertlm 0.11.0의 **Multi-Token Prediction (MTP)** 으로 모바일 GPU 디코드 >2× 가속
  - 샘플러: topK=1, temperature=0 (그리디)
  - **`maxNumTokens=512`** — 전체 컨텍스트 예산 (litertlm 0.11.0에도 별도 출력 한도 없음). 이미지 ~256 + 프롬프트 ~15 + 출력 여유 ~230. 384로 줄이면 KV 캐시 영향으로 더 느려짐 (실측)
  - **백엔드: `Backend.GPU()`** 전체 GPU (디코더+비전). 실패 시 CPU 디코더+GPU 비전 → 전체 CPU 3단계 폴백
  - **`cacheDir = context.cacheDir.absolutePath`** — 컴파일 그래프/커널 캐시 영속화
  - 비전 입력: **≤256px** (Gemma 4 비전 인코더 입력 해상도, Gemma 3n 와 동일), JPEG 품질 90
  - 프롬프트: "40자 이내 한 문장으로 구체적으로 설명해줘" + `PromptBuilder.cleanResponse()` 후처리 (마크다운/영어 번역/추가 문장 제거). 객체 검출 시 라벨 힌트 포함
  - **이전 기준선 (Gemma 3n + 0.10.0, S23 Ultra)**: 평균 4.1초, Prefill ~1.8초, Decode ~12 tok/s
  - **Gemma 4 + 0.11.0 MTP 기대값**: 평균 **2.5–3.0초** (Decode 24+ tok/s 가정). 실측치는 빌드 후 업데이트 예정
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
│   │   ├── DetectionProvider.kt           # 검출기 공통 인터페이스 + DetectionResult + detectOnBitmap (ROI 단발 추론)
│   │   ├── DetectionProviderRegistry.kt   # Singleton 캐시 (MediaPipe + YOLO 상주, Intro에서 선행 초기화)
│   │   ├── MediaPipeDetectionProvider.kt  # MediaPipe EfficientDet-Lite2 (Cascade 1차)
│   │   ├── YoloDetectionProvider.kt       # YOLOv11n TFLite (letterbox + NMS, Cascade 2차)
│   │   └── CascadeDetectionPipeline.kt    # MediaPipe→YOLO 직렬 결합, 탭 진입점
│   ├── download/ModelDownloadManager.kt   # HF 다운로드 (HEAD로 리다이렉트 선행 해석 → DownloadManager)
│   ├── inference/
│   │   ├── InferenceEngine.kt             # 인터페이스 (describe/describeScene 반환 = DescriptionResult)
│   │   ├── LiteRtLmEngine.kt              # 온디바이스 VLM (LiteRT-LM 0.11.0) — 4단계 시간 + 입력 크기 노출
│   │   └── PromptBuilder.kt               # 비전/장면 프롬프트 템플릿 (짧은 한국어)
│   └── recording/
│       ├── CaptureMode.kt                 # OBJECT_DETECTION / OBJECT_DETECTION_NO_PADDING / FULL_FRAME enum (라우트 인자)
│       ├── VlmTimings.kt                  # preprocess/createConv/sendMessage/total ms
│       ├── DescriptionResult.kt           # 본문 + VlmTimings + 입력 이미지 크기
│       ├── ExperimentMetric.kt            # 1회 추론의 전체 메트릭 + toDisplayLines
│       ├── RecordItem.kt                  # 저장된 기록 메타 (경로/시간/모드/크기)
│       ├── FpsMeter.kt                    # 슬라이딩 윈도우 카메라 FPS (Singleton)
│       ├── MetricRecorder.kt              # Bitmap + Metric → 합성 PNG 저장 (Canvas)
│       └── RecordRepository.kt            # vrtmv-records 디렉토리 스캔/썸네일/삭제
├── domain/model/
│   ├── AssetInfo.kt                       # 보조 자산 메타 + AssetRegistry (YOLO)
│   ├── DetectedObject.kt                  # boundingBox, label, confidence
│   ├── DetectorKind.kt                    # MEDIAPIPE / YOLO enum (Cascade 내부 구분용)
│   ├── InferenceState.kt                  # Idle, Loading, Success, Error
│   ├── ModelInfo.kt                       # 모델 정보 (id, url, quantization)
│   └── ModelRegistry.kt                   # 모델 목록 중앙 관리
├── navigation/AppNavHost.kt               # Intro → Main → Camera/{modelId}/{captureMode}
├── ui/
│   ├── intro/IntroScreen.kt, IntroViewModel.kt
│   ├── main/MainScreen.kt, MainViewModel.kt          # 4-버튼 (객체/객체*/전체/기록 보기)
│   ├── camera/CameraScreen.kt, CameraViewModel.kt    # 터치, Cascade 호출, MetricRecorder 호출
│   ├── records/                                       # 실험 기록 액티비티들
│   │   ├── RecordListActivity.kt          # 그리드 (썸네일+모드 배지), 별도 Activity
│   │   ├── RecordListViewModel.kt         # Repository 구독 + 전체 삭제
│   │   └── RecordDetailActivity.kt        # 풀스크린 PNG + 핀치 줌 + 공유/삭제, 별도 Activity
│   ├── overlay/DetectionOverlay.kt, GazeCrosshair.kt
│   ├── components/AppHeader.kt, ResultCard.kt, DownloadProgressUI.kt
│   └── theme/Theme.kt, Color.kt, Type.kt
├── util/
│   ├── AnchorProjector.kt                 # ARCore Anchor pose → 화면 좌표 투영 (MVP 행렬)
│   ├── AssetPathResolver.kt               # 보조 자산 탐색 (getExternalFilesDir/vrtmv-assets/)
│   ├── CoordinateMapper.kt                # 이미지↔화면 좌표 변환 (CameraX 경로용)
│   ├── GazeTargetResolver.kt              # 터치→객체 매칭 알고리즘
│   ├── ImageProxyConverter.kt             # CameraX ImageProxy(RGBA_8888) → upright Bitmap
│   ├── ModelPathResolver.kt               # VLM 모델 파일 경로 탐색 (내부/Download 통합)
│   ├── RoiCropper.kt                      # 바운딩박스 크롭 (25% 패딩)
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
| `gemma-4-E2B-it.litertlm` | `/sdcard/Android/data/com.vrtmv.app/files/vrtmv/` (또는 legacy `/sdcard/Download/vrtmv/`) | HF `joinhyeok/gemma` (`HF_TOKEN` 필요) |
| `yolo11n_float16.tflite` | `/sdcard/Android/data/com.vrtmv.app/files/vrtmv-assets/` | HF `joinhyeok/gemma` (`HF_TOKEN` 필요) |

- VLM 모델 실패는 치명적(앱 사용 불가), 보조 자산(YOLO) 실패는 Cascade가 MediaPipe 단독으로 진행
- 경로 탐색: VLM은 `ModelPathResolver`, 보조 자산은 `AssetPathResolver`

### 실험 기록 저장 위치
- 디렉토리: `/sdcard/Android/data/com.vrtmv.app/files/vrtmv-records/`
- 파일: `record_{epochMs}.png` (메트릭 + 캡처 이미지 합성) + 동일명 `.txt` (raw 메트릭)
- 권한 불필요 (앱 외부 파일 디렉토리), 앱 삭제 시 함께 삭제
- 공유는 `FileProvider` (`${applicationId}.fileprovider`, `res/xml/file_paths.xml`)

## 빌드 & 실행
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 상세 문서
- [개발 단계](docs/development-stages.md) — 단계별 진행 현황
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

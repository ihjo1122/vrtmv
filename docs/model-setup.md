# 온디바이스 VLM 모델 및 검출기 자산 설정

## 1. VLM — Gemma 3n E2B-IT (.litertlm)

### 개요
- **모델**: Gemma 3n E2B-IT (INT4, 약 3.5GB)
- **엔진**: LiteRT-LM 0.10.0 (`litertlm-android`)
- **호스팅**: 사용자 개인 private 미러 리포 (`HF_TOKEN` 필요) — Google 공식 리포는 gated이므로 `DownloadManager`로 직접 접근 불가
  - `https://huggingface.co/joinhyeok/gemma/resolve/main/gemma-3n-E2B-it-int4.litertlm`

### 자동 다운로드 (기본)
앱 최초 실행 시 **`IntroScreen`이 순차 다운로드 큐를 실행**하여 VLM 모델과 보조 자산 2개를 자동으로 내려받는다. 진행률은 `[n/3] 항목명` 헤더와 함께 `GradientProgressBar`로 노출.

게이티드 리포 또는 HF 토큰이 필요한 경우 `local.properties`의 `HF_TOKEN=...` 값이 `Authorization: Bearer` 헤더로 전달된다 (`ModelDownloadManager.startDownload` / `startAssetDownload`).

### 수동 배치 (폴백)
네트워크 실패·URL 변경 등으로 자동 다운로드가 실패하면:
```bash
adb push gemma-3n-E2B-it-int4.litertlm /sdcard/Download/vrtmv/
```

### 모델 탐색 우선순위 (`ModelPathResolver`)
1. 앱 내부 저장소 `files/{modelId}.litertlm`
2. `Download/vrtmv/{fileName}` (자동/수동 배치 경로 공용)

## 2. 객체 검출기 & 제스처 자산

두 가지 경로로 제공:
- **APK 번들** (`app/src/main/assets/`): MediaPipe EfficientDet-Lite2 + COCO80 라벨
- **Intro 자동 다운로드** (`getExternalFilesDir/vrtmv-assets/`): YOLO + 제스처 모델

### MediaPipe EfficientDet-Lite2 (번들)
- `efficientdet_lite2.tflite` (APK 내장, ~7.2MB)
- COCO 80 카테고리, 신뢰도 임계값 0.3
- 구현: [MediaPipeDetectionProvider.kt](../app/src/main/java/com/vrtmv/app/data/detection/MediaPipeDetectionProvider.kt)

### YOLOv11n (Intro 자동 다운로드)
- 파일명: `yolo11n_float16.tflite` (주의: "v" 없음)
- URL: `https://huggingface.co/joinhyeok/gemma/resolve/main/yolo11n_float16.tflite`
- 저장 위치: `getExternalFilesDir(null)/vrtmv-assets/yolo11n_float16.tflite`
- 입력: `[1, 640, 640, 3]` float32, 0~1 정규화, letterbox
- 출력: `[1, 84, 8400]` float32 (4 bbox + 80 classes × 8400 anchors)
- NMS: IoU 0.45, score 0.25
- 백엔드: **CPU 4스레드만 사용** (YOLOv11n GPU delegate 알려진 이슈 회피)
- 구현: [YoloDetectionProvider.kt](../app/src/main/java/com/vrtmv/app/data/detection/YoloDetectionProvider.kt)

#### YOLO 모델 생성 방법 (리포 업로드용, 참고)
```bash
pip install ultralytics
yolo export model=yolo11n.pt format=tflite half=True imgsz=640
# 출력: yolo11n_saved_model/yolo11n_float16.tflite
# HF 리포에 업로드
```

### MediaPipe Gesture Recognizer (Intro 자동 다운로드)
- 파일명: `gesture_recognizer.task`
- URL: `https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task` (Google 공개 CDN)
- 저장 위치: `getExternalFilesDir(null)/vrtmv-assets/gesture_recognizer.task`
- 기능: 검지 손가락 포인팅 제스처 감지 → 0.5초 홀드 시 터치와 동일 경로로 캡처 발화
- 구현: [HandGestureDetector.kt](../app/src/main/java/com/vrtmv/app/data/detection/HandGestureDetector.kt)
- 파일이 없어도 앱은 정상 동작 — 제스처 기능만 비활성화됨 (터치 경로는 유지)

### 보조 자산 탐색 (`AssetPathResolver`)
- 디렉터리: `context.getExternalFilesDir(null)/vrtmv-assets/`
- 앱 제거 시 OS 자동 정리, 추가 권한 불필요
- 부분 다운로드 배제를 위한 최소 크기 검사(1KB)

## 3. 모델 레지스트리 (`ModelRegistry`)

- 기본 모델: `gemma3n-e2b-it-int4`
- 모델 추가 시 [ModelRegistry.kt](../app/src/main/java/com/vrtmv/app/domain/model/ModelRegistry.kt)의 `models` 리스트에만 추가

## 4. 엔진·검출기 수명주기

### LiteRtLmEngine (Singleton, Hilt DI)
- `loadModel()`: `IntroViewModel`에서 호출 (Intro 단계 일괄 초기화), mutex로 동기화
- 샘플러: `topK=1`, `temperature=0` (그리디, 지연 최소화)
- `maxNumTokens=512` (전체 컨텍스트 예산: 이미지 ~256 + 프롬프트 ~15 + 출력 여유 ~230)
- 비전 입력: 최대 256px로 리사이즈 (Gemma 3n 비전 인코더 내부 해상도), JPEG 품질 75
- 백엔드 프로파일: PERFORMANCE(전체 GPU) → BALANCED(CPU 디코더+GPU 비전) → COOL(전체 CPU) 3단계 폴백, thermal-aware 자동 강등
- `release()`: `CameraViewModel.onCleared`

### DetectionProvider (검출기)
- 두 검출기 모두 Intro에서 `DetectionProviderRegistry.initAll()`로 선행 초기화
- `CameraViewModel.init`에서 `SavedStateHandle.detectorId`로 `DetectorKind` 결정, Registry에서 해당 Singleton 획득
- Camera 전환 시 추가 로딩 없이 즉시 재사용

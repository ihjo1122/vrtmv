# 아키텍처 상세

## 앱 흐름
```
앱 시작 → IntroScreen
  → MANAGE_EXTERNAL_STORAGE 권한 확인 (미허용 시 설정 화면 유도)
  → "패치 사항 확인중..." (병렬: VLM/YOLO 존재 확인)
  → 미존재 항목 순차 다운로드 [n/2] (VLM → YOLO)
  → inferenceEngine.loadModel() + 1x1 워밍업
  → DetectionProviderRegistry.initAll() (MediaPipe + YOLO 병렬)
  → "최신 상태입니다" → MainScreen

MainScreen
  → VLM 모델 정보 배너 (Gemma 4 E2B-IT)
  → 캡처 모드 4-버튼:
      · 객체 검출 모드 시작            (OBJECT_DETECTION, 25% 패딩)
      · 객체 검출 (패딩 없음) 시작     (OBJECT_DETECTION_NO_PADDING, 0% 패딩)
      · 전체 이미지 모드 시작          (FULL_FRAME, 검출 없음)
      · 실험 기록 보기                 (별도 Activity: RecordListActivity)
  → 카메라 모드 진입: camera/{modelId}/{captureMode}
```

## 주요 데이터 흐름
```
FrameSource (ARCore 기본, 실패 시 CameraX 폴백)
  → upright Bitmap 변환 (소스 책임)
  → FrameListener 디스패치 (~10fps, 검출기 내부 frameSkip)
      → DetectionProvider.updateFrame(bitmap, ts)   // MediaPipe + YOLO 둘 다 버퍼링
  (콜백 종료 직후 소스가 비트맵 recycle — 보존 필요 시 사본 생성)
  (ARCore 백엔드는 추가로 GL thread 매 프레임 ArFrameCallback 호출 → AnchorProjector로 anchor 투영)

사용자 입력 (추론 중이면 차단)
  · 화면 탭                    → CameraViewModel.onTapDetect(offset, viewW, viewH)
  · FULL_FRAME 모드 시작 버튼  → CameraViewModel.startFullFrameCapture()

모드별 분기
  · OBJECT_DETECTION / OBJECT_DETECTION_NO_PADDING
      → CascadeDetectionPipeline.runForTap()
        → MediaPipe 1차 검출 → GazeTargetResolver.resolve(point, objects, mapper)
        → 선택 객체 영역 20% 패딩 ROI → YOLO detectOnBitmap 재추론
        → confidence 높은 쪽 라벨 채택 (박스는 MediaPipe 원본)
      → ARCore 활성 시: arSource.latestFrame.hitTest() → Anchor 생성 (또는 전방 1.5m fallback)
      → RoiCropper.crop(box, padRatio) → LiteRtLmEngine.describe(crop, label, conf)
      → 선택 객체 없음: 전체 프레임 → LiteRtLmEngine.describeScene(frame)
  · FULL_FRAME
      → frameProvider.captureFrame() → LiteRtLmEngine.describeScene(frame)
      → 검출/박스/앵커 없음

추론 결과 + MetricRecorder
  → 성공 시 캡처 이미지 + 메트릭을 합성한 PNG 한 장 + .txt를 vrtmv-records/ 에 저장

추론 중지 (롱프레스)
  → inferenceJob.cancel() → Idle 복귀 → 다시 입력 가능
```

## DetectionProvider 아키텍처
- **인터페이스**: `updateFrame(bitmap, ts)`, `detectNow(): DetectionResult?`, `detectOnBitmap(bitmap): DetectionResult?` (ROI 단발 추론), `paused`, `close()`
- **구현체**:
  - `MediaPipeDetectionProvider` — EfficientDet-Lite2 (APK 번들), COCO 80, Cascade 1차
  - `YoloDetectionProvider` — YOLOv11n TFLite (Intro 다운로드), letterbox + NMS, CPU 4스레드, Cascade 2차
- **두 검출기 모두 Intro에서 선행 초기화**: `DetectionProviderRegistry.initAll()`이 병렬로 초기화 → Singleton 상주
- **CascadeDetectionPipeline**: 탭 진입점. MediaPipe → GazeTargetResolver → YOLO ROI 재추론 → 최종 라벨/박스 결정
- **공통 하류 파이프라인**: `RoiCropper`, `LiteRtLmEngine`, `MetricRecorder` 동일 코드 경로 사용

## VLM 추론 (LiteRtLmEngine)
- 토글 없음 — 모든 캡처가 자동 발화
- 객체 검출됨: `describe(crop, label, conf)` (라벨 힌트 포함 프롬프트)
- 미검출/FULL_FRAME: `describeScene(frame)` (라벨 힌트 없음)
- 샘플러: `topK=1`, `temperature=0` (그리디)
- `maxNumTokens=512` — 전체 컨텍스트 예산 (이미지 ~256 + 프롬프트 ~15 + 출력 ~230)
- 비전 입력: ≤256px, JPEG 품질 90
- 백엔드 폴백 3단계: PERFORMANCE (전체 GPU) → BALANCED (CPU 디코더 + GPU 비전) → COOL (전체 CPU), thermal-aware
- 컴파일 그래프/커널 캐시: `context.cacheDir`
- 후처리: `PromptBuilder.cleanResponse()` (마크다운/영어 번역/추가 문장 제거)

## 인터랙션
- **터치**: 모드별 경로로 진입 (위 데이터 흐름 참조)
- **추론 중 입력**: 무시 (터치)
- **롱프레스**: 추론 중지 + 선택 해제 → 다시 입력 가능

## AR 오버레이 디자인
- **선택 객체**: 시안색 코너 브래킷(펄스) + 점선 테두리 + 스캐닝 라인 + 동적 크기 플로팅 태그 (화면 75% 기준)
- **비선택 객체**: 반투명 흰색 코너 브래킷 + 작은 라벨
- **크로스헤어**: 노란색 원+십자선 (터치 위치)
- **장면 AR 태그**: 객체 미검출 시 터치 좌표(또는 ARCore 앵커 위치)에 펄스 원 + 커넥터 + 장면 설명 태그
- **앵커 추종 태그 (ARCore 모드)**: `anchoredTagPosition != null` 이면 결과 태그가 정적 박스 중심이 아닌 매 프레임 투영된 anchor 좌표를 추종 — 카메라 이동에 따라 실세계에 고정. 박스/브래킷 자체는 검출 시점 좌표로 정적.
- **하단 카드**: Idle→힌트, Loading→진행바, Error→에러, Scene→장면 설명

## 카메라 백엔드 추상화 (FrameSource)
- **공통 인터페이스**: `start(lifecycleOwner) / stop / addListener / removeListener / close / view: View`
- **CameraXFrameSource (폴백)**: `PreviewView` 노출. `ImageAnalysis` analyzer 에서 `ImageProxyConverter.toUprightBitmap` 호출 → 리스너 디스패치 → recycle.
- **ArCoreFrameSource (기본)**: `GLSurfaceView` 노출. GL thread `onDrawFrame` 마다 `Session.update()` → `BackgroundRenderer.draw()` (카메라 텍스처) → `Frame.acquireCameraImage()` → `YuvToBitmapConverter.convert()` (90° 회전 포함) → 워커 스레드(SynchronousQueue + AbortPolicy)에서 리스너 디스패치 → recycle. 워커 바쁠 시 즉시 드롭(자동 frame skip).
- **Anchor 흐름**: 탭 시 `arSource.latestFrame.hitTest()` → `Anchor` 또는 전방 1.5m fallback. VM 의 `ArFrameCallback` 가 매 GL 프레임에서 `AnchorProjector` 로 화면 좌표 갱신 → `CameraUiState.anchoredTagPosition` → `DetectionOverlay` 가 태그 위치로 사용.
- **폴백 결정**: `CameraScreen.selectFrameSource(useArCore)` — 토글 OFF 또는 `ArCoreApk.checkAvailability()` 미지원/transient 또는 `Session()` 생성 예외 → CameraXFrameSource. 결과는 logcat 명시 (`FrameSource=ArCore` / `FrameSource=CameraX`).

## 실험 기록 (Metric)
- 성공 추론 시 `MetricRecorder`가 캡처 이미지 + 메트릭을 한 PNG로 합성 저장 (Canvas 그리기)
- 경로: `getExternalFilesDir(null)/vrtmv-records/record_{epochMs}.png` + 동일명 `.txt`
- 모드 배지: `OBJECT` / `OBJECT*` (패딩 없음) / `FULL`
- `RecordListActivity`: 그리드 + 썸네일 + 모드 배지, 전체 삭제
- `RecordDetailActivity`: 풀스크린 PNG + 핀치 줌(`detectTransformGestures`) + 공유/삭제
- 공유: `FileProvider` (`${applicationId}.fileprovider`, `res/xml/file_paths.xml`)

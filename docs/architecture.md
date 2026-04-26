# 아키텍처 상세

## 앱 흐름
```
앱 시작 → IntroScreen
  → MANAGE_EXTERNAL_STORAGE 권한 확인 (미허용 시 설정 화면 유도)
  → "패치 사항 확인중..." (ModelDownloadManager.modelExists())
  → 모델 있음: "최신 상태입니다" → 1.5초 대기 → MainScreen
  → 모델 없음: (MainScreen에서 검출기 버튼 탭 시 HF 리포에서 자동 다운로드)

MainScreen
  → VLM 모델 정보 배너 (Gemma 3n E2B-IT)
  → 검출기 선택 버튼 2종:
      · MediaPipe EfficientDet-Lite2
      · YOLOv11n (TFLite)
  → 버튼 탭 → 모델 다운로드 필요 시 DownloadProgressUI → 완료 시 CameraScreen
      (navigation: camera/{modelId}/{detectorId})
```

## 주요 데이터 흐름
```
FrameSource (백엔드: CameraXFrameSource 또는 ArCoreFrameSource — Main 토글)
  → upright Bitmap 변환(소스 책임)
  → FrameListener 디스패치
      → HandGestureDetector.process(bitmap, ts)        // 손 포인팅 실시간 추적
      → DetectionProvider.updateFrame(bitmap, ts)      // 객체 검출용 프레임 버퍼링 (~10fps, frameSkipCounter % 3)
  (콜백 종료 직후 소스가 비트맵 recycle — 보존 필요 시 사본 생성)
  (ARCore 백엔드는 추가로 GL thread 매 프레임 ArFrameCallback 호출 → AnchorProjector 로 anchor 투영)

사용자 입력 (추론 중이면 차단)
  A) 화면 탭                                    → CameraViewModel.onTapDetect(offset, viewW, viewH)
  B) 검지 포인팅 0.5초 홀드 (PointingProgressRing) → CameraViewModel.onPointingConfirmed(normX, normY, viewW, viewH)
  → 공통 경로:
    → DetectionProvider.detectNow()               // 현재 프레임 복사 → 검출 (MediaPipe 또는 YOLO)
    → GazeTargetResolver.resolve(point, objects, mapper)
    → ARCore 활성 시: arSource.latestFrame.hitTest(screenX,Y) → Anchor 생성 (또는 전방 1.5m fallback)
    → selectedObject + capturedBitmap + (anchor)   // AR 태그 표시

VLM 추론 (VLM 모드가 ON일 때)
  → 객체 검출됨: RoiCropper.crop → LiteRtLmEngine.describe → AR 태그 설명
  → 객체 미검출: 전체 프레임 → LiteRtLmEngine.describeScene → 터치 좌표에 장면 AR 태그
  → Greedy 샘플링 (topK=1, temp=0), maxNumTokens=512, 256px 입력, JPEG 75

추론 중지 (롱프레스)
  → inferenceJob.cancel() → Idle 복귀 → 다시 입력 가능
```

## DetectionProvider 아키텍처
- **인터페이스**: `updateFrame(ImageProxy)`, `detectNow(): DetectionResult?`, `paused`, `close()`
- **구현체 선택**: `CameraViewModel.init`에서 `SavedStateHandle.detectorId`로 `DetectorKind` 결정, `DetectionProviderRegistry`에서 해당 Singleton 획득
- **두 검출기 모두 Intro에서 선행 초기화**: `DetectionProviderRegistry.initAll()`로 MediaPipe + YOLO 동시 초기화, Camera 전환 시 즉시 재사용
- **공통 하류 파이프라인**: `GazeTargetResolver`, `CoordinateMapper`, `RoiCropper`, `LiteRtLmEngine` 동일 코드 경로 사용 → 학술 비교 공정성 확보

## 인터랙션
- **터치**: 객체 검출 → 선택 → AR 태그 + (VLM ON이면) 온디바이스 추론
- **손 포인팅 (비접촉)**: 검지 펴서 0.5초 유지 → 프로그레스 링 가득참 → 터치와 동일 경로
- **추론 중 입력**: 무시 (터치·제스처 모두)
- **롱프레스**: 추론 중지 + 선택 해제 → 다시 입력 가능
- **우상단 버튼**: VLM On/Off 토글
  - 검정 반투명: Off
  - 청록색: On

## AR 오버레이 디자인
- **선택 객체**: 시안색 코너 브래킷(펄스) + 점선 테두리 + 스캐닝 라인 + 동적 크기 플로팅 태그 (화면 75% 기준)
- **비선택 객체**: 반투명 흰색 코너 브래킷 + 작은 라벨
- **크로스헤어**: 노란색 원+십자선 (터치 위치에만 표시)
- **포인팅 프로그레스 링**: 시안색 원호 (검지 끝 위치, 0~360° 차오름)
- **장면 AR 태그**: 객체 미검출 시 터치 좌표(또는 ARCore 앵커 위치)에 펄스 원 + 커넥터 + 장면 설명 태그
- **앵커 추종 태그 (ARCore 모드)**: `anchoredTagPosition != null` 이면 결과 태그가 정적 박스 중심이 아닌 매 프레임 투영된 anchor 좌표를 추종 — 카메라 이동에 따라 실세계에 고정. 박스/브래킷 자체는 검출 시점 좌표로 정적.
- **하단 카드**: Idle→힌트, Loading→진행바, Error→에러, Scene→장면 설명

## 카메라 백엔드 추상화 (FrameSource)
- **공통 인터페이스**: `start(lifecycleOwner) / stop / addListener / removeListener / close / view: View`
- **CameraXFrameSource**: `PreviewView` 노출. `ImageAnalysis` analyzer 에서 `ImageProxyConverter.toUprightBitmap` 호출 → 리스너 디스패치 → recycle.
- **ArCoreFrameSource**: `GLSurfaceView` 노출. GL thread `onDrawFrame` 마다 `Session.update()` → `BackgroundRenderer.draw()` (카메라 텍스처) → `Frame.acquireCameraImage()` → `YuvToBitmapConverter.convert()` (90° 회전 포함) → 워커 스레드(SynchronousQueue + AbortPolicy)에서 리스너 디스패치 → recycle. 워커 바쁠 시 즉시 드롭(자동 frame skip).
- **Anchor 흐름**: 탭 시 `arSource.latestFrame.hitTest()` → `Anchor` 또는 전방 1.5m fallback. VM 의 `ArFrameCallback` 가 매 GL 프레임에서 `AnchorProjector` 로 화면 좌표 갱신 → `CameraUiState.anchoredTagPosition` → `DetectionOverlay` 가 태그 위치로 사용.
- **폴백 결정**: `CameraScreen.selectFrameSource(useArCore)` — 토글 OFF 또는 `ArCoreApk.checkAvailability()` 미지원/transient 또는 `Session()` 생성 예외 → CameraXFrameSource. 결과는 logcat 명시 (`FrameSource=ArCore` / `FrameSource=CameraX`).

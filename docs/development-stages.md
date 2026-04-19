# 개발 단계

### 1단계: 카메라 + AR 오버레이 ✅ 완료
- CameraX 프리뷰, 런타임 권한 처리
- AR 스타일 오버레이 레이어 (코너 브래킷, 플로팅 태그, 스캐닝 라인)
- 크로스헤어, 하단 힌트 카드

### 2단계: MediaPipe Object Detector ✅ 완료
- **EfficientDet-Lite2** 모델 번들링 (assets/, 7.2MB)
- DetectionProvider (MediaPipe/YOLO 구현체) — **온디맨드 방식** (프레임 버퍼링 + 회전 적용 + 터치 시 검출)
- CoordinateMapper — 스케일+오프셋만 담당 (회전은 각 `DetectionProvider.updateFrame` 내부에서 `postRotate`로 적용)

### 3단계: 터치 기반 ROI 선택 ✅ 완료
- GazeTargetResolver (포함 검사 → 최소 면적 → 최근접 중심)
- 터치 → 프레임 캡처 → 검출 → 터치 좌표 객체 매칭 → AR 태그 표시

### 4단계: VLM 추론 연결 ✅ 완료
- RoiCropper (바운딩박스 크롭 + 15% 패딩)
- LiteRtLmEngine (LiteRT-LM, 온디바이스 멀티모달)
- PromptBuilder (객체 설명 + 장면 설명 프롬프트)
- VLM 모드 토글: Off / On (우상단 버튼)
- viewModelScope.launch로 비동기 추론, 60초 타임아웃
- Loading/Error 상태 UI (ResultCard, AR 태그)
- 객체 미검출 시 전체 이미지 장면 추론 (describeScene)
- 추론 중 터치 차단 + 롱프레스로 중지

### 5단계: 인트로 + 메인 화면 ✅ 완료
- **인트로 화면**: 앱 시작 시 "패치 사항 확인중..." 표시
  - 모델 존재 시: "최신 상태입니다" → 1.5초 후 메인 화면 이동
  - 모델 미존재 시: 자동 다운로드 (게이지바) 또는 수동 배치 모델이면 바로 메인 진입
  - 다운로드 경로: `Download/vrtmv/{modelFileName}`
- **메인 화면**: AR Camera 버튼 → 기존 CameraScreen 실행
- **네비게이션**: Jetpack Navigation Compose (Intro → Main → Camera)
- **ModelDownloadManager**: Android DownloadManager 기반, 중복 다운로드 방지, 진행률 Flow

### 6단계: 모델 비교 + 폴리싱 🔧 진행중
- **모델 관리**: ModelInfo + ModelRegistry로 모델 목록 중앙 관리 (기본: Gemma 3n E2B-IT INT4)
- **모델별 AR Camera 버튼**: MainScreen에서 모델별 버튼
- **AR 태그 동적 크기**: 화면 너비 75% 기준 동적 너비, 최대 4줄 한국어 설명 수용
- **모델 자동 다운로드**: 미다운로드 모델 클릭 시 MainScreen에서 다이얼로그로 다운로드
- **LiteRtLmEngine 파라미터화**: loadModel(modelInfo) + 모델별 내부 경로 분리 + mutex
- **좌표 디버그 로깅**: CameraViewModel에서 터치→매핑→검출 좌표 전체 로그 출력
- **CoordinateMapper 통합**: ViewModel에서만 생성, CameraScreen 이중 생성 제거
- **추론 시간 표시**: CameraScreen 좌상단에 모델명 + 추론 시간(ms)
- **모델 초기화 로딩**: CameraScreen 진입 시 로딩 스피너 (5-15초)
- **저장공간 체크**: 다운로드 전 StatFs로 가용 공간 확인
- **DownloadProgressUI**: IntroScreen/MainScreen 공통 다운로드 진행률 컴포넌트
- **Navigation**: camera/{modelId} 라우트 + SavedStateHandle로 ViewModel에 전달
- **LiteRT-LM 단일 엔진**: Gemma 3n E2B-IT 멀티모달 엔진 (이미지 직접 입력, GPU 비전 백엔드)

### 8단계: Intro 자동 다운로드 통합 ✅ 완료 (2026-04-11)
- **AssetInfo/AssetRegistry**: YOLO, 제스처 자산 메타데이터 중앙 관리
- **AssetPathResolver**: `getExternalFilesDir(null)/vrtmv-assets/` 탐색, 부분 다운로드 배제(1KB 최소)
- **ModelDownloadManager.startAssetDownload**: `setDestinationInExternalFilesDir` 사용, HF_TOKEN 헤더 재사용
- **IntroViewModel 다운로드 큐**: VLM → YOLO → 제스처 순차 처리, `[n/3]` 진행 표시
- **graceful degradation**: 보조 자산 실패 시 "계속" 버튼으로 스킵, VLM 실패만 치명적
- **HandGestureDetector / YoloDetectionProvider**: 상대 경로(assets) → 절대 경로(내부 저장소)로 로드 방식 변경
- **YOLO 파일명 정정**: `yolov11n_float16.tflite` → `yolo11n_float16.tflite` (HF 업로드 실제 파일명 일치)
- **YOLO GPU Delegate 제거**: Ultralytics YOLOv11n Android TFLite GPU delegate 이슈 대응, CPU 4스레드 고정
- **사용자 편의성**: APK 설치 후 **수동 자산 배치 불필요** — 최초 실행 시 전 자산 자동 준비

### 7단계: LiteRT-LM 0.10.0 전환 + 검출기 듀얼 + 손 제스처 ✅ 완료 (2026-04-11)
- **VLM**: Gemma 3n E2B-IT (INT4) 유지. Gemma 4 시도했으나 litertlm 0.10.0 포맷 미지원 + AICore 부재로 보류 (0.10.1 대기, ModelRegistry에 주석 보존)
- **LiteRT-LM 0.10.0**: API 재설계 대응 (`SamplerConfig`, `ConversationConfig`, `Backend.CPU()`/`GPU()` 인스턴스화, `Contents` 래퍼)
- **Kotlin 메타데이터 우회**: `-Xskip-metadata-version-check` (KSP가 Kotlin 2.3.x 미지원이므로 정식 bump 보류)
- **추론 속도 최적화**: greedy 디코딩(topK=1, temp=0), `maxNumTokens=512`, 비전 입력 256px, JPEG 75, 40자 이내 구체적 프롬프트
- **HF 자동 다운로드**: `huggingface.co/joinhyeok/gemma` 공개 미러 리포에서 `DownloadManager`가 자동 수신. `HF_TOKEN` 헤더 자동 전달 (gated 대응)
- **검출기 듀얼 버전**: `DetectionProvider` 인터페이스 → `MediaPipeDetectionProvider` + `YoloDetectionProvider` (TFLite 2.16.1 + GPU Delegate, letterbox, NMS, COCO80)
- **메인 화면 재구성**: 모델 카드 대신 **검출기 버튼 2종** (MediaPipe / YOLO), 네비게이션 `camera/{modelId}/{detectorId}`
- **손 포인팅 제스처**: `HandGestureDetector` (MediaPipe GestureRecognizer LIVE_STREAM), 검지 0.5초 홀드 → 터치 경로 자동 발화, `PointingProgressRing` 피드백
- **자산 별도 준비**: `yolov11n_float16.tflite` (Ultralytics export), `gesture_recognizer.task` (MediaPipe) — 누락 시 해당 기능만 graceful degradation

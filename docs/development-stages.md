# 개발 단계

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

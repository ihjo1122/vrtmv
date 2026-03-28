# 아키텍처 상세

## 앱 흐름
```
앱 시작 → IntroScreen
  → MANAGE_EXTERNAL_STORAGE 권한 확인 (미허용 시 설정 화면 유도)
  → "패치 사항 확인중..." (ModelDownloadManager.modelExists())
  → 모델 있음: "최신 상태입니다" → 1.5초 대기 → MainScreen
  → 모델 없음: DownloadManager로 다운로드 (게이지바) → 완료 → MainScreen
  → 수동 배치 모델 (downloadUrl 빈 문자열): 바로 MainScreen 진입

MainScreen
  → AR Camera 버튼 클릭 → CameraScreen (모델 로드 → AR 카메라)
```

## 주요 데이터 흐름
```
CameraX ImageAnalysis (매 프레임)
  → ObjectDetectionManager.updateFrame(imageProxy)  // 버퍼링만 (bitmapLock 동기화)

사용자 터치 (추론 중이면 차단)
  → CameraViewModel.onTapDetect(tapPoint, manager, viewW, viewH)
  → ObjectDetectionManager.detectNow()  // 현재 프레임 복사 → 검출
  → GazeTargetResolver.resolve(tapPoint, objects, mapper)  // 터치 좌표 객체 매칭
  → selectedObject + capturedBitmap  // AR 태그 표시

VLM 추론 (모드가 OFF가 아닐 때)
  → 객체 검출됨: RoiCropper.crop → InferenceEngine.describe → AR 태그 설명
  → 객체 미검출: 전체 프레임 → InferenceEngine.describeScene → 터치 좌표에 장면 AR 태그

추론 중지 (롱프레스)
  → inferenceJob.cancel() → Idle 복귀 → 다시 터치 가능
```

## 인터랙션
- **터치**: 객체 검출 → 선택 → AR 태그 + (VLM ON이면) 온디바이스 추론
- **터치 (객체 없음)**: VLM ON이면 전체 프레임으로 장면 추론 → 터치 좌표에 AR 태그
- **추론 중 터치**: 무시 (Loading 상태에서 차단)
- **롱프레스**: 추론 중지 + 선택 해제 → 다시 터치 가능
- **우상단 버튼**: VLM On/Off 토글
  - 검정 반투명: Off
  - 청록색: On

## AR 오버레이 디자인
- **선택 객체**: 시안색 코너 브래킷(펄스) + 점선 테두리 + 스캐닝 라인 + 동적 크기 플로팅 태그 (화면 75% 기준)
- **비선택 객체**: 반투명 흰색 코너 브래킷 + 작은 라벨
- **크로스헤어**: 노란색 원+십자선 (터치 위치에만 표시)
- **장면 AR 태그**: 객체 미검출 시 터치 좌표에 펄스 원 + 커넥터 + 장면 설명 태그
- **하단 카드**: Idle→힌트, Loading→진행바, Error→에러, Scene→장면 설명

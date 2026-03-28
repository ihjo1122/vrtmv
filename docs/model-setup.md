# 온디바이스 VLM 모델 설정

## LiteRT-LM 모델 (.litertlm) — 멀티모달 (이미지+텍스트)
- **Gemma 3n E2B-IT (INT4)** — ~3.66GB, 멀티모달 비전, 수동 배치 필요 (HuggingFace gated)
  https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
  ```bash
  # PC에서 다운로드 후 디바이스 전달
  adb push gemma-3n-E2B-it-int4.litertlm /sdcard/Download/vrtmv/
  ```

## 모델 배치
- PC에서 다운로드 후 `adb push`로 수동 배치 (`Download/vrtmv/{fileName}`)
- 이미 해당 경로 또는 내부 저장소에 모델이 있으면 스킵
- Download 폴더의 모델을 직접 참조 (내부 저장소 복사 불필요)
- `MANAGE_EXTERNAL_STORAGE` 권한 필요 (앱 첫 실행 시 자동 요청)

## 모델 탐색 우선순위 (ModelPathResolver)
1. 앱 내부 저장소 `files/{modelId}.litertlm`
2. `Download/vrtmv/{fileName}` (수동 배치 경로)

모델 파일이 없으면 VLM ON 시 안내 메시지 표시.

## 모델 레지스트리 (ModelRegistry)
- 기본 모델: `gemma3n-e2b-it-int4` (Gemma 3n E2B-IT INT4)
- `downloadUrl`이 빈 문자열 → 수동 배치 모델 (다운로드 시도 시 `ManualInstallRequiredException`)
- 모델 추가 시 `ModelRegistry.kt`만 수정

## 엔진 수명주기
- **Singleton** (`LiteRtLmEngine`) — Hilt DI로 관리
- **loadModel()**: CameraViewModel init에서 호출, mutex로 동기화
- **release()**: CameraViewModel.onCleared()에서 GPU 메모리 해제
- **describe() / describeScene()**: mutex.withLock + Dispatchers.IO

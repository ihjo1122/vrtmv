# TODOS

## 벤치마크 화면 (Approach C 확장)
- **What:** 같은 이미지로 모델별 추론시간/토큰수/응답품질을 자동 측정해서 나란히 표 표시하는 벤치마크 화면 추가
- **Why:** 교수님 앞에서 양자화 수준별 차이를 논문급 데이터로 보여줄 수 있음. 시연 임팩트 극대화.
- **Depends on:** Part 2 (모델 비교 기능) 완료 후
- **Context:** 디자인 문서 `docs/design-20260327-polishing-model-comparison.md`의 Approach C 참조. ModelRegistry + 모델별 CameraScreen이 완성되면 벤치마크 화면은 그 위에 쉽게 구축 가능. BenchmarkScreen + BenchmarkViewModel 신규 생성, 같은 캡처 이미지를 여러 모델에 순차 전달하여 결과 비교.
- **Effort:** CC ~30분

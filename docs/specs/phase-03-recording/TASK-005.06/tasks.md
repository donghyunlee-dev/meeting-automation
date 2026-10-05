# 순차 Audio 업로드 작업 항목

1. TASK-005.03 repository, API-007/008 선행 Issue, SCR-003 UI contract를 대조한다. 완료 증거: 타입과 오류 mapping 차이가 없다.
2. reconcile, serial ordering, request headers/hash, ACK deletion, retry classification 테스트를 먼저 작성해 실패를 확인한다.
3. API-008 수신 목록과 local pending을 조정한다. 완료 증거: 원격 ACK Chunk 제거, missing local sequence만 계획된다.
4. sequence 오름차순으로 API-007을 호출하고 각 200 ACK 뒤 local sequence 하나를 지운다. 완료 증거: 요청 concurrency가 1이며 응답 유실 재요청은 멱등이다.
5. Web Crypto SHA-256, exact Content-Length metadata, MIME 및 stable chunkId header를 구현한다. 완료 증거: Backend API-007 contract와 일치한다.
6. transient retry를 5회로 제한하고 이후 수동 재시도 및 4xx stop/Session-not-found preserve를 구현한다. 완료 증거: 네트워크 오류와 영구 오류가 다른 UX를 가진다.
7. Recording 화면 progress/error/retry를 연결하고 pending 존재 시 process handoff를 막는다. 완료 증거: success 전환은 전체 ACK 뒤 한 번만 호출된다.
8. Frontend test/lint/build와 API contract 통합 QA를 완료한다. 완료 증거: retry count, sequence evidence, UI 증거가 리뷰에 남는다.

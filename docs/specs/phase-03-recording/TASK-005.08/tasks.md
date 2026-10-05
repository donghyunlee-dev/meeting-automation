# 녹음 종료 연결 작업 항목

1. end confirm, controller stop, IndexedDB append, upload flush, API-009/navigation contracts를 확인한다. 완료 증거: orchestration input type이 정합하다.
2. confirm 중복/stop order/final chunk/pending/API retry/navigation 테스트를 먼저 작성해 실패를 확인한다.
3. end-confirm state guard와 recorder stop 완료 및 final chunk storage 완료 대기를 구현한다. 완료 증거: final Blob 저장 전 uploader가 시작되지 않는다.
4. TASK-005.06 flush/reconcile를 호출하고 pending count가 0인지 확인한다. 완료 증거: 미전송 Chunk일 때 process API 호출이 없다.
5. expectedChunks/duration/mimeType와 stable Session idempotency key로 API-009를 호출한다. 완료 증거: retry와 중복 click이 같은 요청을 공유한다.
6. 202 Session ID 확인 뒤 Processing route로 이동하고 오류·수동 재시도를 연결한다. 완료 증거: 유효 응답 전 navigation이 발생하지 않는다.
7. 오류 경로의 IndexedDB 데이터 보존과 접근성 상태를 점검한다. 완료 증거: 어떤 실패도 pending Audio를 자동 삭제하지 않는다.
8. FE `npm run test`, `npm run lint`, `npm run build`와 contract/E2E QA를 완료한다. 완료 증거: 단계별 실행 순서와 API payload가 확인된다.

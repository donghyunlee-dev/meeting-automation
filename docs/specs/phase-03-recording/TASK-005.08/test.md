# 종료 후 Processing 시작 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| END-FLOW-01 | SCR-004 취소 action | recorder/timer/upload/process 변경 없이 Recording/Paused 유지 | component test |
| END-FLOW-02 | 종료 확정 2회 연속 | stop/orchestration 한 번, confirm busy 처리 | duplicate action test |
| END-FLOW-03 | recorder stop 후 final dataavailable | final append 완료 뒤 upload flush 시작 | deferred event ordering test |
| END-FLOW-04 | Chunk upload pending/failure | API-009 호출 없음, 진행/재시도 안내 표시 | uploader integration test |
| END-FLOW-05 | 모든 Chunk ACK, 정상 API-009 202 | 정확한 expectedChunks/duration/mime/key 전송 후 processing route 이동 | API/router integration test |
| END-FLOW-06 | API-009 timeout 뒤 동일 종료 action 재시도 | 같은 payload/key 재사용, 새 작업 중복 없음 | idempotency replay test |
| END-FLOW-07 | API-009 `AUDIO_CHUNKS_INCOMPLETE` | uploader reconcile 요청, process route 이동 없음 | recovery test |
| END-FLOW-08 | API-009 4xx validation/state error | 자동 반복 호출 없음, 안전한 오류와 가능한 복구 action | error mapping/component test |
| END-FLOW-09 | 202 response sessionId mismatch | 안전 오류, processing navigation 차단 | response contract test |
| END-FLOW-10 | API/네트워크 오류가 발생 | IndexedDB pending Audio 보존 | repository assertion |

Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행하고 결과를 기록한다.

## 통합/수동 QA

- 종료 취소, Recording/Paused 각각에서 종료 확인 후 stop부터 Processing 이동까지 순서를 확인한다.
- 마지막 Chunk를 저장/업로드 지연시켜 API-009 호출이 flush 이후인지 확인한다.
- network offline, API-009 timeout, 409 incomplete에서 화면 action/데이터 보존을 확인한다.
- 중복 클릭과 timeout 재시도에서 Idempotency-Key/payload가 동일하고 job이 중복 생성되지 않는지 확인한다.
- 유효 202 후 `/meetings/{sessionId}/processing`에 해당 Session ID로 이동하는지 확인한다.

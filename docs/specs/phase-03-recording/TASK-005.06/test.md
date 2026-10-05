# 순차 Audio 업로드 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| UPLOAD-01 | local pending sequence 0,1,2; API-008 `[0,2]` | local 2는 제거, sequence 1만 전송 | orchestrator test |
| UPLOAD-02 | pending sequence 1,2,3; 정상 네트워크 | 순서대로 한 번에 하나씩 PUT | serial scheduler test |
| UPLOAD-03 | 유효 Blob/MIME | raw body, SHA-256, byte length, stable chunkId header | API client test |
| UPLOAD-04 | PUT 200 ACK sequence 1 | sequence 1만 IndexedDB에서 삭제 | repository integration test |
| UPLOAD-05 | PUT 성공했으나 응답 timeout; 서버에는 저장됨 | retry/restart 후 API-008 reconcile, 중복 없이 local 삭제 | lost response recovery test |
| UPLOAD-06 | network error 또는 5xx 연속 발생 | backoff 1/2/4/8/16초, 자동 시도 총 5회 후 정지 | fake timer retry test |
| UPLOAD-07 | 4xx validation/conflict | 자동 retry 0회, 오류 안내 유지 | classifier/orchestrator test |
| UPLOAD-08 | 최대 retry 뒤 수동 `재시도` | API-008부터 재조정한 뒤 missing sequence만 재개 | UI/controller test |
| UPLOAD-09 | API-008 또는 PUT `SESSION_NOT_FOUND` | 로컬 Chunk 보존, 복구 불가 안내, 자동 삭제 없음 | recovery error test |
| UPLOAD-10 | 일부 Chunk ACK, 다음 Chunk 실패 | ACK된 것만 삭제, 나머지는 pending | partial progress test |
| UPLOAD-11 | pending Chunk 하나 이상 남음 | processing handoff 차단, 실패/진행 안내 표시 | component integration test |
| UPLOAD-12 | 전부 ACK 후 completion callback | callback/navigation 정확히 한 번 | completion idempotency test |

Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행하고 결과를 기록한다.

## 통합/수동 QA

- 제한된 네트워크에서 업로드를 중단했다 다시 연결하고 API-008이 보고한 missing sequence만 재전송되는지 확인한다.
- Backend가 ACK 후 응답만 유실하는 상황을 만들어 재시작 시 중복 저장 없이 복구되는지 확인한다.
- API-008/007에서 Session 없음, 4xx conflict, 5xx를 각각 발생시켜 retry 여부와 안내를 확인한다.
- 전송 실패 중 새로고침 후 IndexedDB pending이 남고 완료될 때까지 처리 시작이 차단되는지 확인한다.
- VoiceOver/TalkBack과 키보드에서 진행/오류/수동 retry 안내가 이해 가능하고 반복 공지가 과도하지 않은지 확인한다.

# Processing Start API 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-009-01 | valid Session, sequences 0..N-1, 유효 duration/MIME/key | HTTP 202 `PROCESSING`/`AUDIO_ASSEMBLY`, enqueue 1회 | Controller/application test |
| API-009-02 | 빈 업로드, expectedChunks 1 | HTTP 409 `AUDIO_CHUNKS_INCOMPLETE`, enqueue 없음 | completeness test |
| API-009-03 | sequence 중간 누락 또는 마지막 외 sequence | HTTP 409, 작업/상태 변경 없음 | sequence set test |
| API-009-04 | expectedChunks가 저장 count와 다름 | HTTP 409 `AUDIO_CHUNKS_INCOMPLETE` | request/storage comparison test |
| API-009-05 | duration 0/음수 또는 maxMeetingDuration 초과 | HTTP 400 `VALIDATION_FAILED` | validation test |
| API-009-06 | 정책에 없는 MIME | HTTP 400, enqueue 없음 | uploadPolicy test |
| API-009-07 | Session 없음 또는 Backend restart 뒤 Session 소실 | HTTP 404 `SESSION_NOT_FOUND` | Session store test |
| API-009-08 | key 누락/공백, body 필드 누락/형식 오류 | HTTP 400 `VALIDATION_FAILED` | Controller validation test |
| API-009-09 | 같은 key/payload 직렬/동시 반복 | 같은 response/job ID, enqueue 1회 | idempotency concurrency test |
| API-009-10 | 기존 key로 다른 payload | HTTP 409 `IDEMPOTENCY_KEY_CONFLICT` | idempotency conflict test |
| API-009-11 | 허용 불가 Session lifecycle state | HTTP 409 `SESSION_STATE_CONFLICT`, 변경 없음 | state transition test |
| API-009-12 | queue 등록 실패 | 공통 안전 오류, 부분 처리 상태/중복 재실행 위험 없음 | failure atomicity test |
| API-009-13 | `CREATED` Session에서 새 처리 요청 | HTTP 409 `SESSION_STATE_CONFLICT`, enqueue 없음 | state transition test |
| API-009-14 | `PROCESSING` Session에 같은 key/payload 재요청 | 기존 response/job 재사용, enqueue 1회 | idempotency replay test |
| API-007-01 | `CREATED` Session에 첫 Chunk 저장 성공 | ACK 후 Session `UPLOADING`, version 1 증가 | upload API + API-010 state test |
| API-007-02 | 첫 Chunk 입력 검증 또는 저장 실패 | 실패 응답, Session은 `CREATED`/동일 version | upload failure state test |
| API-007-03 | `UPLOADING` Session에 후속 Chunk 저장 | ACK 성공, status/version 유지 | upload API state test |
| API-007-04 | `PROCESSING` Session에 새 Chunk 업로드 | HTTP 409 `SESSION_STATE_CONFLICT`, Chunk 미저장 | upload state guard test |
| API-009-15 | Chunk 업로드와 API-009 시작이 경쟁 | upload가 먼저 완료되면 sequence에 포함, process가 먼저 전이하면 upload 거절; PROCESSING 이후 추가 저장 없음 | per-session concurrency test |

`backend/`에서 PowerShell `.\gradlew.bat test --tests com.meetingautomation.ProcessingStartApiTests --tests com.meetingautomation.AudioChunkApiTests`를 실행하고 선택된 테스트의 결과를 기록한다. 전체 Backend suite와 `clean build`는 이 task의 일상적인 검증 명령으로 사용하지 않는다.

## 수동/통합 QA

- API-007으로 Chunk 전부 전송 후 API-009를 호출해 202와 초기 stage를 확인한다.
- API-010에서 첫 Chunk 이후 `UPLOADING`, API-009 이후 `PROCESSING` 상태와 version 변화를 확인한다.
- Chunk 하나를 누락시키고 API-009가 거절되며 작업이 생성되지 않는지 확인한다.
- 동일 Idempotency-Key 요청을 재호출해 processing job이 하나인지 확인한다.
- API-010 조회에서 API-009 시작 결과가 보이고 Session/queue 실패 내용 원문이 노출되지 않는지 확인한다.

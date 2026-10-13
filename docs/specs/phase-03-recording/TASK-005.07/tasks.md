# Processing Start API 작업 항목

1. Session state graph, upload policy, Chunk repository, queue/idempotency 기반을 확인한다. 첫 successful Chunk는 `CREATED → UPLOADING`으로 전이하며 API-009는 `UPLOADING`에서만 새 작업을 받는다. 완료 증거: 시작 가능한 선행 조건이 문서화된다.
2. 첫 Chunk 저장 성공/실패 전이, 처리 중 업로드 차단, upload/process 경쟁의 Session별 직렬화, request validation, sequence completeness, duplicate key, state conflict 테스트를 먼저 작성해 실패를 확인한다.
3. Session/Key/payload, expectedChunks/duration/MIME 검증을 구현한다. 완료 증거: 잘못된 요청은 side effect 전에 종료된다.
4. 저장된 sequence를 검증하고 `0..expectedChunks-1` 연속성과 크기/개수 한계를 확인한다. 완료 증거: missing/extra upload는 409다.
5. Idempotency-Key별 단일 processing command/job 등록을 구현한다. 완료 증거: 동시 같은 요청에서도 enqueue가 한 번이다.
6. Session application transition 및 HTTP 202 `AUDIO_ASSEMBLY` response를 연결한다. 완료 증거: API-010 조회에서 처리 시작이 보인다.
7. API-007의 첫 Chunk 저장 후 `UPLOADING` 전이와 `PROCESSING` 이후 업로드 guard를 연결한다. 완료 증거: API-010에 Session 상태가 반영되고 실패한 저장은 상태를 유지한다.
8. 실패 envelope/부분 enqueue 정리 및 TASK-005.07에 직접 관련된 Backend 테스트를 검증한다. 완료 증거: 선택한 테스트와 단일 side effect 결과가 기록된다.

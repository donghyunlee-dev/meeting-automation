# Chunk 저장 작업 항목

1. TASK-005.02 callback, stable `chunkId`, API-006 maxChunkBytes 및 Frontend 테스트 도구를 확인한다. 완료 증거: 입력/output 타입과 저장 adapter 위치가 계획에 반영된다.
2. Session/Chunk record, IndexedDB schema version, 복합 key 및 repository interface를 정의한다. 완료 증거: pending 조회와 ACK 삭제 계약이 독립적으로 호출 가능하다.
3. atomic append, 연속 sequence, 중복 호출, sorted recovery, ACK delete, quota/abort 테스트를 먼저 작성해 실패를 확인한다.
4. Session metadata를 초기화하고 단일 read-write transaction으로 Blob과 sequence를 저장한다. 완료 증거: commit 후에만 sequence 증가 결과가 노출된다.
5. 재시작 후 pending records와 next sequence 복구, ACK된 Chunk 삭제와 empty metadata cleanup을 구현한다. 완료 증거: reload 전후 데이터와 sequence가 동일하게 이어진다.
6. 빈/초과 Chunk, browser storage quota, open/transaction 실패를 매핑한다. 완료 증거: 저장 실패가 성공 ACK로 오인되지 않는다.
7. 저장 adapter를 TASK-005.02 recorder callback에 연결하고 저장 결과를 전달한다. 완료 증거: 여러 dataavailable 이벤트가 순서대로 serialize된다.
8. 전체 테스트 및 `npm run lint`, `npm run build`를 실행한다. 완료 증거: 검증 명령 결과와 보유/삭제 정책을 리뷰 기록에 남긴다.

# Chunk 임시 저장 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| CHUNK-STORE-01 | 새 Session에 첫 Chunk 저장 | sequence 0, metadata nextSequence 1 | IndexedDB repository test |
| CHUNK-STORE-02 | 같은 Session에 연속 Chunk 두 개 | sequence 0,1 순서로 저장 | append sequence test |
| CHUNK-STORE-03 | 동시에 도착한 여러 callback | 중복 없이 연속 sequence 할당 및 callback 순서 보존 | serialized append/transaction test |
| CHUNK-STORE-04 | 같은 `(sessionId, chunkId)` append 재호출 | 기존 sequence 결과 반환, Chunk/sequence 추가 없음 | idempotency test |
| CHUNK-STORE-05 | transaction 중단 | Chunk 및 sequence 증가 모두 반영되지 않고 저장 실패 | abort atomicity test |
| CHUNK-STORE-06 | pending query | Session별 sequence 오름차순 Blob·MIME·timestamp 반환 | repository query test |
| CHUNK-STORE-07 | DB를 닫고 재개방한 뒤 pending query | 미전송 Chunk와 nextSequence 복구 | IndexedDB reopen test |
| CHUNK-STORE-08 | sequence 1 ACK | 해당 record만 제거되고 나머지 유지 | acknowledge/delete test |
| CHUNK-STORE-09 | 마지막 pending Chunk ACK | Session metadata도 정리 | empty session cleanup test |
| CHUNK-STORE-10 | 빈 Blob 또는 maxChunkBytes 초과 Blob | 유효하지 않은 저장 오류, record 미생성 | validation test |
| CHUNK-STORE-11 | quota exceeded 또는 IndexedDB open 실패 | 구분 가능한 저장 실패, success ACK 없음 | storage error mapping test |
| CHUNK-STORE-12 | Session ID가 Backend에서 유효하지 않다고 후속 소비자가 알림 | 로컬 Chunk는 유지되고 복구 불가 결과를 표시할 데이터 반환 | recovery metadata contract test |

프로젝트 Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행하고 결과를 기록한다.

## 수동 브라우저 QA

- 녹음을 진행해 Chunk가 IndexedDB에 sequence 순서대로 쌓이는지 확인한다.
- 같은 origin의 탭을 닫거나 새로고침한 뒤 저장소를 다시 열어 미전송 Chunk와 next sequence를 확인한다.
- ACK된 Chunk만 제거되고 아직 ACK되지 않은 Chunk는 남는지 확인한다.
- 브라우저 저장 데이터 삭제 또는 quota 제한 시 저장 실패 안내가 나타나며 녹음 손실을 복구 성공으로 표시하지 않는지 확인한다.

## 한계 확인

동일 origin/browser profile 영속성만 확인한다. IndexedDB를 삭제·정리한 경우와 Backend 프로세스 재시작으로 memory-only Session이 사라진 경우는 복구할 수 없으며, 이 한계를 제품 오류 안내가 숨기지 않는지 확인한다.

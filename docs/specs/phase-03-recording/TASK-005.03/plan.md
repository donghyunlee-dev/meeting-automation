# Chunk 임시 저장 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-005`, `DEC-020`, `TASK-005.03`
- 선행 TASK-005.02 Issue [#20](https://github.com/donghyunlee-dev/meeting-automation/issues/20): MediaRecorder가 `{chunkId,blob,mimeType,recordedAtMs}` 전달
- 본 TASK Issue [#21](https://github.com/donghyunlee-dev/meeting-automation/issues/21)
- 후속 TASK-005.06: 저장소의 pending 조회/ACK 삭제를 사용해 전송 재시도
- API 계약: API-006 `uploadPolicy.maxChunkBytes`; API-007 업로드 sequence 및 ACK

## 변경 경계

- Frontend `ChunkRepository`: IndexedDB open/schema, atomic append, ordered pending read, ACK delete, recoverable-session metadata query
- Frontend `ChunkRecorderAdapter`: MediaRecorder callback을 직렬화해 Repository에 저장하고 저장 성공/실패 결과를 controller에 전달
- 테스트: fake IndexedDB 또는 프로젝트 테스트 환경의 IndexedDB adapter, transaction/quota 실패 대역
- API/Backend 및 upload scheduler 변경 없음. UX에서 복구 세션을 노출하는 통합은 후속 TASK-005.06 범위로 전달한다.

## 구현 순서

1. Frontend scaffold와 TASK-005.02 Blob callback에서 안정적 `chunkId`, 녹음 길이/Session ID/upload policy 계약을 확인한다. 결과: repository 입력 타입이 확정된다.
2. object store/key/index/schema version 및 browser storage adapter를 정의한다. 결과: IndexedDB 구현과 테스트 대역이 같은 interface를 사용한다.
3. 연속 순번, atomic append, duplicate call, pending order, reload, ACK delete 및 실패 테스트를 먼저 작성한다. 결과: 요구사항별 실패 사례가 고정된다.
4. Session 초기화와 transaction 내 Blob+sequence+metadata append를 구현한다. 결과: commit 성공 때만 다음 sequence와 성공 결과가 보인다.
5. pending 복구 query, acknowledge/delete, empty metadata cleanup을 구현한다. 결과: 후속 upload가 sequence 순서대로 안전하게 처리할 수 있다.
6. max chunk bytes/빈 Blob/quota/DB 오류 mapping 및 caller handoff를 구현한다. 결과: 저장 실패를 사용자 안전 동작과 연결할 수 있다.
7. 테스트, lint, build를 실행하고 데이터 보유·오류·후속 소비 계약을 검토한다. 결과: 아래 자동화 증거 및 manual QA가 기록된다.

## 검증 명령

Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행한다. IndexedDB 영속성은 자동화 테스트 후 브라우저 reload/강제 종료 QA로 확인한다.

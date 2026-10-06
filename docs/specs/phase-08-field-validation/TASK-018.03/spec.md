# Chunk 복구·재전송 현장 검증

## 목표

네트워크 단절, 응답 유실, 새로고침/브라우저 재실행 후에도 동일 origin의 IndexedDB pending Chunk와 API-008 수신 목록을 대조해 누락 sequence만 복구하는지 실제 Android Chrome 및 iOS Safari에서 검증한다. 계약을 지키는지 확인하는 QA task이며 업로더 동작이나 저장 구조를 새로 정의하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-018.03`, `FR-005`, `NFR-007`, `API-007`, `API-008`, `DEC-020`, PRD Section 21을 구체화한다. Issue [#67](https://github.com/donghyunlee-dev/meeting-automation/issues/67).

## 범위

- 실제 모바일 브라우저에서 API-008을 먼저 조회하고 서버 수신 sequence를 local pending과 대조하는 과정 관찰
- 아직 서버에 없는 pending Chunk를 0부터의 numeric sequence 오름차순, Session당 동시 요청 1개로 보내는지 검증
- PUT가 서버에서 수락됐으나 응답이 유실된 경우, 동일 `chunkId`/body의 재시도와 API-008 재조정 결과 검증
- timeout/network, 408, 429, 5xx의 제한 backoff 및 수동 재시도와 4xx 비자동 재시도 구분
- 중간 ACK/transaction 오류, 페이지 reload/reopen, 연결 복구 후 완료/종료 차단 조건 검증
- synthetic Chunk의 sequence, `chunkId`, SHA-256, MIME, byte length를 client/server metadata와 대조
- Android 결과와 iOS 결과, 비민감 Evidence 및 fixture 정리 기록

## 비범위

- IndexedDB schema/생성/저장 동작 구현 (`TASK-005.03`)
- API-007/008 backend validation 또는 idempotency 구현 (`TASK-005.04`, `TASK-005.05`)
- FE uploader retry/화면 기능 구현 (`TASK-005.06` 및 `TASK-018.04`)
- API-009 처리 시작 구현 (`TASK-005.07/.08`); 여기서는 pending이 남은 경우 handoff가 차단되는지 관찰
- Backend Session 재시작 또는 저장소가 사용자의 설정/브라우저 eviction에 의해 제거된 뒤의 복구 보장
- 실제 회의 Audio·Transcript·Secret·개인정보를 QA에 사용하는 것

## 고정된 업로드 계약

TASK-005.03~005.06의 기존 계약을 시험 oracle로 사용한다.

- 같은 origin의 유효 Session은 IndexedDB pending을 유지한다. 명시 ACK 이전에는 local Chunk를 제거하지 않는다.
- 재개 시 API-008의 `receivedSequences`를 먼저 조회한다. 서버에 이미 있는 로컬 sequence는 원격 확인 후 local에서 정리하고, API-008에 없는 local Chunk만 전송한다.
- 전송은 Session 내 numeric sequence 오름차순이며 한 번에 요청 하나만 처리한다. PUT에는 저장된 binary body/MIME, SHA-256, byte length, 고정 `chunkId` idempotency key가 포함된다.
- HTTP 200과 반환 sequence/`received:true` 일치 뒤 해당 sequence만 삭제한다. 응답이 사라지면 동일 ID/body 재시도 또는 API-008 조정으로 결과를 확인한다.
- network/timeout/408/429/5xx는 기존 bounded policy의 최대 5회 retry delay 1/2/4/8/16초를 따르고, 반복 실패 뒤 수동 재시도를 제공한다. 4xx validation/conflict/session 오류는 자동 반복하지 않는다.
- pending이 하나라도 남으면 upload complete 또는 API-009 Processing handoff로 넘어가지 않는다. `SESSION_NOT_FOUND`, quota 초과, IndexedDB transaction 실패, 사용자가 저장소를 제거한 경우는 복구 성공이 아니며 silent delete/성공 처리하지 않는다.

## 수용 기준

- 합성 fixture의 local/server sequence 목록 차이가 계산되고 서버에 이미 수신된 Chunk는 재전송되지 않는다.
- 서버에 없는 local Chunk가 오름차순으로 한 개씩 전송되며 sequence별 HTTP ACK 전에는 local copy가 남는다.
- 응답 유실 뒤 재시도가 같은 `chunkId`와 동일 hash/byte를 사용하고 server에는 sequence당 하나의 수신 결과가 남는다.
- retryable 오류의 retry 횟수/delay가 기존 정책과 일치하고 이후 수동 retry가 동작한다. 4xx는 자동 반복하지 않는다.
- 실패 중에는 미확인 Chunk가 보존되고 upload complete/API-009가 발생하지 않는다. 모두 원격 확인된 경우에만 완료 callback/handoff가 진행된다.
- 브라우저 재시작 후 동일 origin/유효 Session에 남은 pending과 next sequence가 복구된다. Session/storage가 사라진 조건은 실패로 표시되고 자동 폐기되지 않는다.
- Android Chrome 및 iOS Safari에서 같은 fixture 의도로 run을 각각 수행하고 차이/재현 조건을 기록한다.
- Evidence에는 오디오/Transcript/Secret/개인정보/인증 header가 없고 synthetic test payload와 QA object가 종료 후 정리된다.

## 전제 및 근거

- Browser persistence는 동일 origin과 브라우저 정책의 범위에서만 검증한다. IndexedDB 완료 이벤트나 현재 API 결과를 기기 저장장치의 영구 보존 보장으로 확대 해석하지 않는다.
- [IndexedDB 사용 — MDN](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API/Using_IndexedDB): 브라우저 종료 시 transaction 중단 가능성과 unload 처리에 의존하지 않는 기준을 확인한다.
- [Storage quota와 eviction — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria): quota 초과/eviction은 환경별 저장 정책이며 테스트는 제품 보장으로 약속하지 않는다.
- [IndexedDB transaction `complete` — MDN](https://developer.mozilla.org/en-US/docs/Web/API/IDBTransaction/complete_event): `complete`는 IndexedDB transaction commit 확인으로 사용하며 장치 물리 저장장치 보장으로 해석하지 않는다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [IndexedDB 계약](../../phase-03-recording/TASK-005.03/spec.md)
- [Upload retry 계약](../../phase-03-recording/TASK-005.06/spec.md)
- [Android 현장 기준](../TASK-018.01/test.md)
- [iOS 현장 기준](../TASK-018.02/test.md)

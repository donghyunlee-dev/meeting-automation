# 검증 계획

## 선행 조건

- TASK-005.03 Issue #21: IndexedDB sequence/Chunk 저장, ACK 삭제, quota/transaction 실패 결과
- TASK-005.04 Issue #22 및 TASK-005.05 Issue #23: API-007 PUT, API-008 수신목록 계약
- TASK-005.06 Issue #24: reconcile, 직렬 업로드, idempotency, backoff, 수동 retry, handoff 차단
- TASK-018.01 Issue #65: Android Chrome 실기기 조합
- TASK-018.02 Issue #66: iOS Safari 실기기 조합
- PRD v1.8.1, `FR-005`, `NFR-007`, `DEC-020`, Section 21

## 환경과 책임

| 대상 | 소유자 | 확인 결과 |
|---|---|---|
| synthetic fixture와 기대값 | QA + FE | sequence별 고유 `chunkId`, MIME, byte length, SHA-256 및 기대 수신 집합 |
| API 오류/응답 유실 주입 | QA + BE/플랫폼 | non-production 전용 fault proxy 또는 기존 API contract test harness로 요청/응답 장애를 재현 |
| Browser IndexedDB | FE + QA | pending/ACK 삭제/재실행 복구 및 quota/transaction 오류 결과 |
| API-007/008 상태 | BE + QA | server received sequence, byte count/hash, idempotent retry 집계 |
| 사용자 상태/마무리 | FE + QA | retryable/terminal 실패 안내, 수동 retry 및 API-009 handoff gate |
| 정리/Evidence | QA | 합성 데이터·격리 Audio object 제거와 비민감 결과 기록 |

이 작업은 필드 검증만 수행한다. 오류를 안정적으로 주입할 non-production 경로가 준비되지 않으면 제품 API를 수정하지 않고 실행 setup을 별도 blocker로 기록한다. 성공/실패를 입증할 수 없는 행은 완료 판정하지 않는다.

## 구현/실행 순서

- 고정된 앱/API build, Android/iOS 기기, Safari/Chrome 버전, MIME, upload policy와 Session expiry를 기록한다. PWA/in-app browser는 해당 실행에서 제외한다.
- 콘텐츠가 없는 결정적 합성 Chunk 집합을 준비한다. 각 Chunk는 fixture run 식별자, sequence별 고유 `chunkId`, 알려진 크기/hash를 가진다. raw payload는 증거로 저장하지 않는다.
- 준비 API/기존 QA harness에서 local pending과 server received 목록을 설정한다. 재현 결과는 시작 시퀀스 집합을 기준으로 계산하고, fault proxy는 비운영 환경에서만 사용한다.
- 서버 수신 일부/로컬 보유 일부/이미 ACK된 일부를 섞고 동기화해 서버 수신 Chunk가 건너뛰고 missing만 직렬 전송되는지 관찰한다.
- PUT 수락 후 response drop, before-acceptance disconnect, 408/429/5xx, 4xx validation을 각각 단독 실행한다. 오류 run을 서로 섞지 않는다.
- 자동 retry가 끝난 케이스에서 사용자가 수동 재시도한다. 새로고침/브라우저 종료 뒤 동일 origin 복귀도 별도 run으로 수행해 API-008부터 재조정하는지 확인한다.
- quota/transaction 실패와 synthetic session의 `SESSION_NOT_FOUND`를 분리 실행한다. 실패 시 local data 보존/안내/업로드 complete 차단 여부를 확인하고 storage가 사용자가 삭제된 조건은 복구 보장 대상이 아님을 기록한다.
- 전 run에서 처리 시작 gate를 관찰한다. pending 또는 미확인 server receipt가 남으면 handoff가 차단되고, 모두 원격 확인된 후에만 후속 API-009 처리가 가능해야 한다.
- Android와 iOS 각각에 대해 API-008 목록, local pending, PUT 순서/횟수, 최종 server 목록, byte/hash 대조를 기록한다. 차이가 나면 브라우저/OS/환경을 바꾸지 않고 한 번 재현한다.
- 완료 시 QA prefix/fixture object 및 IndexedDB fixture Session을 정리한다. cleanup이 실패하면 격리 환경에서 삭제하고 비민감 상태를 기록한다.

## 계약과 소유권

- 재시도 횟수, delay, terminal 오류 구분은 TASK-005.06과 일치시킨다. 이 문서에서 기존 구현을 덮는 새로운 retry 알고리즘을 발명하지 않는다.
- 브라우저 저장소 실제 재기동 내구성은 IndexedDB 전체 store의 sequence/hash 대조로 관찰한다. unload handler에서 write/flush가 발생할 것이라고 가정하지 않는다.
- API-007/008 wire contract, `chunkId`, body hash/byte length는 TASK-005.03~.06의 정의를 그대로 사용한다.
- 이번 작업은 새 production logging/telemetry/API를 추가하지 않는다. fault injection과 server inventory는 접근 통제된 비운영 QA 기능으로 제한한다.
- 네트워크 outage 후 sequence-level 복구 결과를 `docs/evidence/TASK-018.03.md`에 기록하고, 구현 결함은 TASK-018.04 또는 별도 버그 Issue로 연결한다.

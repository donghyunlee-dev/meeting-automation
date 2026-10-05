# 순차 Audio 업로드와 재시도 복구

## 목표

IndexedDB pending Chunk를 API-007로 순서대로 보내고 API-008의 실제 수신 목록과 조정해 네트워크 중단·브라우저 재시작에서 누락된 Chunk만 복구한다. Recording 화면에 진행·실패와 가능한 재시도를 표시한다. PRD v1.7.0 (2026-10-05), `FR-005`, `NFR-007`, `SCR-003`, `API-007`, `API-008`, `TASK-005.06`을 구체화한다. Issue [#24](https://github.com/donghyunlee-dev/meeting-automation/issues/24).

## 범위

- TASK-005.03 IndexedDB에서 Session별 pending Chunk를 sequence 오름차순 조회
- API-008 조회 결과로 서버 수신/로컬 pending을 조정하고 이미 ACK된 로컬 Chunk 삭제
- API-007 순차 PUT, request hash/length/MIME/header 생성, 성공 ACK 뒤 로컬 삭제
- 일시적 네트워크/5xx 실패 bounded retry 및 사용자 수동 재시도
- Recording/Processing 전환 전 미전송 Chunk 차단, 진행/실패 UI 제공
- 브라우저 reload 뒤 다시 조정하고 missing sequence만 전송

## 비범위

Chunk 생성·IndexedDB schema(`TASK-005.03`), Backend upload/status API(`TASK-005.04/005.05`), 처리 시작 API-009(`TASK-005.07/005.08`), Session backend 영속화, 브라우저 간 저장소 동기화는 포함하지 않는다.

## 전송 계약

- 업로드 시작/재개 시 API-008을 먼저 조회한다. `receivedSequences`와 local pending을 비교한다. 서버 수신 목록에 있는 local Chunk는 이전 응답 ACK 뒤 브라우저가 종료된 경우로 보고 로컬에서 제거한다.
- API-008에 없는 local pending Chunk만 numeric sequence 오름차순으로 전송한다. 한 Session에서는 동시에 한 요청만 보내며 다음 요청은 현재 요청의 성공 ACK 이후 시작한다.
- 각 PUT은 raw Blob body, 저장된 MIME, Web Crypto SHA-256 lowercase hex, 실제 byte length 및 `Idempotency-Key: chunkId`를 보낸다. timeout 후 같은 sequence 재시도는 동일 chunkId/body를 유지한다.
- HTTP 200 및 응답 sequence 일치 `{received:true}`를 확인한 뒤 그 Chunk만 IndexedDB에서 삭제한다. 응답 유실 뒤 재시도는 API-007 멱등 ACK와 API-008 조정으로 안전하게 복구한다.
- timeout/network error, 408, 429, 5xx는 자동 재시도 대상이다. 4xx validation/conflict/session-not-found는 자동 재시도하지 않는다. 5회까지 지수 backoff 1s, 2s, 4s, 8s, 16s를 적용하고 이후 실패 상태와 수동 `재시도` action을 제공한다. 수동 재시도는 API-008을 다시 조회한다.
- API-008 또는 업로드가 `SESSION_NOT_FOUND`이면 자동으로 Chunk를 지우지 않는다. 로컬 오디오 복구가 불가능할 수 있음을 안전한 안내로 표시하고 사용자가 데이터 처리 방법을 결정하기 전까지 pending을 보존한다.
- 진행률은 전체 local Chunk 수 대비 이번 동기화에서 ACK/remote-confirm된 수로 계산한다. 미전송 Chunk가 남아 있으면 Processing 시작을 허용하지 않는다. 모든 pending이 정리된 뒤 후속 TASK가 API-009를 호출한다.
- recording Session view 화면에서 상태/오류를 표시하고 timer tick과 진행 상태를 screen reader가 과도하게 반복 읽지 않도록 한다. 일반 로그에 raw Audio, Auth/header, request body를 남기지 않는다.

## 수용 기준

- 업로드는 한 Session 내 sequence 오름차순 직렬이며 API-008에서 이미 수신된 sequence는 재전송하지 않고 local copy를 정리한다.
- 각 200 ACK 뒤 해당 sequence만 로컬에서 지워지고 다른 pending Chunk는 그대로 남는다.
- 네트워크/5xx 실패는 최대 5회 재시도 뒤 멈추고 수동 재시도를 표시한다. 4xx는 자동 재시도하지 않는다.
- timeout/응답 유실 뒤 재실행은 API-008과 API-007 멱등 동작으로 중복 업로드 없이 복구한다.
- pending이 하나라도 있으면 처리 시작으로 진행하지 않고 정확한 진행/실패 안내를 제공한다.
- Session 부재, IndexedDB 삭제/저장 오류를 성공으로 표시하거나 로컬 Chunk를 자동 폐기하지 않는다.
- 모든 Chunk ACK 이후에만 upload complete callback/navigation이 발생한다.

## 결정 및 전제

PRD의 “누락 Chunk만 재전송”은 API-008 실제 수신 sequence 및 TASK-005.03 로컬 pending 교집합/차집합 조정으로 구현한다. 제한된 5회 exponential backoff와 이후 manual retry를 기본 재시도 정책으로 정해 무한 자동 호출을 피한다. Backend process restart 후 Session 부재 복구는 API/저장소 전제상 보장되지 않는다.

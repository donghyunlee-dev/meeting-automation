# Speaker mapping 저장 및 Transcript 반영 통합

## 목표

Review UI의 speaker-keyed mapping draft를 API-011에 저장하고 성공한 mapping을 해당 Speaker의 모든 Transcript segment 표시에 일관되게 반영한다. 실패 시 기존 저장 snapshot과 편집 draft를 보존한다. PRD v1.7.0 (2026-10-05), `DEC-010`, `API-011`, `TASK-007.03`을 구체화한다. Issue [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36).

## 범위

- TASK-007.02 Review draft와 API-011 full replacement request 연결
- API-010의 현재 Session version을 인용한 `If-Match` 저장
- API-011 성공 응답의 새 version 및 전체 mappings를 UI/query cache에 원자 반영
- Speaker ID join을 이용한 해당 Speaker의 모든 Transcript segment 표시 갱신
- 저장 중 중복 submit 차단, 저장 오류/412/network timeout 복구
- API-010 read-after-write와 안전한 draft/server snapshot 관리
- App Config의 Provider 미선택/미설정 때 기존 Settings 안내를 보존

## 비범위

새 API/endpoint, 자동 diarization/실명 매핑, Transcript 원문 저장 변경, Minutes 저장/확정, 개별 segment mapping, 사용자 draft의 영구 저장, Participant 생성·수정은 포함하지 않는다.

## 통합 계약

- Clean draft는 API를 호출하지 않는다. 변경이 있고 `UPDATE_SPEAKER_MAPPING`이 `allowedActions`에 있을 때 Save 동작을 활성화한다. 미저장 mapping을 Confirm으로 제출할 수 없도록 Review action gating을 유지한다.
- Save 시 `{mappings:[{speakerId,participantId}]}` 전체 replacement set을 정확히 한 번 전송하고, API-010 snapshot의 `version`을 `If-Match: "<version>"`으로 보낸다. mapping은 API-011 계약처럼 모든 감지 Speaker를 한 번씩 포함한다.
- 요청 중 중복 submit을 막고 Dropdown draft를 변경하지 못하게 하거나 변경 값을 별도 draft로 계속 추적한다. 응답이 현재 편집과 다르게 섞이지 않도록 요청 snapshot과 현재 draft를 분리한다.
- HTTP 200 성공이면 response `version`과 full `mappings`를 committed base로 교체하고 draft를 비운다. API-010 cache의 Session version 및 Speaker `participantId`를 같은 commit으로 갱신한다. mapping 이름은 API-011 response `participantName`을 사용한다.
- Transcript segment에는 Participant를 별도 저장하지 않는다. 기존 `speakerId` 참조를 그대로 두고 view model에서 최신 speaker mapping의 Participant 이름을 join한다. 그러므로 하나의 mapping 결과는 해당 Speaker의 모든 segment에서 같은 이름으로 보인다.
- 성공 뒤 API-010을 재조회하거나 cache invalidation해 authoritative Session snapshot과 `allowedActions`를 동기화한다. 재조회가 늦어도 이미 확인된 API-011 성공 mapping을 이전 값으로 되돌리지 않는다.
- 400/404/409/502 응답은 safe common error로 표시하고 last committed mapping과 unsaved draft를 보존한다. API error 원문은 표시하지 않는다.
- HTTP 412 `SESSION_VERSION_CONFLICT`이면 API-010 최신 snapshot을 조회해 committed base만 갱신하고 사용자 draft를 보존한다. 덮어쓰기 자동 retry를 하지 않는다. 사용자에게 최신 결과 확인 후 draft를 다시 저장하도록 안내한다.
- PUT 전송 결과가 네트워크 오류로 불명확하면 자동으로 같은 PUT을 반복하지 않는다. 먼저 API-010을 GET하고 최신 Speaker→Participant IDs가 제출 snapshot과 같으면 저장 성공으로 reconcile한다. 다르면 최신 committed base를 갱신하되 제출 draft는 보존하고 사용자에게 재확인/명시적 재저장을 요청한다. GET도 실패하면 양쪽 snapshot/draft를 지우지 않고 조회 재시도 안내를 제공한다.
- `document.provider=null` 또는 `configured=false`이면 TASK-007.02의 SCR-011 Settings 안내를 유지하고 저장 호출을 시도하지 않는다. Provider를 자동 선택하지 않는다.
- `SESSION_NOT_FOUND`는 task00607의 Review 이탈/새 회의 복구 안내를 사용한다. 매핑을 부분 적용하지 않는다.

## 수용 기준

- 변경된 전체 draft만 현재 API-010 version으로 API-011에 한 번 제출되며 중복 클릭으로 추가 요청이 생기지 않는다.
- 성공 시 server version/full mapping 및 모든 같은 Speaker의 Transcript 표시가 함께 갱신되고 이전 version으로 되돌아가지 않는다.
- 저장 실패는 기존 committed mapping을 유지하고 draft를 잃지 않으며 안전한 안내를 제공한다.
- 412는 최신 base와 사용자 draft를 보존하고 무조건 덮어쓰기/재전송을 하지 않는다.
- 네트워크 응답 유실은 API-010 read-after-write로 reconcile하며 결과 미확인 때 자동 PUT 반복이 없다.
- 동일 Speaker를 참조하는 모든 Transcript segment 표시가 같은 Participant 이름을 사용하며 Transcript ID/time/text 원문은 변경되지 않는다.
- clean form은 불필요한 API 호출이 없고 Provider 미선택/미설정이면 Settings 경로를 유지한다.

## 결정 및 전제

TASK-007.01 API-011의 전체 replacement, optimistic version 및 atomic response 계약과 TASK-007.02의 speakerId-keyed local draft 계약을 연결한다. 불명확한 PUT 결과는 멱등성 key가 없는 API-011을 blind retry하지 않고 API-010으로 먼저 확인한다. API-010/011이 제공하는 version과 mapping이 최종 source of truth다.

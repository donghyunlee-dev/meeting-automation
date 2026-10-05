# Review Template 선택 및 Minutes 재생성 UI

## 목표

SCR-006 Review에서 API-002 Template 목록 중 하나를 고르고 사용자가 명시적으로 확인한 뒤 API-013으로 Minutes 재생성을 시작한다. 재생성 중 화면 상태를 안전하게 표시하고 API-010의 완료 snapshot에 따라 새 결과 또는 복구된 이전 Minutes를 보여준다. PRD v1.7.0 (2026-10-05), `SCR-006`, `FR-013`, `FR-014`, `API-002`, `API-010`, `API-013`, `TASK-009.02`를 구체화한다. Issue [#41](https://github.com/donghyunlee-dev/meeting-automation/issues/41).

## 범위

- API-002 template metadata 조회와 API-010 현재 Template 연결
- pending Template 선택, 변경 취소, 명시적 재생성 확인
- 미저장 Minutes draft 보존을 위한 저장 선행 처리
- API-013 `If-Match`/`Idempotency-Key` 요청 및 API-010 결과 polling
- API-010 terminal Review snapshot/version-aware cache 반영
- 실패 rollback 결과와 안전한 사용자 안내
- loading/error/empty state, 화면 이탈/복귀, keyboard/mobile 접근성

## 비범위

Backend API 구현·실패 rollback (`TASK-009.01`), STT/Diarization 0회 회귀 검증 및 전체 통합 테스트 (`TASK-009.03`), Template 생성/편집/삭제, Transcript 또는 Speaker mapping 수정, API-012 저장 contract 자체, Provider/credential 설정, Meeting 확정/공유 기능은 포함하지 않는다.

## 화면 상태와 Template 선택

- Review 진입 시 API-010의 current `minutes.templateId`와 API-002 `items[{id,name,version}]`를 결합한다. API-002 metadata의 id/version과 current `minutes.templateId/templateVersion`은 각각 식별/표시한다.
- selector는 current Template을 선택값으로 초기화한다. 다른 항목 선택은 화면의 pending selection만 변경하고 서버 상태나 Minutes를 바꾸지 않는다. current Template을 다시 선택하면 변경 없음으로 취급한다.
- Template 목록 로딩 중에는 재생성 action을 비활성화한다. API-002 오류 시 현재 Minutes 편집은 유지하고 Template 영역에 안전한 오류와 `다시 불러오기`를 표시한다. 요청이 성공하기 전까지 목록의 일부/임의 기본값을 선택하지 않는다.
- current Template이 응답 목록에 없으면 현재 id/version을 읽기 전용으로 보존하고 선택 가능 Template을 확인할 수 없다는 안내를 표시한다. 유효한 다른 API-002 item을 명시 선택하기 전까지 재생성을 막는다.
- `allowedActions`에 `REGENERATE_MINUTES`가 없거나 Session이 `REVIEW`가 아니면 action을 실행하지 않고 최신 Session 상태를 다시 조회한다. `allowedActions`는 UI gate이며 Backend API 검증을 대신하지 않는다.

## 명시적 확인과 편집 draft 보존

- pending Template이 current와 다르고 API-002가 유효할 때 `새 Template으로 회의록 재생성` action을 노출한다.
- 실행 전 confirmation dialog에 현재/선택 Template 이름과 “현재 회의록이 새 결과로 교체됩니다. Transcript는 유지됩니다.”를 설명하고 `재생성`/`취소`를 제공한다. 취소/ESC/backdrop close는 pending selection을 current로 되돌리고 API를 호출하지 않는다.
- Minutes draft가 server base와 다르면 확인 후 API-012 저장을 먼저 완료한다. UI는 저장 snapshot/최신 version이 API-010 read-after-write로 확정되기 전 API-013을 호출하지 않는다. 이 연속 작업 동안 편집/confirm 중복 입력을 잠그되 draft는 보존한다.
- 저장 오류, version conflict, 응답 결과 불명확 또는 저장 중 route 이탈 시 API-013을 보내지 않는다. TASK-008.03의 base/draft reconciliation을 유지하고 Template pending selection을 보존해 사용자가 다시 저장/검토하도록 한다.
- clean draft라면 확인 뒤 즉시 API-013을 요청한다. 저장 후 재생성을 연속 실행한 경우에도 사용자의 앞선 확인이 해당 pending Template에 대한 명시적 동의로 간주된다.

## API 호출과 처리 중 화면

- POST body는 `{templateId: pendingTemplate.id}`다. `If-Match`는 저장 완료 후 확정된 현재 API-010 `data.version`; `Idempotency-Key`는 새 사용자 의도마다 새로 만든 opaque key다.
- 동일 사용자 의도의 전달 결과가 불명확하면 새 key로 재전송하지 않는다. 먼저 API-010을 조회한다. 동일 Session의 `PROCESSING`/`DRAFT_REGENERATION`이면 이미 수락된 요청으로 취급해 polling한다. `REVIEW`가 기준 version과 같으면 같은 body/key를 재사용할 수 있다. terminal version이 기준 version보다 높으면 결과 snapshot을 검증한다.
- `202` 이후 Review의 edit/save/confirm/다른 재생성 action을 비활성화하고 같은 route에 blocking `Minutes 재생성 중` 화면을 표시한다. Session이 처리 중이므로 API-010이 숨기는 Minutes/Transcript partial data를 새로 읽거나 표시하지 않는다. 진행률은 API-010에 유효 progress가 있을 때만 표시하고, 없으면 단계 문구만 보여준다.
- API-010 polling은 TASK-006.07 정책을 재사용한다: 수락 후 즉시 조회, 처리 중이면 1초 간격; 연속 조회 오류는 2/4/8초로 늘리고 최대 10초로 제한; 한 번에 하나의 GET만 수행한다. Review terminal, unmount/route 이탈에서 timer와 GET을 취소한다. polling 취소는 Backend regeneration을 중지하지 않는다.
- 화면이 처리 중 unmount된 경우 server 작업은 계속된다. 같은 Review로 돌아오면 API-010 current status를 기준으로 진행/terminal outcome을 복원한다. local state가 없으면 Review가 아닌 처리 중 Session에 partial 데이터를 만들지 않는다.

## 완료/실패 반영

- 현재 sessionId와 일치하고 version이 regeneration 요청의 기준 version보다 높은 API-010 snapshot만 최종 반영한다. `status=REVIEW`와 `lastOperation.type=MINUTES_REGENERATION`, `requestedTemplateId=pendingTemplate.id`를 함께 확인한다.
- `lastOperation.outcome=SUCCEEDED`면 최신 snapshot의 minutes/templateId/templateVersion, speakers, transcript, version, allowedActions를 한 번에 cache/form base에 반영하고 pending selection을 성공한 current Template으로 정렬한다. 새 Minutes가 보인다는 성공 안내를 제공한다.
- `lastOperation.outcome=FAILED`면 TASK-009.01이 복구해 반환한 기존 Minutes/provenance와 일치하는 API-010 snapshot을 적용한다. pending Template을 이전 current 값으로 돌리고, 요청 Template이 적용되지 않았으며 기존 편집본이 복구됐다는 안전한 안내와 재선택 경로를 제공한다.
- Provider detail/response, Transcript/Minutes 원문은 error message, console, telemetry에 넣지 않는다. API-010 404/세션 이탈, 일반 `PROCESSING_FAILED`, 잘못된/불일치 `lastOperation`은 일반 regeneration success/failure로 추측하지 않고 safe recovery 안내 및 재조회 경로를 제공한다.
- API-013 400/409/412 등 수락 전 거절은 review form/base와 pending selection을 유지한다. 412는 API-010 latest를 조회하고 사용자가 다시 확인한 최신 version에서만 새 의도를 시작한다. 실패 POST를 자동으로 다른 key로 재전송하지 않는다.

## 수용 기준

- Template list/current Template이 id 기준으로 일관되게 초기화되고 선택/원복이 Minutes나 서버에 부수 효과를 주지 않는다.
- 목록 로딩/오류/누락 상태에서 잘못된 Template이나 기본값을 임의 요청하지 않는다.
- 사용자 확인 전 API-013 호출 횟수는 0이다. 확인 취소도 API mutation을 만들지 않는다.
- unsaved Minutes draft는 API-012 저장 및 API-010 read-after-write 성공 후에만 재생성으로 이어지며 저장이 실패하면 draft와 pending Template이 남고 API-013 호출은 0이다.
- 유효 요청은 최신 version `If-Match`와 의도별 idempotency key로 한 번 수락된다. 단일 polling, error backoff, route 이탈 취소를 지킨다.
- 성공은 새 Minutes/Template/version을 일관되게 반영한다. 실패는 기존 저장 Minutes 전체/provenance 복구 snapshot, current Template 선택 및 안전 안내를 표시한다.
- 결과 불명확/412/세션 이탈에서 draft를 덮거나 regeneration POST를 blind retry하지 않는다.
- 360px viewport, 키보드 전용, dialog focus return, screen reader label/live status와 reduced motion에서 사용 가능하다.
- 로그/alert에 Transcript, Minutes, email, Secret 또는 provider 원문이 없다.

## 의존성

- TASK-004.02 Issue #15: API-002의 고정 Template id/name/version 목록
- TASK-008.02 Issue #38: Structured Minutes controlled form, dirty state, accessible UI
- TASK-008.03 Issue #39: API-012 저장/requery와 base/draft/version reconciliation
- TASK-009.01 Issue #40: API-013 수락, API-010 optional `lastOperation`, 실패 rollback 후 REVIEW 복구
- TASK-006.07 Issue #33: 취소 가능한 API-010 polling/backoff 규칙

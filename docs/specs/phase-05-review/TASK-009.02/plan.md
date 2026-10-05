# Template 재생성 UI 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `SCR-006`, `FR-013`, `FR-014`, `API-002`, `API-010`, `API-013`, `TASK-009.02`
- TASK-004.02 Issue [#15](https://github.com/donghyunlee-dev/meeting-automation/issues/15): API-002 static Template metadata
- TASK-008.02 Issue [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38): Review editor, dirty form, dialog/accessibility pattern
- TASK-008.03 Issue [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39): API-012 save와 API-010 read-after-write/version reconciliation
- TASK-009.01 Issue [#40](https://github.com/donghyunlee-dev/meeting-automation/issues/40): 202 API-013, 실패 rollback, optional `lastOperation`
- TASK-006.07 Issue [#33](https://github.com/donghyunlee-dev/meeting-automation/issues/33): API-010 poll, 1초 정상 간격, 조회 오류 최대 10초 backoff 및 취소
- 화면 source: `docs/product/ui-design.md` SCR-006, `docs/product/PRD.md` API-002/010/013

## 소유 경계

- Review Template control: API-002 query, current/pending template selection 및 loading/error state
- Confirm/save coordinator: dirty Minutes save 결과가 확정된 뒤만 API-013 실행; API-012 mutation 구현은 TASK-008.03 owner를 사용
- Regeneration client: API-013 request DTO, latest version If-Match, intention idempotency key 및 safe error mapping
- Processing view state: same Review route의 blocking state와 TASK-006.07 bounded polling policy 재사용
- Session query/cache: version-aware 단일 API-010 snapshot 적용, TASK-009.01 `lastOperation` 대조 및 Review editor base 동기화
- Accessibility: native accessible dialog semantics, focus restore, loading announcement, 360px/reduced-motion 동작

## 구현 순서

1. Review route, API-002/API-010 query cache, TASK-008.02 dirty state, TASK-008.03 save/requery callback, API-013 client 및 TASK-006.07 polling abstraction을 확인한다. 결과: concrete component/query/callback 소유자가 기록된다.
2. template list fixture와 Review snapshot으로 selection/reset, cancel/no-request, dirty save gating, stale version outcome의 FE contract tests를 먼저 작성한다. 결과: 사용자 데이터 보존과 request 횟수 기대가 구현 전에 고정된다.
3. API-002 loading/error/success query와 current/pending Template selector를 연결한다. 결과: 목록 오류가 Minutes editor를 초기화하지 않고 유효 목록 전에는 regeneration을 막는다.
4. confirmation dialog와 dirty form gate를 구현한다. 결과: 취소 시 request 0회, dirty 시 save/read-after-write 성공 이전 API-013 호출 0회를 보인다.
5. API-013 client를 연결해 최신 API-010 version `If-Match`와 새 idempotency key를 제출한다. 결과: 202 이후 단일 blocking processing state에 진입하고 입력을 중복 실행하지 않는다.
6. API-010 cancellable polling을 TASK-006.07 backoff로 재사용한다. 결과: DRAFT_REGENERATION 표시, 오류 backoff, hidden tab/route 이탈/terminal cleanup이 동작한다.
7. API-010 version/session/outcome correlation을 적용하고 성공 snapshot 또는 복구된 실패 snapshot을 cache/form base에 version-aware로 반영한다. 결과: stale response가 최신 state/draft를 덮지 않으며 status/template selection이 서버와 일치한다.
8. API 거절/응답 불명확/세션 이탈, API-002 error, 접근성/mobile/reduced-motion 회귀를 검증하고 수동 QA 증거를 Issue #41에 남긴다. 결과: 전 사용자 흐름과 복구 경계가 기록된다.

## 사용자 흐름

Review/API-010 snapshot + API-002 목록 → current/pending 비교 → confirmation → (dirty면 API-012 save 및 API-010 재조회 완료) → API-013 202 → same route blocking progress → API-010 versioned polling → matching `lastOperation` → success Minutes 반영 또는 failure rollback Minutes 복원. API-002/013/010은 각 목적의 source로 유지한다. 별도 provider 설정 상태를 Template regeneration 가능 여부와 결합하지 않는다.

## 변경 후보

- Review page Template selector, confirmation dialog, regeneration state view
- Template query 및 typed API-013 mutation client
- 기존 API-010 query/polling/cache와 TASK-008.03 save coordinator 연결
- 검증용 Template/API-010/API-013 fixtures 및 component/integration tests
- SCR-006 accessibility/mobile presentation

실제 package/module/경로는 구현 checkout에서 기존 Frontend 구조를 확인해 결정한다. 이번 설계 checkout에서는 Frontend package manifest를 찾지 못해 package scripts를 추정하지 않는다.

## 검증 방법

현재 repository view에서 Frontend `package.json` 및 scripts가 확인되지 않았다. 구현 시 Frontend workspace의 package manifest와 lockfile 기준으로 `test`, `lint`, `build` 명령을 확정하고 Issue에 실행 명령/결과를 기록한다. Automated tests는 selection/confirmation, dirty save gate, API call/header, polling lifecycle/cancel, outcome reconciliation, keyboard/mobile를 포함한다. `TASK-009.03`의 backend+integration STT/Diarization 호출 0회 검증과 구별한다.

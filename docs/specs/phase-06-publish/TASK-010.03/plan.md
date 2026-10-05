# 구현 계획

## 기준과 의존성

- PRD v1.7.0, 2026-10-05: `SCR-007`, `API-010`, `API-014`, `API-015`
- 선행 작업: TASK-010.02, Issue [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44)
- 사용자 계약: API-014가 Review를 `CONFIRMED`로 확정; API-015는 새 version에서 Publish를 접수; API-010이 비동기 상태/문서 reference를 제공
- 본 작업: Issue [#45](https://github.com/donghyunlee-dev/meeting-automation/issues/45)
- 화면 기준: [spec.md](./spec.md), [tasks.md](./tasks.md), [test.md](./test.md)

## 변경 경계

- SCR-007 route/view-model: App Config loading, 선택 입력, dirty/recovery state
- API client: API-014 confirm, API-015 publish, API-010 polling/error parsing
- Review summary/editor connection: `details.issues`를 speaker/minutes field와 연결하고 scroll/focus 제공
- Publish view: progress, saved document link, failed/retryable state
- Router/cache: version-aware API-010 reconciliation, SCR-011 Settings 복귀

현재 repository에는 `frontend/` 디렉터리나 frontend package manifest가 없다. 구현 착수 시 현재 branch/PRD 기준으로 FE source/package를 확인하고 scripts가 있으면 그대로 사용한다. 없다면 FE baseline에 맞는 runnable test/lint/build scripts를 project setup 작업에서 먼저 제공하고, 이 task는 그 실제 명령을 실행한다. 설계에서 존재하지 않는 npm scripts를 가정하지 않는다.

Backend API 변경은 선행 TASK-010.02가 제공할 publish action/error/document reference 계약만 소비한다. 채널 provider 구현 및 채널 결과 화면은 별도 선행/후속 TASK의 경계다.

## 처리 순서

Review 검증을 먼저 완료해야 `CONFIRMED` version으로 Publish를 안전하게 접수할 수 있다. 서버가 Review completeness의 유일한 권위이므로 client-only 검증을 대체 수단으로 쓰지 않는다.

1. SCR-007과 Review route/query/cache ownership, API-001/010/014/015 DTO, Settings navigation/recovery contract를 구현 전에 확인한다.
2. API validation issue mapping, confirm→publish sequence, provider-null gate, polling/version races, failure retry의 component/API tests를 먼저 작성한다.
3. API-001 설정 상태 및 SCR-011 link/recovery state를 구현하고 provider 자동 선택을 방지한다.
4. Confirm mutation으로 API-014를 먼저 연결한다. 422 issue navigation과 no-publish behavior를 구현한다.
5. Confirm success version으로 API-015를 연결하고 selected channel options, generated idempotency keys, single-flight control을 적용한다.
6. 202 접수 뒤 bounded backoff API-010 polling, stale-version suppression, route cleanup을 구현한다.
7. `DOCUMENT_SAVED` document URL 및 `DOCUMENT_FAILED` retry action을 연결한다. 재시도는 fresh key/current version, explicit user click을 요구한다.
8. keyboard/mobile/browser, API error/redaction, full Review→document outcome 회귀를 검증하고 evidence를 Issue #45에 기록한다.

## 인터페이스

- API-014: 현재 Review version, confirm request; 200의 새 version/status; 422의 `details.issues` 전체
- API-015: recipient IDs와 notification choice; new Idempotency-Key 및 Confirm response version을 담은 If-Match; 접수 202
- API-010: status/version/allowedActions; document save 이후 optional `document.documentId`와 `document.documentUrl`. Document reference 존재 여부가 delivery state보다 저장 여부 판정의 기준이다.
- API-001: `document.provider`, `document.configured`; null 또는 false면 Settings 안내 및 Publish 차단

response envelopes는 common `{data}`/`{error}`를 유지하며 업무별 확장만 소비한다.

## 검증 및 제한

자동화 테스트는 API 호출 순서, issue mapping, provider null, polling state, link URL validation, retries/replay, cache races 및 accessible UI를 분리해 검증한다. 실제 frontend command는 구현 시 package manifest에서 확인한다. 로컬 project가 없는 상황에서 설계 단계 테스트를 실행하지 않는다. Document URL을 열 때 `noopener`/`noreferrer`를 적용한다.

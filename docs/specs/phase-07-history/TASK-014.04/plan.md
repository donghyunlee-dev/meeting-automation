# 구현 계획

## 의존성

- `TASK-014.02` API-018 Meeting Detail
- `TASK-014.03` Meetings list, route, query/caching 및 empty/loading/error foundation
- PRD v1.7.0 (2026-10-05), `FR-021~023`, `SCR-009`, `SCR-010`, `API-017`, `API-018`
- GitHub Issue: [#55](https://github.com/donghyunlee-dev/meeting-automation/issues/55)

## 변경 대상

- Meetings list toolbar: title/attendee text search, optional local `from`/`to` date fields, invalid range message, clear/reset action.
- Filter utility: normalized text match, inclusive local-date boundaries, month grouping of filtered items, no mutation/reordering of API results.
- Row/router: accessible row action to `/meetings/{documentId}`; route encodes the opaque identifier safely.
- Detail screen: API-018 client/query, loading/error/retry/not-found, read-only Minutes renderer, Transcript drawer, validated external URL action.
- Tests: pure filter/date utility, screen interactions, router detail flow, API query/error states, URL security, keyboard/accessibility.

## 구현 순서

Provider-backed list/detail and route exist from dependencies, so first define pure client-side filter behavior, then integrate navigation and detail presentation.

1. Filter utility tests for Unicode/case handling, combined text/date predicates, inclusive dates, invalid range, reset, stable order, and month groups.
2. Meetings screen tests for search/date inputs, accessible row navigation, result-empty vs dataset-empty, and filter reset.
3. API-018 client/detail tests for success, loading, 404, provider failure, retry, and no mutation actions.
4. Implement filter utility and bind controlled fields without additional API requests.
5. Implement detail route/client/Minutes presentation/Transcript accessible dialog and external URL action with scheme/hostname validation and new-tab isolation.
6. Run related FE tests, lint/build, keyboard and 360px viewport QA; record evidence.

## 검증 및 운영 경계

Exact FE scripts are taken from the repository package configuration at implementation time. Test cases are listed in [test plan](./test.md). Keep external link opening behind a clear user click. The only filter data source is the already loaded API-017 result set.

# 구현 계획

## 의존성

- `TASK-004.01` 제공 Home route, 새 회의 CTA, Bottom Navigation
- `TASK-014.01` 제공 API-017 최신순 요약 endpoint (`limit=5` 또는 기본 100)
- PRD v1.7.0 (2026-10-05), `FR-001`, `FR-020`, `SCR-001`, `SCR-009`
- GitHub Issue: [#54](https://github.com/donghyunlee-dev/meeting-automation/issues/54)

## 변경 대상

- Home page/component: 기존 UI에 최근 5건 query와 목록 영역을 추가한다.
- Meetings route/page: `/meetings` route, 화면 title/list/content container를 추가한다. 프로젝트의 Router convention에 맞춰 기존 Bottom Navigation을 재사용한다.
- API client/types: API-017 MeetingSummary 및 `{data:{items}}` response type, `limit` query parameter를 사용한다.
- Query/cache layer: `limit=5`와 `limit=100`이 다른 cache key가 되도록 하고 기존 retry/error convention을 재사용한다.
- Reusable UI: list row와 loading/empty/error/retry presentation을 프로젝트의 기존 component/design tokens로 구현한다.
- Tests: API hooks/components/router state에 대한 unit/component tests 및 page navigation tests.

## 구현 순서

Backend API 계약과 Home shell이 선행되어 있으므로 기존 frontend API client/types부터 연결한 뒤 Home/Meetings 화면을 구성한다.

1. 저장소 실제 route/API client/test 구성과 TASK-004.01 Home shell을 확인한다.
2. API client query, cache key, Home list states, Meetings page states와 navigation을 먼저 검증하는 실패 테스트를 작성한다.
3. API-017 typed client/query를 구현하고 `limit=5`/default-100 query가 구분되는지 확인한다.
4. Home recent list와 `/meetings` list를 구현한다. 재사용 shell/navigation을 사용하고 list click/detail/filter는 범위 밖으로 둔다.
5. Loading/empty/error/retry, data rendering, Home CTA 보존, responsive/accessibility tests를 통합한다.

## 인접 작업

`TASK-014.04`는 이 작업의 Meetings 화면에 검색/필터 field와 작동, row의 API-018 상세 연결, URL 열기를 추가한다. 이 task는 해당 동작에 필요한 row identifier인 `documentId`를 DOM 접근성/라우팅 연결 없이 내부 데이터로 보존한다.

## 검증

정확한 test/build 명령은 FE package 설정을 확인해 실행 단계에서 사용한다. 사례는 [검증 계획](./test.md)을 따른다.

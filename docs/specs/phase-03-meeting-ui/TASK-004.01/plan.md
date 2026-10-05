# 구현 계획

## 의존성

- `TASK-001.02` (#2): React 19, TypeScript, Vite, React Router, Vitest 기본 환경
- PRD v1.7.0 `FR-001`, `SCR-001`; UI Design SCR-001
- Issue [#14](https://github.com/donghyunlee-dev/meeting-automation/issues/14)

## 변경 대상

Home route/page, app route registration, 재사용 Primary button 또는 기존 UI component, Bottom Navigation 연결, component/router tests를 구현한다. 저장소에 아직 `frontend/`가 없으므로 먼저 scaffold 결과와 실제 디렉터리 구조를 확인한다.

## 소유권과 계약

- `/`는 Home shell을 렌더링한다.
- CTA는 `/meetings/new` 경로로 이동한다. `TASK-004.03`이 같은 경로의 페이지를 추가한다.
- 이 작업은 API 요청을 하지 않는다. 최근 Meeting 데이터/오류는 후속 `TASK-014.03` 범위다.
- 앱 공통 navigation이 이미 존재하면 재사용하고 두 번째 Bottom Navigation을 추가하지 않는다.

## 구현 순서

1. TASK-001.02 결과에서 app entry, route tree, design tokens 및 test command를 확인한다.
2. Home content/button과 router navigation 테스트를 구현보다 먼저 추가하고 실패를 확인한다.
3. 최소 Home component와 `/` route를 구현한다.
4. Router CTA로 목적 경로에 이동하는지 확인한다. 실제 New Meeting 페이지가 아직 없다면 테스트 Router에 destination fixture를 둔다.
5. Bottom Navigation, 360px layout, keyboard accessible name/focus를 확인하고 lint/test/build를 실행한다.

새 Meeting UI/API가 아직 미구현이어도 Home의 이동 계약과 목적 경로는 이 Task에서 독립 검증 가능하다.

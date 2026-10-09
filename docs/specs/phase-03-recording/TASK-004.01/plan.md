# 구현 계획

## 의존성

- `TASK-001.02` (#2): React 19, TypeScript, Vite, React Router, Vitest 기본 환경
- PRD v1.7.0 `FR-001`, `SCR-001`; UI Design SCR-001
- Issue [#14](https://github.com/donghyunlee-dev/meeting-automation/issues/14)

## 변경 대상

`frontend/` scaffold는 TASK-001.02에서 완료되어 있다. 현재 코드에는 Router와 Bottom Navigation이 없고 TASK-003.04 Participants 화면이 `App.tsx`에서 직접 렌더링되므로, React Router 의존성과 공유 shell을 추가하고 기존 화면을 Settings 하위 route로 보존한다.

## 소유권과 계약

- `/`는 Home shell을 렌더링한다.
- CTA는 `/meetings/new` 경로로 이동한다. `TASK-004.03`이 같은 경로의 페이지를 추가한다.
- 기존 Participants 화면은 `/settings/participants`에서 유지한다. Bottom Navigation의 Settings 항목은 이 화면으로 연결한다.
- Meetings 목록 화면은 후속 `TASK-014.03` 범위로 두고 이 task에서 목록/API를 구현하지 않는다.
- 이 작업은 API 요청을 하지 않는다. 최근 Meeting 데이터/오류는 후속 `TASK-014.03` 범위다.

## 구현 순서

1. 기존 app 진입점, Participants 화면, 의존성, 디자인 토큰과 테스트 명령을 확인한다. React Router가 없으므로 공식 선언형 라우팅 API 기준으로 의존성을 추가한다.
2. Home content/button과 router navigation 테스트를 구현보다 먼저 추가하고 실패를 확인한다.
3. 최소 Home component와 `/` route를 구현한다.
4. Router CTA로 목적 경로에 이동하는지 확인한다. 실제 New Meeting 페이지가 아직 없다면 테스트 Router에 destination fixture를 둔다.
5. Bottom Navigation, 360px layout, keyboard accessible name/focus를 확인하고 lint/test/build를 실행한다.

새 Meeting UI/API가 아직 미구현이어도 Home의 이동 계약과 목적 경로는 이 Task에서 독립 검증 가능하다.

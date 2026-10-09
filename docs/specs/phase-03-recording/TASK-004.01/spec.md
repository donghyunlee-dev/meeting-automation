# Home 기본 화면과 새 미팅 이동 설계

## 목표

React Router 앱의 Home shell을 제공하고 주요 CTA에서 New Meeting 화면으로 이동한다. PRD v1.7.0 (2026-10-05), `FR-001`, `SCR-001`, `TASK-004.01`을 구체화한다. Issue [#14](https://github.com/donghyunlee-dev/meeting-automation/issues/14).

## 범위

- `/` Home route와 기본 화면 shell
- 현재 앱에 없는 React Router와 공유 App shell/Bottom Navigation 연결
- 제목 `Meeting Automation`, SCR-001 안내 문구, `새 회의 시작` Primary button
- 하단 navigation
- 클릭 시 `/meetings/new`로 React Router 이동
- 기존 TASK-003.04 Participants 화면을 Settings 하위 `/settings/participants`에서 계속 사용할 수 있도록 route 연결
- component/router automated tests

## 비범위

최근 회의 목록, 전체 Meetings 화면, 목록 Loading/Empty/Error, New Meeting 폼/API 연동, 인증은 포함하지 않는다. 최근 목록과 전체 보기는 `TASK-014.03`에서 구현한다. New Meeting 페이지는 `TASK-004.03`에서 구현하되 목적지 경로는 본 Task와 동일하게 사용한다.

## 화면/경로 계약

- Home: `/`
- New Meeting destination: `/meetings/new`
- 제목 및 본문: SCR-001 예시의 `Meeting Automation`, `회의를 시작할까요?`, `회의 내용을 녹음하고 자동으로 정리합니다.`
- 주요 CTA: `새 회의 시작`
- 최소 360 CSS px viewport에서 가로 overflow 없이 표시하고 기존 UI 접근성 토큰을 따른다.

CTA는 링크 의미의 접근 가능한 이름을 제공하고 router navigation을 사용한다. 현재 코드에는 Router와 Bottom Navigation이 없으므로 PRD/UI Design의 세 항목(홈, 회의록, 설정)으로 새 공유 shell을 구성한다. 기존 Participants 화면은 Settings 하위 경로에 연결해 Home 추가로 접근성을 잃지 않게 한다. `/meetings/new` 페이지 본문과 Meetings 목록은 후속 task 범위다. CTA 이동 테스트에서는 목적 경로 fixture를 사용한다.

## 수용 기준

- `/` 진입 시 제목, 안내 문구, Primary CTA가 렌더링된다.
- CTA는 `/meetings/new`로 이동한다.
- Bottom Navigation이 기본 UI 설계와 일치한다.
- 기존 Participants 화면은 `/settings/participants`에서 계속 접근 가능하다.
- component 및 router 테스트가 렌더링과 이동을 확인한다.
- 화면이 360px viewport에서 가로로 넘치지 않고 CTA에 접근 가능한 이름이 있다.

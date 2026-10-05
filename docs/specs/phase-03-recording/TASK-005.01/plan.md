# 구현 계획

## 의존성

- `TASK-004.05` (#18): Session Context `{sessionId,title,uploadPolicy}`와 `/meetings/{sessionId}/recording` navigation
- `TASK-005.02` 후속: MediaRecorder/controller가 state, elapsed 및 callbacks를 제공
- PRD v1.7.0 `FR-003~005`, `SCR-003/004`; Issue [#19](https://github.com/donghyunlee-dev/meeting-automation/issues/19)

## 변경 대상

Recording route page/component, typed view-state/callback props, accessible end-confirm modal, Session Context guard, state fixture/component/accessibility tests를 구현한다. FE scaffold 경로가 준비됐는지 먼저 확인한다.

## 소유권과 계약

- UI view model: `Ready | Recording | Paused`, `elapsedMs`, `sessionId`, `title`
- UI actions: `onStart`, `onPause`, `onResume`, `onRequestEnd`, `onConfirmEnd`, `onCancelEnd`
- Controller side effect와 MediaRecorder는 UI 밖에 둔다. API-009는 이 Task에서 호출하지 않는다.
- modal 취소는 state controller에 cancel 신호를 주고 이전 state/timer를 유지한다.

## 구현 순서

1. Route, Session Context, design token, modal primitive와 Vitest setup을 확인한다.
2. 세 상태별 rendering, action callback, 종료 modal confirm/cancel component tests를 먼저 쓴다.
3. Route Session Context guard와 Recording layout/state presentation을 만든다.
4. Modal semantics, focus trap/restore, Escape handling과 CTA accessibility를 구현한다.
5. 360px layout 및 reduced motion/ARIA 동작을 테스트한다.
6. TASK-005.02 controller integration을 위한 prop/callback contract를 확인하고 frontend test/lint/build를 수행한다.

MediaRecorder 제어보다 시각 상태/action contract를 먼저 고정해 컨트롤러가 테스트 가능한 UI boundary를 사용하게 한다.

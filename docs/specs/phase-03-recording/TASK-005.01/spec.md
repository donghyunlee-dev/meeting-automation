# Recording 화면과 종료 확인 UI 설계

## 목표

New Meeting에서 만든 Session의 녹음 화면 상태를 표시하고 사용자의 시작/일시정지/재개/종료 확인 action을 제공한다. PRD v1.7.0 (2026-10-05), `FR-003~005`, `SCR-003`, `SCR-004`, `TASK-005.01`을 구체화한다. Issue [#19](https://github.com/donghyunlee-dev/meeting-automation/issues/19).

## 범위

- `/meetings/{sessionId}/recording` 화면 shell
- Ready/Recording/Paused UI-state별 title, status, timer, action
- 종료 확인 modal의 열기/계속 녹음/종료 확인
- MediaRecorder/controller state fixture 기반 component tests
- 모바일·키보드·screen reader presentation

## 비범위

MediaRecorder lifecycle, microphone permission 요청, timer 계산, Audio chunk, upload, processing start, API 호출은 포함하지 않는다. 실제 녹음 제어는 `TASK-005.02`, chunk/upload는 후속 TASK에서 담당한다.

## 상태 및 동작 계약

이 화면의 `Ready | Recording | Paused`는 React view state이며 Backend Session의 API status enum에 추가하지 않는다. Ready는 생성 직후 `CREATED` Session Context에서 시작한다. 화면은 Session title과 `sessionId`를 사용하고 timer elapsed 값은 controller가 전달한다. 표시값은 HH:mm:ss, tabular numerals를 사용한다.

- Ready: `준비됨`, `00:00:00`, `녹음 시작`; `onStart` callback만 보낸다.
- Recording: `녹음 중`, elapsed, `일시정지`, `회의 종료`; `onPause`, `onRequestEnd` callback을 보낸다.
- Paused: `일시정지됨`, 유지된 elapsed, `녹음 재개`, `회의 종료`; `onResume`, `onRequestEnd` callback을 보낸다.
- `onRequestEnd` 후 `SCR-004` modal을 연다. `계속 녹음`은 modal을 닫고 modal을 열기 전 state와 timer를 그대로 둔다. Escape도 같은 취소 동작이며 focus를 호출 버튼으로 돌린다.
- `회의 종료` 확정은 `onConfirmEnd`를 한 번 호출한다. 실제 recorder stop/업로드/processing은 호출부 책임이다.

녹음이 진행/일시정지 중일 때 Bottom Navigation과 Settings 이동을 숨긴다. Ready는 기존 app shell 기준을 따른다. Session Context가 없거나 route Session ID와 Context ID가 다르면 MediaRecorder callback을 호출하지 않고 안내와 `/meetings/new` 이동 action을 제공한다.

## 수용 기준

- 세 view state별 status text, elapsed time, actions가 일치한다.
- 종료 확인 modal은 Recording/Paused 상태에서 열리고 취소 뒤 원래 state/timer가 유지된다.
- 확인 action은 callback을 한 번 보내고 이 Task가 recorder/API를 직접 실행하지 않는다.
- Session Context 부재/ID mismatch는 녹음 action을 막고 안전하게 안내한다.
- 360px 모바일, 터치 대상 최소 44×44px, focus/label/modal 접근성이 UI 기준에 맞는다.

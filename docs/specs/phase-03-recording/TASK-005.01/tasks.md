# 구현 작업 목록

1. Ready/Recording/Paused state fixture component tests를 작성한다. 기대 결과: title/status/timer/action 불일치가 실패한다.
2. `onStart/onPause/onResume/onRequestEnd` callback assertion을 작성한다. 기대 결과: 버튼별 정확한 callback만 호출된다.
3. modal confirm/cancel/Escape/focus restore tests를 작성한다. 기대 결과: cancel은 원래 state/timer 보존, confirm은 한 번 callback이다.
4. Session Context missing/mismatched route tests를 작성한다. 기대 결과: 녹음 callback을 호출하지 않고 안내한다.
5. Recording route shell, state view model, mobile layout을 구현한다.
6. 종료 확인 modal과 button 상태/접근 가능한 이름/focus 동작을 구현한다.
7. MediaRecorder mock을 사용하는 controller integration UI test를 추가해 input state 전이를 표시한다. 실제 recorder 제어 코드는 추가하지 않는다.
8. 360px/accessibility checks와 `npm run test`, `npm run lint`, `npm run build`를 수행한다.

## 의존성 결과

후속 `TASK-005.02`는 위 view state/callback 계약만 구현하고 Session view presentation을 중복 생성하지 않는다.

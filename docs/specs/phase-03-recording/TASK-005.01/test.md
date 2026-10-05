# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-003-01 | Ready, elapsed 0 | 제목, `준비됨`, `00:00:00`, start action | Vitest component test |
| SCR-003-02 | Recording, elapsed 62,018ms | `녹음 중`, `00:01:02`, pause/end actions | Component test |
| SCR-003-03 | Paused, elapsed 62,018ms | `일시정지됨`, 동일 timer, resume/end actions | Component test |
| SCR-003-04 | 각 state의 start/pause/resume click | 해당 callback 한 번만 호출 | Component test |
| SCR-004-01 | Recording/Paused에서 end 요청 | 접근 가능한 confirm modal과 안내가 표시됨 | Component/modal test |
| SCR-004-02 | modal `계속 녹음`/Escape | modal 닫힘, 이전 view state/timer, focus 보존/복구 | Component/accessibility test |
| SCR-004-03 | modal `회의 종료` 확인 | `onConfirmEnd` 한 번 호출, API/media side effect는 UI에서 없음 | Component test |
| SCR-003-05 | Session Context 없음 또는 ID mismatch | 안내와 New Meeting action, media callback 없음 | Router test |
| SCR-003-06 | 360px viewport | 가로 overflow 없음, 주요 action 최소 44×44px | Browser/component test |
| SCR-003-07 | Recording/Paused | Bottom Navigation 숨김; Ready에서 shell 기준 표시 | Component test |
| SCR-003-08 | timer 초 단위 변경 | screen reader에 status만 알리고 초마다 timer를 반복 낭독하지 않음 | Accessibility test |
| SCR-003-09 | reduced motion 설정 | 움직임/점멸 animation 생략 | Component/style test |

Frontend scaffold Issue #2의 `npm run test`, `npm run lint`, `npm run build`로 검증한다. 실제 MediaRecorder mock integration은 component state adapter에 주입하고 이 Task는 browser microphone API를 직접 실행하지 않는다.

## 수동 QA

- Session 생성 후 Recording route에서 Ready 화면과 title/timer를 확인한다.
- mock/controller가 전달하는 Recording/Paused state별 버튼과 timer를 확인한다.
- 종료 확인에서 계속 녹음/ESC는 화면 state와 timer를 보존하고, 확인은 종료 callback을 한 번 전달한다.
- 키보드 focus trap/restore, screen reader 안내, 360px mobile layout을 확인한다.

## 릴리스 확인

실제 마이크 권한, MediaRecorder codec 및 stop timing은 TASK-005.02와 Phase 8 실기기 검증에서 확인한다. Chunk 업로드 및 `API-009` 처리 시작은 이 화면 modal에 연결하지 않는다.

# 주요 조작 접근성 및 입력 검증

## 작업 식별 정보

- 작업: TASK-020.03
- 상위 작업: TASK-020 Mobile UI / Accessibility Polish
- 단계: Phase 9 — Release
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: SCR-001~012, NFR-010, NFR-012
- 선행 작업: TASK-020.01 Issue #73
- GitHub Issue: [#75](https://github.com/donghyunlee-dev/meeting-automation/issues/75)

## 결과

주요 화면의 핵심 입력·버튼·navigation·modal/drawer를 pointer와 keyboard로 사용할 수 있게 하고, accessible name/role/state와 visible focus 및 비색상 상태 cue를 검증한다. PRD의 mobile control 크기를 보존하면서 공통 결함은 최소 수정하고 자동/수동 Evidence를 기록한다.

## 적용 기준

- SCR-001~012의 모든 primary action, form control, nav link, icon-only action, state/retry control을 inventory로 만든다. 화면 내 modal/bottom sheet/drawer가 열릴 때와 닫힌 뒤도 포함한다.
- PRD 13의 주요 touch target 최소 44×44 CSS px, PRD 15.4의 primary button 최소 높이 52px 및 Input/Select 최소 높이 48px를 모두 지킨다. 자주 쓰는 mobile icon/action은 48×48 CSS px hit area를 목표로 한다. WCAG 2.2 SC 2.5.8의 24×24 CSS px 최소와 spacing 예외도 확인하되, WCAG 예외가 더 엄격한 PRD 44px 기준을 대체하지 않는다.
- 페이지와 대화상자의 모든 기능은 Tab/Shift+Tab, Enter/Space, Escape 및 해당하는 native select 조작만으로 완료 가능해야 한다. Tab 순서는 시각적/논리적 읽기 순서를 따른다. 키보드 trap은 modal 내부에서만 허용하며 Escape/닫기로 빠져나오고 원래 실행 control로 focus를 돌린다.
- 모든 입력은 visible label과 programmatic accessible name을 가진다. 오류는 해당 control과 `aria-describedby` 등으로 연결하고 focus 이동 뒤 오류를 알 수 있다.
- 모든 interactive element는 keyboard focus 시 식별되는 visible indicator를 유지하고 sticky header/action/modal에 가려지지 않는다.
- Processing progress는 의미 있는 상태 변경을 보조기술에 전달한다. 반복 progress tick 전체를 live announcement로 쏟아내지 않는다. 성공/실패/녹음 상태는 색 외에 텍스트 및/또는 아이콘으로 전달한다.
- Loading/Empty/Error 공통 표시는 `role=status`/`role=alert` 등을 의미에 맞게 사용하고 화면 이동 시 focus heading/landmark가 명확하다. modal은 dialog name, focus containment, close action 및 focus restoration을 가진다.
- native HTML semantics를 먼저 쓴다. ARIA는 native semantics로 표현 불가능한 role/state/name에만 추가하며 시각적 동작과 accessible tree가 일치해야 한다.

## 포함 화면

Home/new meeting, Recording/end confirm/Processing, Review/transcript drawer, Share/Complete, Meetings/detail, Settings/Participants. 서로 재사용하는 shared control은 component에서 한 번 검증하고, 각 화면에서는 labeling, state, keyboard flow 연결을 확인한다.

## 제외

- 신규 기능 또는 디자인 시스템 전면 개편
- 실제 Android/iOS 브라우저·OS keyboard, safe area, TalkBack/VoiceOver 전체 기기 검증 (`TASK-020.04`)
- WCAG 인증/법률 적합성 주장. 자동 scanner 결과는 수동 평가를 대체하지 않는다.
- 화면 상태 조합 자체의 exhaustive 검증 (`TASK-020.02`) 및 viewport layout matrix 전체 재실행 (`TASK-020.01`)

## 완료 기준

- 핵심 control inventory가 SCR ID, route, control, accessible name/role, input method, measured target 크기를 담는다.
- PRD 주요 target 44×44, primary height 52, input/select height 48 기준을 통과한다. 예외는 상위 요구사항의 명시된 근거에 한해 기록하고 WCAG 24px 예외를 PRD 기준 완화 근거로 쓰지 않는다.
- 각 주요 흐름이 keyboard-only로 완료되고 visible focus, 논리적 순서, dialog focus 이동/복원이 확인된다.
- form label/error association, Loading/Processing status announcement, 비색상 status cue가 실제 accessible tree/화면에서 확인된다.
- 자동 accessibility scan의 미해결 critical/serious violation이 없고, 수동 사례마다 pass/fail와 Evidence가 있다.
- 수정된 shared component 및 영향을 받은 route regression이 통과하고 `docs/evidence/TASK-020.03.md`에 기록된다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [반응형 viewport 기준](../TASK-020.01/spec.md)
- [비동기 상태 기준](../TASK-020.02/spec.md)
- [WCAG 2.2 target size minimum 이해](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)
- [WCAG 2.2 focus visible 이해](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible)
- [Playwright accessibility testing](https://playwright.dev/docs/accessibility-testing)

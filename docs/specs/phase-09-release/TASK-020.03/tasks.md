# 주요 조작 접근성 작업 목록

- [ ] SCR-001~012의 primary/input/icon/navigation/dialog control inventory를 만들고 accessible name, role, state, target size, keyboard action을 명시한다. 의존: TASK-020.01 routes. 완료 결과: 검사 목록과 route fixture 연결.
- [ ] target size, name/label, focus, tab order, dialog, status announcement의 실패 테스트를 먼저 작성한다. 의존: inventory. 완료 결과: 각 핵심 계약이 독립적인 failing case로 재현된다.
- [ ] 공통 native semantics, label/error association, focus-visible 및 52px/48px PRD 크기를 구현한다. 의존: red tests. 완료 결과: shared component tests green.
- [ ] modal/sheet/drawer initial focus, containment, Escape close, opener focus restore를 구현하고 검사한다. 의존: 공통 control tests. 완료 결과: dialog keyboard cycle 통과.
- [ ] SCR별 tab 흐름, accessible action names, 비색상 state text, async `status/alert` 연결을 구현한다. 의존: shared component. 완료 결과: screen integration tests green.
- [ ] stable `@axe-core/playwright`와 `npm run test:a11y`를 Playwright 설정에 연결한다. 의존: frontend/Playwright baseline. 완료 결과: scanner report 재현 및 lockfile 반영.
- [ ] 자동 검사와 Keyboard-only QA, target size 측정, 수정 영향 regression을 실행한다. 의존: 기능 구현. 완료 결과: `test.md` 모든 case 결과와 follow-up issue 기입.
- [ ] `docs/evidence/TASK-020.03.md` 작성 및 .020.04 device QA로 넘길 항목 분리. 의존: 검사 결과. 완료 결과: 개인정보 없는 증거 및 미해결 항목 링크.

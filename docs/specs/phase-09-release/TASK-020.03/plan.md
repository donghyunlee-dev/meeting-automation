# 주요 조작 접근성 구현 계획

## 의존성과 우선순위

- TASK-020.01 Issue #73: 주요 routes, modal/drawer 및 Playwright viewport harness
- TASK-020.02 Issue #74: async state, safe error copy 및 Processing 상태 mapping
- PRD SCR-001~012, NFR-010/NFR-012 및 15.4 button/input 크기 기준

이 task는 FE 및 QA 소유다. 먼저 control inventory와 failing keyboard/accessibility tests를 만들어 현재 페이지 계약을 재현하고, 공통 control/semantics를 수정한 뒤 각 route 통합을 검증한다. accessibility scanner를 기존 Playwright harness에 연결하고 실제 Tab/Enter/Escape 및 touch-target geometry는 별도 수동 절차로 확인한다. 자동 scanner는 오용된 role/name 등 기계 탐지 가능한 일부 문제만 찾으므로 keyboard-only 수동 검증을 병행한다.

## 변경 대상

| 위치 | 책임 |
|---|---|
| `frontend/src/components/` shared Button/Input/Select/Status/Dialog/Drawer | PRD 크기, native semantic, focus ring, label/error relationship, open/close focus management |
| `frontend/src/features/**/` | SCR route별 accessible names, status semantics, tab order, processing/live status, dialog 연결 |
| `frontend/tests/accessibility/` 또는 기존 test convention | Playwright + axe scan, target geometry assertions, route/control inventory tests |
| `frontend/playwright.accessibility.config.ts` 또는 기존 Playwright 설정 | axe test browser project 및 synthetic app server 연결 |
| `frontend/package.json`, `package-lock.json` | 구현 시 확인한 최신 stable `@axe-core/playwright` dev dependency와 `test:a11y` script 추가 |
| `docs/evidence/TASK-020.03.md` | control inventory, auto scan report, keyboard/touch manual 결과와 예외 기록 |

실제 source tree가 scaffold에서 feature route로 진전되어 있을 수 있으므로 구현 시 기존 selector/component conventions을 먼저 확인하고 이에 맞춰 경로를 조정한다. 앱 router나 design system 전체를 다시 만들지 않는다.

## 접속성 동작 계약

- Native `button`, `a`, `label`, `input`, `select`, heading, landmark 우선. clickable `div`에 직접 key handler를 추가하기보다 적절한 native element로 바꾼다.
- Icon-only control은 액션을 설명하는 accessible name을 제공한다. decorational icon은 accessible tree에서 숨긴다.
- Dialog/sheet는 `role=dialog` 및 accessible title을 제공하고 열릴 때 initial focus를 내부로 이동, Tab 순환을 제한, 닫힐 때 opener에 복원한다. 사용자가 명시적으로 다른 위치로 이동한 경우 focus를 강제로 빼앗지 않는다.
- Validation errors는 form field에 연결하고 invalid field 또는 summary로 결정적인 focus 이동을 제공한다. 입력 오류가 color only가 되지 않도록 text/shape도 제공한다.
- 비동기 status는 `TASK-020.02`의 상태 유형을 그대로 읽기 가능한 short text로 노출한다. `aria-live=polite`를 기본으로 하고 사용자 차단 오류만 `alert` 사용을 검토한다. 매초 elapsed timer/progress 숫자를 반복 공지하지 않는다.
- Disabled/processing control의 실제 disabled 상태와 이유를 전달한다. 단순 색 변화나 pointer-events만으로 비활성 처리하지 않는다.

## 작업 순서

| 단계 | 소유 | 결과 |
|---|---|---|
| SCR-001~012 control inventory 및 expected semantics 작성 | FE, QA | 모든 핵심 control, modality, 상태 announcement 대상 목록 |
| target size/accessible name/dialog/focus/keyboard red tests 추가 | FE | 실패를 재현하는 Playwright/Testing Library 사례 |
| shared control semantics/size/focus/dialog handling 보완 | FE | 공통 컴포넌트 contract green |
| route별 labels/status/navigation/action과 modal 연결 보완 | FE | screen integration tests green |
| axe scan 및 target/focus geometry 자동화, dependency lock | FE | 재현 가능한 `npm run test:a11y` |
| keyboard-only 및 screen reader status 수동 QA, device handoff 작성 | QA | `docs/evidence/TASK-020.03.md`에 pass/fail과 미지원 기기 구분 |

## 검증 접근

- 현재 Vitest/jsdom 명령 `npm run test`는 semantic/component contracts에 활용한다. 실제 browser focus order 및 bounding boxes는 TASK-020.01이 준비한 Playwright harness를 사용한다.
- 구현 시 공식 Playwright guidance에 따라 `@axe-core/playwright`를 browser test에 붙인다. 버전은 구현 시 stable release로 정하고 lockfile에 기록한다. WCAG 2.2 A/AA rule tags를 기준으로 초기 scan하되 WCAG 전체 준수라고 해석하지 않는다.
- 12 screen 전부에 같은 axe scan을 복제하지 말고 shared shell/control scan과 각 route의 핵심 unique control/form/dialog을 다룬다. open state가 있어야 하는 drawer/modal은 먼저 조작해서 열고 scan한다.
- target 크기/spacing, focus visible/clipped, tab order, Enter/Space/Escape는 Playwright 자동화와 QA 수동 keyboard run을 함께 사용한다.
- 수정 후 기존 `npm run test`, `npm run lint`, `npm run build`, `npm run test:viewport`, 신규 `npm run test:a11y` 결과를 Evidence에 기록한다.

# 주요 조작 접근성 검증 계획

## 자동화 테스트

기존 명령은 `frontend/`의 `npm run test`, `npm run lint`, `npm run build`, TASK-020.01에 계획된 `npm run test:viewport`다. 본 task는 구현 deliverable로 Playwright + `@axe-core/playwright`를 추가하고 `npm run test:a11y`를 제공한다. 설치 버전은 구현 시 stable release를 확인해 lockfile로 고정한다. 계획 단계에서는 테스트를 실행하지 않는다.

| ID | 조건/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| A11Y-AXE-ROUTES | synthetic fixture로 SCR-001~012 대표 ready/error states를 열고 axe WCAG 2.2 A/AA 범위 검사 | 미해결 critical/serious 자동 위반 없음; 확인한 route/state가 report에 기재됨 | axe JSON/HTML report |
| A11Y-OPEN-SURFACES | SCR-004 confirm, SCR-006 transcript drawer, participant picker 등 열어 검사 | dialog name/role, focus 내부 진입, background inert/modal semantics 정상 | Playwright assertions + scan |
| A11Y-NAMES | 모든 interactive control을 role/name 기준으로 query | icon-only/action control 포함 목적을 설명하는 이름이 존재 | accessible locator result |
| A11Y-FORM-LABELS | SCR-002/006/009/011/012 fields 검사 | visible label이 input에 연결되고 placeholder가 유일한 label이 아님; invalid/error 설명 연결 | label/id/aria-describedby assertions |
| A11Y-TARGET-SIZE | primary/input/other frequent controls bounding box 조회 | primary height >=52px, input/select >=48px, common touch target >=48×48px 목표; 나머지 >=24×24 또는 문서화된 WCAG spacing exception | selector rect/exception list |
| A11Y-FOCUS-VISIBLE | 모든 주요 control에 keyboard focus 이동 | focus indicator가 눈에 띄고 viewport/sticky UI에 가려지지 않음 | focus screenshot + rect |
| A11Y-TAB-ORDER | 각 주요 task flow에서 Tab/Shift+Tab 이동 | 시각/논리 순서 유지, keyboard trap은 열린 modal 내부에서만 존재 | focus sequence assertion |
| A11Y-KEY-ACTIVATION | Enter/Space/Escape로 link/button/toggle/dialog 조작 | pointer 없이 새 회의, 검색/필터, 종료 취소, drawer/modal open-close 수행 가능 | Playwright key results |
| A11Y-DIALOG-RESTORE | confirm/drawer open → 내부 순환 → Escape/close | initial focus 내부, 닫힌 뒤 opener에 복귀, hidden contents 접근 불가 | focus assertions |
| A11Y-STATUS-TEXT | Loading/Error/Processing/retry fixture와 color token 변형 렌더 | 의미가 text/state로 존재하고 color만으로 차이를 전달하지 않음; 과도한 반복 announcement 없음 | DOM role/text assertions |
| A11Y-ASYNC-FOCUS | async error 후 keyboard flow 및 form error 제출 | 사용자 focus가 예측 가능하고 오류 field 또는 summary에 도달 | focus order trace |

## 수동 QA

- SCR-001~012 핵심 user journey를 마우스/터치 없이 Tab, Shift+Tab, Enter, Space, Escape로 완료한다. 현재 focus 위치가 계속 보이는지, sticky area가 가리지 않는지 확인한다.
- 모바일 viewport에서 Home/Recording/Processing/Review/Share의 핵심 control hit area와 간격을 개발자 도구로 측정한다. 정확한 실제 기기 터치 검증은 TASK-020.04에서 수행한다.
- modal/sheet/drawer를 열고 첫 focus, 내부 순환, close, trigger focus 복귀를 확인한다.
- invalid form, Loading, Processing progress/failure 및 retry 상태의 visible text와 보조기술용 role/live status를 확인한다. live region은 매초 timer를 반복해서 낭독하지 않는다.
- scanner가 놓치는 논리 순서, action 의미, focus obscured, error recovery를 수동 기록한다. 실제 TalkBack/VoiceOver 기기 조합 미검증은 성공으로 추정하지 않고 TASK-020.04 handoff로 표시한다.

## 완료 증거

- 위치: `docs/evidence/TASK-020.03.md`
- 포함: commit/build, browser/version, SCR/route/control inventory, target rect, keyboard sequence, axe violations/fixes, 수동 결과, WCAG target-size 예외 근거, .020.04 handoff
- 금지: 실제 participant/email/meeting/audio/transcript/Secret/provider response
- 성공: 모든 자동 사례가 통과하고 keyboard-only QA에서 차단 결함이 없으며, 실제 device assistive-tech 범위는 별도 후속으로 식별됨.
- 실패: 핵심 action keyboard 불가, 보이지 않는 focus, 이름 없는 control, form error 인지 불가, color-only state, input data loss, target policy 위반 또는 modal에서 빠져나갈 수 없음.

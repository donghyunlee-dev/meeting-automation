# 검증 계획

## 자동화 테스트

기존 FE unit/component 명령은 `frontend/`에서 `npm run test`, `npm run lint`, `npm run build`이다. `vitest`/`jsdom`은 page CSS layout을 계산하지 않으므로 이 task는 현재 구현 시점의 stable `@playwright/test` dev dependency, Chromium/WebKit projects, `playwright.viewport.config.ts`, Vite `webServer` config 및 `npm run test:viewport`를 추가한다. Browser binaries는 `npx playwright install chromium webkit`으로 setup한다. 계획 단계에서는 test를 실행하지 않는다.

| ID | 조건/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| VIEWPORT-MATRIX | SCR-001~012를 360/375/390/430/768/1280px에서 load | 각 route에서 root scrollWidth <= clientWidth; console/app runtime error 없음 | width/clientWidth, route, build |
| CONTENT-MAX | 768/1280 viewport에서 주요 page containers 검사 | content column <=640px, horizontal center alignment, mobile single-column hierarchy 유지 | element bounding rect |
| MOBILE-PADDING | 360/375 mobile primary content sections 검사 | horizontal padding 16px 이상이며 core content 양쪽 clipping 없음 | element x/width measurement |
| SCR-004-MODAL | 360/375에서 End Confirm 열기 | title/body와 양 action이 modal viewport 내에서 표시되고 modal/page에 x overflow 없음 | screenshot/bounding boxes |
| SCR-006-DRAWER | 360/375에서 transcript drawer 열기/닫기 | drawer content 접근 가능, 닫기/action이 viewport 밖으로 밀리지 않음 | screenshot/open/close status |
| LONG-KOREAN | long synthetic meeting title/name, 80+ char name, long action item/title | wrap/clamp가 유지되고 다른 action/field를 밀어내지 않음 | fixture ID, box widths, screenshot |
| LONG-ERROR | New Meeting validation, Processing failure notice/actions, detail error copy | body scroll 필요 시 세로로만 발생하고 error/action content 잘림 없음 | route/state fixture ID, measured bounds |
| LIST-CONTROLS | SCR-009 search/date filter and SCR-012 participant search | input/clear/select control이 360px에서 가로 overflow 없이 보이고 사용 가능 | screenshot/selector measurements |
| REVIEW-SHARE | SCR-006 speaker map/template/actions, SCR-007 share choices, SCR-008 result | 주요 buttons/sections가 viewport 안에서 wrap/vertical flow, no page x overflow | route fixture screenshot |
| NAV-RECORDING | SCR-001 nav 및 SCR-003 Recording layout at phone/desktop widths | Recording nav hidden; timer/title/end action remain within 360px; Home nav fits | selector rect/nav state |
| RECHECK-SHARED | CSS fix 뒤 target route plus shared nav/modal/drawer pages re-run | original assertion green, no regression at all matrix widths | test reports/build commit |

`npm run test:viewport`은 Playwright Chromium/WebKit projects를 모두 실행한다. CI에서 Browser cache를 사용할 수 없을 때만 위의 browser-install command를 실행하며 설치 결과/browser revision을 Evidence에 기록한다.

## 수동 시각 점검

- 각 SCR route의 360 CSS px screenshot/DOM bounding result 확인. Screenshot에는 fixture names/emails만 사용하고 개인정보를 표시하지 않는다.
- horizontal overflow가 나오면 `documentElement.scrollWidth`, `clientWidth`, 문제가 있는 element bounding rect와 clipping ancestor를 근거로 원인을 적는다.
- 768/1280에서는 desktop/tablet content width/centering을 확인하고 640px layout max를 넘지 않는지 측정한다.
- 360x640/800 높이에서 page의 세로 scroll이 필요한 screen은 자연스럽게 scroll 가능하고 fixed primary action이 content를 가리지 않는지 본다. OS keyboard/safe-area는 TASK-020.04다.
- API/data load state의 Loading/Empty/Error 전 조합은 TASK-020.02, keyboard/touch/screen reader는 `.020.03`에서 별도 검사한다.

## 성공/실패 판정

- 성공: 모든 12 main screens 및 지정 modal/drawer가 360px에서 page x overflow 없이 보이고, 375/390/430/768/1280 widths에서도 content/clipping/geometry 기준이 통과한다.
- 실패: root horizontal scroll, 주요 text/button/input이 viewport 밖, fixed action이 content를 가림, long Korean synthetic string이 page width 확장, desktop max-width 기준 위반.
- 의도된 scroll은 vertical only다. 존재하지 않는 API state나 실제 mobile browser-only defect를 viewport screenshot에서 성공으로 추정하지 않는다.
- 자동 overflow check가 통과해도 visual clipping가 보이면 fail로 남기고 selector/bounds를 기록한다.

## Evidence 및 정리

- 위치: `docs/evidence/TASK-020.01.md`
- 허용: screen ID, route, viewport, browser/build, fixture ID, scroll/client width, element rect, pass/fail, screenshot path, follow-up Issue.
- 금지: 실제 meeting/participant name/email, audio/transcript, Secret, provider response, signed URL.
- API는 mock/synthetic fixture를 사용한다. screenshot 및 fixture는 scrub 후 commit/Evidence에 포함하고 transient real-account data는 저장하지 않는다.

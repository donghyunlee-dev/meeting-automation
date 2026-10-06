# 검증 계획

## 선행 조건

- TASK-004.05 Issue #18: New Meeting successful/failure route flow 및 API-006 context
- TASK-014.04 Issue #55: SCR-009/010 route, API-017/018 list/detail, transcript drawer
- PRD v1.8.1, SCR-001~012, `NFR-001`, `NFR-002`, layout max width/padding/button contracts
- FE app routes 및 screenshots/DOM selectors가 비운영 synthetic fixture data로 실행 가능한 환경

선행 route가 아직 구현되지 않았다면 dummy UI로 완료를 주장하지 않고 존재하지 않는 route를 blocker로 기록한다. Endpoint가 미사용이어도 client mock fixtures로 페이지 layout을 검증한다.

## 소유권

| 영역 | 담당 | 산출 결과 |
|---|---|---|
| Route/screen inventory | FE + QA | SCR id, route, representative screenshot/fixture |
| Viewport automation | FE | viewport resize/set, overflow assertions, layout bounding boxes |
| Visual/manual review | QA | wrapping/clipping, long content, modal/drawer reachability |
| Responsive fix | FE | 재현 결함의 최소 mobile-first layout correction |
| Evidence/privacy | QA | synthetic screenshot/DOM measurement 및 민감정보 제외 |

이 task의 사용자 가치는 기준 화면을 각 viewport에서 보이게 하는 것이다. 구현 코드를 바꾸기 전 route별 자동 viewport checks를 만들고 failing breakpoint를 재현한다. CSS/component를 수정하면 기존 check와 영향을 받는 route matrix를 재실행한다.

## Browser viewport harness

현재 `vitest.config.ts`는 `jsdom`을 사용하며 실제 CSS layout engine이 아니므로 `scrollWidth`/bounding box를 제품 DOM에서 판정할 수 없다. 이 task는 FE root에 현재 구현 시점의 최신 안정 `@playwright/test` dev dependency와 lockfile entry를 추가하고, 별도의 `playwright.viewport.config.ts`, route viewport test suite, `npm run test:viewport` script를 만든다.

- Playwright Chromium과 WebKit projects를 사용해 CSS pixel viewport matrix를 실행한다. Playwright WebKit 결과는 iOS Safari 인증/실기기 결과로 표기하지 않으며 실제 기기 검증은 `.020.04`에 둔다.
- Playwright config `webServer`가 `npm run dev -- --host 127.0.0.1 --port 4173 --strictPort`를 시작하고 `http://127.0.0.1:4173` ready URL을 기다리게 한다. 별도 backend가 필요한 routes는 기존 API mock fixture가 준비될 때만 `route.fulfill()`으로 응답한다.
- `@playwright/test` version은 문서 설계 시 고정하지 않는다. 구현 시 npm registry stable release를 확인하고 `package-lock.json`에 exact resolution을 보존한다.
- Test runner는 route별 fixture/viewport를 parameterize하고 app route에 접근해 root/body scroll width, 핵심 selector bounding rect, visible text/action, `page.screenshot()` artifact를 저장한다.
- Visual review용 screenshot은 실행 산출물로 남기되 OS/browser rendering 변동 때문에 pixel-golden assertion/임의 pixel tolerance를 도입하지 않는다. 구조적 layout assertion이 pass/fail oracle다.
- 근거 문서: [Playwright Projects](https://playwright.dev/docs/test-projects), [Playwright Web Server](https://playwright.dev/docs/test-webserver), [Playwright Screenshot Assertions](https://playwright.dev/docs/test-snapshots).

## 화면 및 뷰포트 행렬

| 화면 ID | 대표 route/content | 보조 표면 |
|---|---|---|
| SCR-001 | Home, recent meeting list, bottom navigation | — |
| SCR-002 | New Meeting long title, participant multiselect, validation | picker/dialog if current design uses it |
| SCR-003 | Recording long meeting title, timer, end action | bottom navigation hidden |
| SCR-004 | Recording end confirm | modal/bottom sheet |
| SCR-005 | Processing stepper and representative failure notice/actions | — |
| SCR-006 | Review speaker map, long action item/text | transcript drawer and template confirm if defined |
| SCR-007 | Share document/email/slack options | — |
| SCR-008 | Complete statuses and actions | partial channel result fixture |
| SCR-009 | Meetings search/date filter and month list | filter controls wrap |
| SCR-010 | Meeting detail sections and long title | transcript drawer/external link control |
| SCR-011 | Settings integrations/card labels | — |
| SCR-012 | Participant search and long name/email fixture | add/edit form route if separately visible |

Viewport width matrix: 360, 375, 390, 430, 768, 1280 CSS px. Mobile cases use representative available app min height and an additional short 640px height for content scrolling; desktop/tablet use 900px height. Width/height pairs remain fixed for each visual comparison. Device rotation and real OS safe-area test are `.020.04`.

## 실행 순서

- FE root의 기존 `npm run test`, `lint`, `build` scripts 및 Browser route selectors를 확인하고 Playwright harness/version을 추가한다. 결과: `npm run test:viewport`와 local server setup이 재현 가능하다.
- Build SCR-001~012 route fixture inventory. Result: every screen and inline modal/drawer points to one synthetic dataset/state.
- At 360, 375, 390, 430, 768, and 1280 run the same screens/fixtures. Result: root scroll/client widths and key element rects are captured.
- Test long Korean title, 80+ character Participant name, fixture email, multi-line validation/error, long decision/action item and Transcript sentences at 360px. Result: wrap/clamp and action visibility are directly observed.
- Confirm mobile `content max-width` behavior is unrestricted up to 640px, padding never below 16px, body has no x overflow; at 768/1280 content column is centered and <=640px.
- Capture screenshot only when automated bounding checks fail or as route baseline evidence; manually inspect truncation, position and readable content.
- Where a failing CSS issue occurs, record baseline screenshot/selector/width, add failing responsive assertion, make minimal FE layout correction, then rerun the affected route at all widths plus shared layout regression.
- TDD order: failing Playwright viewport assertion를 먼저 추가해 현재 overflow/clipping을 재현하고, CSS/component fix 뒤 `npm run test:viewport`를 green으로 만든다. Existing `npm run test`는 FE unit/component 회귀로 유지한다.
- Distinguish layout failure from missing application states/API fixture issues; detailed state coverage goes to TASK-020.02.
- Save sanitized results and screenshots in `docs/evidence/TASK-020.01.md`; remove real data from test environment, and link unresolved accessibility or device behavior to `.020.03/.04`.

## 구현 경계

- QA harness tests should assert width/visibility and major contract geometry, not duplicate all existing component tests.
- Existing API-006/017/018 fixtures/mocks can render screens. This task does not alter API route, Session status, Processing behavior or meeting domain state.
- CSS changes remain mobile-first and localized to affected screen/shared shell components. Do not introduce page-level `overflow-x:hidden` solely to suppress child overflow: fix the offending element/container and retain visible content.
- Do not change typography/color/design system tokens to make a viewport assertion pass; that requires review in a follow-up design task if needed.

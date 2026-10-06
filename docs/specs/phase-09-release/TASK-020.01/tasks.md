# 실행 작업

## 단계

- TASK-004.05/#18 및 TASK-014.04/#55 routes/surfaces를 확인한다. 의존: 직접 선행 route 구현. 완료 결과: SCR-001~012마다 검증 가능한 route와 대표 content를 지정한다.
- `@playwright/test` stable dependency, `playwright.viewport.config.ts`, `npm run test:viewport` 및 Vite `webServer` 설정을 추가한다. 의존: frontend workspace. 완료 결과: Chromium/WebKit browser binaries 설치 및 local Vite route start가 재현된다.
- 360/375/390/430/768/1280px viewport를 정의하고 root scroll-width 및 bounding-box checks를 작성한다. 의존: route inventory. 완료 결과: 360px overflow 시 red가 되는 layout assertion이 있다.
- SCR-001~012와 SCR-004 modal/SCR-006 drawer에 long/synthetic content fixtures를 연결한다. 의존: route map 및 synthetic data. 완료 결과: 모든 화면을 same responsive matrix로 재현한다.
- baseline browser viewport matrix와 screenshots/DOM measurements를 수집한다. 의존: existing UI baseline. 완료 결과: 실패 화면과 정확한 breakpoint/selector가 증거로 확인된다.
- 재현 결함마다 실패 assertion을 유지하며 mobile-first CSS/component layout을 최소 변경한다. 의존: baseline overflow failure. 완료 결과: 360px failure가 해결되고 content가 숨지 않는다.
- 영향된 screen 및 shared shell에 전체 matrix를 재실행한다. 의존: layout fix. 완료 결과: target route 및 공유 navigation/modal/detail 모두 non-overflow다.
- Evidence를 작성하고 `TASK-020.02/.03/.04`에 넘길 state/a11y/device defect를 연결한다. 의존: matrix run 완료. 완료 결과: sanitized screenshot/result와 follow-up ID가 남는다.

## 의존 관계

- 선행 routes와 browser command 확인 전에 viewport pass/fail을 선언하지 않는다.
- Route fixtures는 한 번 freeze하고 각 viewport에서 같은 data/route state를 재사용한다.
- UI fix 전에 failing width/selector evidence를 수집한다.
- 수정 뒤 같은 failure test와 shared layout 영향 matrix를 재실행한다.
- Evidence 작성은 최종 matrix와 fixture cleanup 이후 완료한다.

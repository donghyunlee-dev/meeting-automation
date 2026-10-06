# 주요 화면 360px 반응형 검증

## 목표

PRD SCR-001~012 주요 화면과 화면 내부 modal/drawer가 360 CSS px부터 tablet/desktop까지 mobile-first layout을 유지하고 수평 페이지 overflow나 핵심 content 잘림이 없는지 검증한다. 자동 viewport/browser QA와 실제 route fixture를 사용해 화면별 결과를 남긴다.

PRD v1.8.1 (2026-10-06), `TASK-020.01`, `SCR-001~012`, `NFR-001`, `NFR-002`를 구체화한다. Issue [#73](https://github.com/donghyunlee-dev/meeting-automation/issues/73). 선행은 TASK-004.05 Issue #18 및 TASK-014.04 Issue #55다.

## 범위

- Home, New Meeting, Recording, End Confirm, Processing, Review, Share, Complete, Meetings, Meeting Detail, Settings, Participants의 responsive layout
- SCR-004 modal/bottom sheet, SCR-006 transcript drawer 및 장문 content/validation 안내
- 360/375/390/430 CSS px mobile, 768 tablet, 1280 desktop viewport
- page-level horizontal overflow, 주요 요소 bounding rect, clipping/truncation, wrapping, long Korean/string content, nav/action reachability 확인
- PRD mobile content max-width `640px`, 작은 화면 좌우 padding `16px` 및 safe-area bottom action 배치를 large viewport까지 확인
- synthetic app/meeting/participant/transcript data만 사용한 browser viewport screenshots 및 sanitised Evidence

## 비범위

- API/data state별 Loading/Empty/Error/Ready 조합의 전수 검사 (`TASK-020.02`)
- 44px touch target, keyboard-only, screen reader, focus, reduced motion 기준 (`TASK-020.03`)
- 실제 Android/iOS Safari/Chrome OS chrome, keyboard, notch safe area 및 전체 녹음 회귀 (`TASK-020.04`)
- 기능/색상/typography redesign. Overflow 원인이 실제 UI 결함이면 최소 layout fix만 이 task에서 수행하고 나머지는 후속 Issue로 분리
- 실제 user/meeting data, audio, Transcript, personal Email, Secret를 screenshot/Evidence에 표시

## 검증 규칙

- Browser viewport 값은 CSS pixel 단위로 고정한다. 브라우저 zoom 100%, deviceScaleFactor는 screenshot 비교 간 고정한다.
- 360 CSS px에서 `document.documentElement.scrollWidth <= document.documentElement.clientWidth`를 확인한다. 이는 page-level 수평 scroll의 1차 기계 판정이며, 개별 control/label가 가려지지 않았는지도 bounding rect와 시각 결과로 확인한다.
- 내부에 의도된 horizontal scroll component가 PRD에 정의되어 있지 않으므로 page body/root의 overflow-x는 허용하지 않는다. 긴 Korean meeting title, participant name, long email placeholder, error copy, Transcript line 및 Action item text는 자연스럽게 wrap/clamp되어 control과 viewport를 밀어내지 않아야 한다. Email 실제값은 fixture domain만 사용한다.
- 768/1280에서는 content max-width 640px 이하와 centered single column/container 기준을 확인한다. Recording timer/action area와 bottom nav가 mobile layout 밖으로 튀지 않고 desktop에서 의미 있는 spacing을 유지한다.
- viewport screenshot은 확인 증거이며 pixel-perfect golden diff threshold를 새로 도입하지 않는다. 구현 중 baseline이 없으면 screenshot을 numeric similarity score로 판정하지 않는다.
- SCR-005 failure state는 24h notice/actions가 viewport 안에 맞는지 단일 대표 fixture로 확인하되 state matrix exhaustiveness는 TASK-020.02 범위다.

## 수용 기준

- SCR-001~012 각 화면이 360/375/390/430 px에서 page-level horizontal overflow 없이 렌더링된다. 화면 내부로 연결되는 SCR-004 modal/SCR-006 drawer도 검사한다.
- 핵심 title, field, form validation, Recording timer/end action, Processing status/failure action, Review speaker/action item, Share/Complete controls, history/detail/settings/participant controls가 360px에서 화면 안에 표시되고 잘리지 않는다.
- 대표 긴 synthetic Korean string/validation copy/empty fixture가 wrap/clamp 규칙을 유지하고 가로 overflow를 만들지 않는다.
- 768/1280에서 content max-width 640px, responsive centering 및 360 minimum 대응이 확인된다.
- 테스트가 route, viewport, browser, build, fixture를 식별하고 실패가 있으면 screenshot/selector/DOM overflow 근거가 남는다.
- Evidence/screenshot에 실제 개인 데이터, transcript, Audio, email address, Secret 또는 backend provider 오류 원문이 없다.
- 테스트 후 fix가 있으면 변경된 화면 및 재사용 shared layout 영향을 같은 matrix에서 재검증한다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [SCR-002/New Meeting API 연결](../../phase-03-recording/TASK-004.05/spec.md)
- [SCR-009/010 Meetings and Detail](../../phase-07-history/TASK-014.04/spec.md)

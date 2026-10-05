# Processing 화면 및 polling 연결 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `SCR-005`, `API-010`, `TASK-006.07`
- 선행 TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): 공통 `{data}` envelope, API-010 snapshot 및 safe failure shape
- 본 TASK Issue [#33](https://github.com/donghyunlee-dev/meeting-automation/issues/33)
- UI source: `docs/product/ui-design.md` Processing Stepper, SCR-005
- API source: `docs/product/api-spec.md` common response, API-009/010, polling guidance
- Data source: `docs/product/data-spec.md` Session과 Transcript/Review response

## 변경 경계

- Frontend Processing route/page: Session ID를 받아 API-010 연결, 화면 수명주기와 terminal route 전환 소유
- API client/query layer: common response/error envelope 파싱, 취소 가능한 GET, polling scheduler
- Processing view model: Backend stage/status를 사용자용 stepper와 안내 상태로 매핑
- 접근성/공통 layout: 명시적 progress semantics, reduced-motion, Processing 중 Bottom Navigation 숨김
- Backend/API-010 response shape는 바꾸지 않는다. 참가자 roster 기능은 다루지 않는다.

## 구현 순서

1. 기존 route, API client/common envelope/error parser, React Query 또는 polling abstraction, Bottom Navigation 제어 방법을 확인한다. 결과: 기존 앱 경계에 맞는 변경 파일과 query owner를 식별한다.
2. API fixture를 이용해 stage/status mapping, REVIEW navigation gate, safe failure, error backoff, unmount cancellation의 Frontend test를 먼저 작성한다. 결과: 수용 기준별 실패 테스트가 준비된다.
3. API-010 typed response/error mapper와 bounded polling lifecycle를 구현한다. 결과: 최대 하나의 요청만 실행되며 terminal/unmount에서 중단된다.
4. 네 단계 stepper, 대기/실패 안내, accessibility progress, reduced-motion 및 Bottom Navigation 숨김을 구현한다. 결과: SCR-005 화면이 API snapshot에 동기화된다.
5. API-009 성공 경계에서 Session ID를 전달해 route를 연결한다. REVIEW와 ID 일치 확인 후 Review로 한 번 이동하고, API 원문을 보여주지 않는지 통합 확인한다. 결과: 처리 완료/실패까지 사용자 흐름이 이어진다.
6. Frontend 단위·통합 테스트와 수동 QA를 수행한다. 결과: 단계 전이, polling 취소, 안전 안내, 접근성 증거가 기록된다.

## 검증 접근

Frontend 프로젝트의 확인된 test/lint/build 명령을 사용한다. 현재 저장소 checkout에는 Frontend package manifest가 포함되어 있지 않으므로 구현 시 실제 package scripts를 확인한 후 명령을 기록한다. 테스트 명령을 임의로 만들어 문서의 필수 명령으로 가정하지 않는다.

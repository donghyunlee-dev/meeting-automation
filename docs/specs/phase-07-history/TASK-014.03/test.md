# 검증 계획

## 자동화 테스트

저장소의 FE test runner/component/router 환경을 사용한다. 정확한 실행 명령은 FE package 설정 확인 후 implementation 단계에서 고정하며 Provider API는 fixture/mock response를 사용한다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR001-RECENT-LIMIT | Home API fixture 7개, `limit=5` 호출 | endpoint query 5, 화면에는 최신 5개만 표시 | API client/component test |
| SCR001-CONTENT | 최근 회의 fixture | 제목·일시·참석자 수를 표시하고 email/status badge를 표시하지 않음 | Home component test |
| SCR001-EMPTY | 빈 `items` | 최근 목록 위치에 짧은 안내, `새 회의 시작` CTA 유지 | Home component test |
| SCR001-LOADING | API pending | 최근 목록 영역에 loading UI 표시 | Home component test |
| SCR001-ERROR-RETRY | API 502 후 retry click | 원문 오류 없이 안내 표시, 동일 `limit=5` GET 한 번 재요청 | Home interaction test |
| SCR001-ALL-MEETINGS | 전체 보기 action | `/meetings` route로 이동 | Router test |
| SCR009-DEFAULT-LIMIT | Meetings route 진입 | API-017 기본 최대 100 요청 및 결과 렌더링 | API hook/page test |
| SCR009-EMPTY | 빈 `items` | 화면 empty 안내와 Bottom Navigation 표시 | Page component test |
| SCR009-LOADING-ERROR | pending, 502, retry | Loading 및 safe Provider error 상태; retry가 GET 재호출 | Page interaction test |
| SCR009-STATUS-OMISSION | Meeting item을 Home/Meetings에 렌더링 | 상태/진행/완료/활성 badge 없음 | DOM assertion |
| SCR001-SCR009-A11Y | 키보드 tab 및 accessibility query | CTA, 전체 보기, retry에 접근 가능한 이름과 focus | component/a11y assertions |

## User handoff (not a development gate)

- After local setup, the user verifies meeting creation, processing, review/save/share, and the Home and `/meetings` lists.
- Real Provider accounts, microphone/device, meeting-room use, and manual 360px viewport checks remain user validation; they are not completion evidence for this task.

## 릴리스 확인

- 프로젝트 정의 FE unit/component/router tests와 lint/build를 실행하고 Evidence 문서에 결과를 남긴다.
- Real API, Provider, and device smoke checks happen after handoff. Development gates are fixture-based focused tests, lint/build, exact-head review, and applicable CI.

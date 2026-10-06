# 주요 화면 비동기 상태 검증 계획

## 자동화 테스트

실행 위치는 `frontend/`이며 현재 package 명령은 `npm run test`, `npm run lint`, `npm run build`다. Vitest/jsdom + Testing Library로 state component 및 화면/API fixture 연결을 검사한다. TASK-020.01의 `npm run test:viewport`는 구현된 route의 대표 상태 copy를 viewport에서 확인하는 smoke에만 사용한다. 계획 단계에서는 명령을 실행하지 않는다.

| ID | 조건/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| ASYNC-INITIAL | 화면별 pending query fixture 렌더 | 해당 영역에 Loading 표시, 화면 action/input 계약 불변 | component assertion |
| ASYNC-READY | 정상 API-001/002/003/010/017/018/019 fixture resolve | 대응 화면 Ready data 표시 | screen test |
| ASYNC-EMPTY-LIST | API-017/003/018-compatible list response `items: []` | list empty 안내와 해당 화면의 create/start action 제공 | list test |
| ASYNC-EMPTY-FILTER | 데이터는 있으나 client-side filter 0건 | “전체 목록 비어 있음”과 구분되는 검색 결과 안내, filter clear 가능 | filter test |
| ASYNC-EMPTY-RECENT | Home API-017 결과 0건 | 최근 목록 영역만 Empty, 새 회의 이동 유지 | Home test |
| ASYNC-ERROR-SAFE | 공통 error fixture에 `message`, `details`, traceId, secret-like raw text | 정적 안전 copy/code mapping만 보이며 raw 값은 DOM에 없음 | DOM text assertion |
| ASYNC-GET-RETRY | transient GET 실패에서 사용자가 retry 클릭 | 요청 1회 추가, 성공 시 Ready 전환; 자동 재요청 없음 | mock call count |
| ASYNC-STALE | Ready draft/data 뒤 background refetch 실패 | 기존 data/draft 보존, 비차단 경고 노출 | form/list state assertion |
| CONFIG-NULL-PROVIDER | API-001 `provider:null, configured:false` | generic Empty/Error 대신 Document 설정 선택/연결 안내 및 올바른 Settings 진입 | route/action assertion |
| ERROR-404 | API-018/참석자 detail 대상 404 | Empty로 속이지 않고 안전한 not-found 안내 및 돌아가기 경로 | detail test |
| MUTATION-UNKNOWN | API-009/012/015 mutation 결과 timeout/unknown | 자동 POST/PUT 재호출 없음, 해당 기능의 안전한 상태조회 경로 안내 | mutation call count |
| DELIVERY-RETRY-GATE | API-016에 성공/진행/FAILED retryable true/false fixture | `FAILED && retryable=true`만 명시 retry 가능 | action/assertion matrix |
| PROCESSING-STATES | API-010 Waiting/Running/Completed/Failed 응답 렌더 | Processing lifecycle을 generic Empty/Ready와 혼동하지 않음 | state transition test |
| AUDIO-FAILURE | retry 허용, 보존 만료, download complete/not complete fixture | TASK-017.03 action만 표시; 실패 기록에 stage/disposition/시각, attendee email/slack 없음 | synthetic document/outbound spy |
| FORM-DRAFT-PRESERVE | New Meeting/Review dirty form 중 query 재요청/에러 | 사용자 입력과 Review draft 유지 | controlled form assertion |
| SETTINGS-HEALTH | API-019 일부 미구성/미연결 contributor fixture | 영역별 미설정·연결 오류·정상 상태 구분, Secret 미표시 | Settings test |
| VIEWPORT-STATE-SMOKE | 대표 mobile Loading/Empty/Error/Ready route를 Playwright에서 검사 | 360px에서 copy/action 접근 가능, page horizontal overflow 없음 | Playwright report/screenshots |

## 수동 QA

- Home, Meetings, New Meeting, Settings, Participants에서 synthetic network delay/error를 설정해 Loading/Empty/Error/Ready 전환과 다음 동작을 확인한다.
- Review에 입력한 synthetic draft를 유지한 채 재조회 오류를 발생시켜 작성 내용이 그대로 남는지 확인한다.
- Processing 변환 실패 후 retry/download/실패 종료 화면을 확인한다. 실패 기록은 회의 문서에 나타나고 참석자 Email/Slack 전송 기록은 생성되지 않아야 한다.
- provider 미선택 상태에서 화면 안내가 실제 Settings 연결 선택으로 이어지는지 확인한다. 자동 Provider 선택이 일어나지 않아야 한다.
- 사용자에게 표시되는 문구 및 screenshot에 실제 Error.message, provider response, Email, Secret, Audio, Transcript가 없는지 검사한다.

## 완료 증거

- 파일: `docs/evidence/TASK-020.02.md`
- 기록: commit/build 식별자, 실행 명령, case ID별 pass/fail, screen/state/fixture ID, 안전한 screenshot 또는 DOM 근거, 미해결 결함 Issue
- 성공: 모든 applicable state와 특별 분류(provider null, 404, Processing failure, mutation unknown, delivery retry gate)가 검증되고 비적용 상태가 spec 기준을 따른다.
- 실패: Empty/Error가 구분되지 않음, raw API detail 노출, dirty data 유실, 불명 mutation 중복 실행, 허용되지 않은 retry, Audio 실패를 참석자에게 전송, Loading/Empty/Error에서 복구 동작 불가.

# 주요 화면 비동기 상태 구현 계획

## 의존성과 기준

- TASK-020.01 Issue #73: screen/viewport 및 synthetic fixture 범위
- TASK-016.03 Issue #59: API error mapping, safe message, draft 보존, retry 금지 경계
- TASK-014.03/.04: Home, list, detail 조회/empty 계약
- TASK-017.03: Processing Audio 실패/명시 재시도/다운로드/실패 종료 계약
- PRD API-001~022: 기존 API 응답/오류 계약. 이 작업에서 Backend 계약을 바꾸지 않는다.

이 작업은 FE 상태 전이 검증이다. 화면/API ownership을 분리해 상태 종류와 fixture를 먼저 정하고, 공유 AsyncState UI/상태 resolver를 구현한 다음 실제 화면 query를 연결한다. 첫 통합 화면에서 API error envelope와 UI mapping을 맞춘 뒤 나머지 화면에 확장한다.

## 변경 대상

| 위치 | 책임 |
|---|---|
| `frontend/src/components/async-state/` | 재사용 Loading, Empty, Error UI 및 상태 union. 상태별 접근 가능한 heading/status 영역과 영역별 재시도 전달 |
| `frontend/src/lib/api/` | 공통 성공/error envelope parse 및 안전한 `ApiError` normalize. raw `details`/provider 본문은 component props로 전달하지 않음 |
| `frontend/src/features/**/` | 기존 screen query hook/화면에서 공통 상태 소비. 각 feature에 재시도/empty action 소유 |
| `frontend/src/test/fixtures/` | 합성 API 응답: 빈/정상 목록, provider 미선택, retryable/non-retryable 오류, 404, processing 실패 |
| `frontend/src/**/*.test.tsx` | AsyncState component, 화면별 state matrix, 입력 보존 및 재시도 경계 테스트 |
| `docs/evidence/TASK-020.02.md` | screen/state/test 결과와 비민감 Evidence 요약 |

현재 FE package에는 React 19/Vitest/Testing Library만 있고 아직 route/feature module은 scaffold 수준이다. 구현 시작 시 실제 진행분을 확인하고 위 위치는 기존 repo convention에 맞춰 추가한다. 페이지 라우팅 전면 구현 또는 상태가 아닌 기능 범위 확장은 이 task에 포함하지 않는다.

## 데이터/화면 계약

- `Loading`/`Empty`/`Error`/`Ready`는 서버 상태가 아니라 query result의 화면 표현이다. Processing은 API-010의 processing/session lifecycle을 유지한다.
- Query 상태 판정은 `data` 존재 여부만으로 하지 않는다. `items.length === 0`는 목록 Empty, `provider === null && configured === false`는 Settings 연결 유도, 404는 리소스 없음 오류다.
- stale data를 가진 background refetch error는 기존 Ready 내용을 유지하고 비차단 경고+재시도 action을 표시한다. 초기 요청 실패만 blocking Error 화면으로 나타낸다.
- 오류 normalize는 `error.code/category/retryable/traceId`를 내부 view model로 변환한다. 사용자 copy는 정적 code map이며 서버 `message/details`를 render하지 않는다.
- Retry는 query GET을 사용자 클릭으로 재호출한다. mutation은 결과가 불명확하면 재호출하지 않고 최신 API-010/API-015/API-016 state 조회 등 해당 feature의 기존 안전 경로로 안내한다.
- API-016은 `status === FAILED && retryable === true` 행만 retry 가능. Audio conversion 재시도는 TASK-017.03에서 정의한 보존 시간 및 allowed action이 유효한 때에만 표시한다.
- 성공 response의 구조는 해당 API response가 정의한 바를 사용하며 공통 empty sentinel을 Backend에 추가하지 않는다.

## 구현 순서

| 단계 | 소유 | 선행 이유 | 결과 |
|---|---|---|---|
| 화면-state 표와 synthetic fixture를 테스트 우선으로 고정 | FE | 혼합 상태 의미를 구현 전에 고정해야 함 | failing component/state contract tests |
| 공통 error normalization 및 AsyncState primitive 작성 | FE | page별 문구가 raw API payload를 직접 해석하지 않도록 공통 경계 선행 | component tests green, 안전한 props |
| Meetings/Home/Participants/Detail query 상태 연결 | FE | list/detail의 empty 유형과 GET 재시도에서 공통 패턴 검증 | 해당 화면 integration tests |
| New Meeting/Recording/Processing/Review/Share/Complete/Settings 적용 | FE | 첫 read flow에서 검증된 공통 상태를 mutation-sensitive 화면으로 확장 | 각 화면별 contract tests |
| state matrix 및 regressions 실행, Evidence 작성 | FE, QA | 전체 route의 적용 상태/비적용 근거 확인 후 완료 증거 생성 | test/lint/build 결과 및 Evidence |

## 검증 접근

- 현재 알려진 명령은 `frontend/`에서 `npm run test`, `npm run lint`, `npm run build`다. 컴포넌트/화면 계약은 Vitest + jsdom + Testing Library로 자동화한다.
- `test.md` 사례별로 state × screen group matrix를 실행한다. 반복되는 공통 UI를 모든 화면에서 같은 snapshot으로 복사하지 않고, UI primitive 단위 검사와 screen integration mapping을 분리한다.
- TASK-020.01 Playwright viewport harness는 필요한 대표 Loading/Empty/Error/Ready copy의 360px 노출만 통합 확인한다. pixel golden 비교와 state 전수 검증을 재작성하지 않는다.
- 수동 QA는 실패/empty 안내의 다음 동작, 기존 draft 보존, 민감 정보 비노출, Audio 실패 회의록 미공유를 합성 계정으로 확인하고 `docs/evidence/TASK-020.02.md`에 결과를 기록한다.

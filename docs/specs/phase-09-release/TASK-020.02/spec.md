# 주요 화면 비동기 상태 일관성 검증

## 작업 식별 정보

- 작업: TASK-020.02
- 상위 작업: TASK-020 Mobile UI / Accessibility Polish
- 단계: Phase 9 — Release
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: SCR-001~012, API 공통 오류 envelope, API-001~022, FR-027~029
- 선행 작업: TASK-020.01 Issue #73, TASK-016.03 Issue #59
- GitHub Issue: [#74](https://github.com/donghyunlee-dev/meeting-automation/issues/74)

## 결과

각 주요 화면에서 실제 비동기 요청의 Loading, Empty, Error, Ready 상태를 일관된 공통 UI 패턴으로 표시하고, 상태 전환·복구·기존 사용자 입력 보존을 컴포넌트 및 통합 테스트로 검증한다. 각 화면에 적용할 수 없는 상태는 억지로 렌더링하지 않고 표의 비적용 사유를 유지한다.

## 화면별 적용 상태

| 화면 | Loading | Empty | Error | Ready | 계약/조건 |
|---|---|---|---|---|---|
| SCR-001 Home | 최근 회의 조회 | 최근 회의 0건 영역만 | 최근 목록 재조회 | 최근 최대 5건 | 새 회의 이동은 목록 오류와 독립 |
| SCR-002 New Meeting | 설정·참석자 초기 조회 | 참석자 목록 0명 안내 | 조회 실패 | 필수값 선택 가능 | `provider:null`은 empty가 아니며 설정 경로 안내 |
| SCR-003 Recording | Session 생성 중 | 비적용 | 생성·chunk 전송 실패 인라인 | 녹음 중 | 미확정 chunk 유실을 숨기지 않고 PRD 업로드 계약을 따른다 |
| SCR-004 End Confirm | 비동기 조회 없음 | 비적용 | 종료 요청 실패 시 확인 가능한 녹음 상태 안내 | 확인 sheet | 종료 실패 후 녹음 화면/사용자 선택 보존 |
| SCR-005 Processing | 최초 조회 및 처리 중 | 비적용 | 조회 실패와 terminal processing failure 구분 | 처리 결과 또는 Review 진입 | Processing의 Waiting/Running/Completed/Failed는 별도 lifecycle |
| SCR-006 Review | Review 데이터 조회 | 유효 계약상 빈 transcript 영역만 | 조회/저장/충돌 안내 | 편집 가능 데이터 | draft 입력 보존, 최신 버전 재조회는 명시적 복구 |
| SCR-007 Share | config 및 공유 준비 | 선택 가능한 recipient가 없음 | 준비·document 저장 오류 | 선택 가능한 공유 action | provider 미설정 시 연결 설정 화면으로 안내 |
| SCR-008 Complete | 결과 조회/갱신 | publish 결과 없음은 Error/충돌로 분류 | 전체/부분 실패를 채널별로 표시 | 결과별 성공·진행 표시 | 성공/진행 delivery 재전송 금지, `retryable=true` 실패만 허용 |
| SCR-009 Meetings | 목록 조회 | `items: []` | 재조회 action | 검색/필터 가능한 목록 | client-side filter의 결과 0건은 query-specific empty |
| SCR-010 Meeting Detail | 상세 조회 | 필수 상세가 누락되면 empty로 간주하지 않음 | 404/누락 provider resource 구분 안내 | 읽기 전용 상세 | 민감 provider 오류 원문 숨김 |
| SCR-011 Settings | config/health 조회 | 비적용 | API 오류 | 영역별 설정/연결 표시 | document `provider:null`는 설정 미선택 상태, 연결 경로 제공 |
| SCR-012 Participants | 목록 조회 | `items: []` | 재조회 action | 검색/편집 가능한 목록 | 검색 결과 0건과 전체 목록 0건을 구분 |

SCR-004의 동기적 확인 UI는 generic request state 대상이 아니다. SCR-005 처리 lifecycle은 Loading/Empty/Error/Ready와 별도이며, 실패 원인은 기존 safe error mapping으로 표현한다. Audio 처리 실패 시 회의 문서에 실패를 기록하고 참석자에게 Email/Slack을 보내지 않는다. 명시적인 변환 재시도, 원본 다운로드 확인 후 실패 종료, 만료 정리는 TASK-017.03 계약을 그대로 소비한다.

## 공통 UI 계약

- 공통 `AsyncState` 계층은 `loading | empty | error | ready`를 표현하되 empty data와 `provider:null/configured:false`, resource not found, processing failed를 같은 상태로 합치지 않는다.
- Loading은 해당 콘텐츠 영역에 표시하고 기존 입력/draft를 unmount하거나 초기화하지 않는다. 재조회 중 stale ready data가 있으면 보존하며 화면이 깜박이지 않게 한다.
- Empty에는 상태 제목과 해당 영역에서 가능한 다음 동작을 제공한다. 목록 자체가 비어 있음과 필터 결과 없음은 다른 안내를 쓴다.
- Error는 공통 envelope의 `code`, `category`, `retryable`, `traceId` 중 화면에 필요한 안전한 값만 매핑한다. `message`, `details`, provider response, raw exception, Secret, Audio/Transcript 본문은 직접 출력하지 않는다.
- Retry 버튼은 멱등 GET 재조회 등 안전한 복구에만 제공한다. POST/PUT timeout이나 결과 불명 요청을 자동 재전송하지 않는다. Delivery 재시도는 API-016 허용 행에만 둔다.
- `provider:null`은 사용자가 연결 대상을 정하지 않은 설정 상태다. 자동 provider 선택 또는 generic empty 표시를 하지 않고 적절한 Settings 연결 안내로 연결한다.
- 모든 상태 문구는 텍스트와 아이콘/role을 함께 써 색만으로 구분하지 않는다. 접근성 세부 적합성은 TASK-020.03 범위다.

## 제외

- TASK-020.01 viewport/overflow 매트릭스 재실행 전수, TASK-020.03 touch/keyboard/screen reader audit, TASK-020.04 실기기 회귀
- Backend API/envelope/error code 변경, 새 retry endpoint, 자동 retry 정책
- 페이지 전면 재설계, API가 제공하지 않는 상태 생성
- 실제 계정/개인정보를 포함한 fixture 및 screenshot

## 완료 기준

- 12개 화면의 적용/비적용 상태와 Empty 하위 유형이 위 계약과 일치한다.
- 공통 state UI와 상태 조합 전환이 테스트되며 각 applicable state에 최소 한 사례가 있다.
- 조회 중 기존 draft 보존, safe GET retry, mutation 불명 응답 중복 방지, `provider:null` 설정 유도가 확인된다.
- API 오류의 내부 원문·Secret·개인 Email·Transcript·Audio가 사용자 화면이나 test Evidence에 노출되지 않는다.
- SCR-005 변환 실패가 실패 기록/회복 action으로 연결되고 실패 회의록이 참석자에게 공유되지 않는다.
- [검증 계획](./test.md)의 자동/수동 사례와 `docs/evidence/TASK-020.02.md` 결과가 연결된다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [TASK-020.01 반응형 기준](../TASK-020.01/spec.md)
- [TASK-016.03 오류 복구 계약](../../phase-07-operations/TASK-016.03/spec.md)
- [TASK-017.03 Audio 변환 실패 복구](../../phase-07-security/TASK-017.03/spec.md)

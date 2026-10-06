# 주요 화면 오류 안내 및 복구 검증 설계

## 작업 식별 정보

- 작업: TASK-016.03
- 상위 작업: TASK-016 Error / Admin Slack
- 단계: Phase 7 — History / Settings / Operations
- PRD 기준: PRD-MA-001 v1.7.0, 2026-10-05
- 관련 기준: SCR-003~008, FR-027, API common error envelope
- 영역: Frontend, Integration
- 선행 작업: TASK-016.01 (#57), 화면별 구현 계약
- GitHub Issue: [#59](https://github.com/donghyunlee-dev/meeting-automation/issues/59)

## 결과

SCR-003 Recording부터 SCR-008 Complete까지 대표 API 오류와 복구 경로를 FE integration/component tests로 검증한다. 기존 개별 TASK가 소유한 화면/기능을 다시 구현하지 않고 공통 error envelope가 올바른 안내, 유지할 데이터 및 허용된 다음 동작으로 이어지는지 확인한다.

## 화면별 오류 계약

| 화면 | 실패 입력 | 사용자 안내/복구 |
|---|---|---|
| SCR-003 Recording | microphone denied/unsupported, chunk upload validation/conflict, network failure | 권한/지원 안내를 분리하고 안전한 재전송 정책만 사용한다. 미전송 데이터가 있으면 유실을 숨기지 않는다. Provider 원문은 표시하지 않는다. |
| SCR-005 Processing | API-010 transient network error, PROCESSING_FAILED, SESSION_NOT_FOUND | GET polling 오류만 제한된 backoff로 조회한다. pipeline 재시작 API가 없으므로 API-009를 재호출하지 않는다. terminal missing session은 새 회의 경로를 안내한다. |
| SCR-006 Review | 422 REVIEW_VALIDATION_FAILED, 412 SESSION_VERSION_CONFLICT, 409 SESSION_STATE_CONFLICT | issues를 관련 영역에 연결하고 draft를 보존한다. 충돌 시 최신 API-010을 조회한다. 민감 원 입력은 오류에 넣지 않는다. |
| SCR-007 Share | API-001 document unconfigured, API-014 validation, API-015 network/5xx | null/unconfigured는 Settings 경로를 안내한다. validation은 issue로 돌려보내 draft를 보존한다. POST 응답 불명 시 API-010 확인 전 중복 접수하지 않는다. |
| SCR-008 Complete | API-010 read failure, API-016 404/409, retryable false Delivery | 상태를 다시 조회하고 안전한 문구를 표시한다. FAILED && retryable=true 한 행만 명시 재시도한다. 성공/진행/결과 불명 Delivery는 재전송하지 않는다. |

공통 원칙은 API error code/category/traceId를 안전하게 소비하고 사용자 문구에 내부 exception, provider body, Secret을 노출하지 않는 것이다. Mutation 응답이 불명확한 timeout에는 자동 재전송하지 않는다. Review draft와 다른 성공 Delivery는 오류 전환 뒤에도 보존한다.

## 범위와 제외

- 대표 error envelope, UI state mapping, 보존/복구 action을 integration/component tests로 검증하고 확인된 FE gap만 보완한다.
- TASK-005.08 Recording, TASK-006.07 Processing, TASK-010.03 Share, TASK-013.02 Complete 기능은 해당 spec을 소비한다.
- 새로운 Backend error code, retry endpoint, 자동 retry, 공통 error page replacement, History 화면 오류 흐름은 범위 밖이다.

## 완료 기준

- 다섯 화면의 대표 오류 fixture에 맞는 안전한 안내와 복구 action이 연결된다.
- 입력/Review draft 및 독립적으로 성공한 결과가 오류 뒤 보존된다.
- API-009/API-015/API-016 mutation은 timeout/불명 응답에서 자동 반복되지 않는다.
- Retry 가능한 Delivery만 수동 재시도되고 404/409 이후 최신 상태를 조회한다.
- raw provider/body/Secret/Transcript/Audio/email이 오류 UI에 표시되지 않는다.
- 접근 가능한 오류 announcement/focus/retry control이 검증된다.
- [검증 계획](./test.md)의 각 사례가 완료 기준을 덮는다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

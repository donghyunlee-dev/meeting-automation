# 녹음 종료와 API-009 연결 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-006`, `SCR-004`, `API-009`, `TASK-005.08`
- 선행 TASK-005.01 Issue [#19](https://github.com/donghyunlee-dev/meeting-automation/issues/19): end confirm callback
- 선행 TASK-005.02 Issue [#20](https://github.com/donghyunlee-dev/meeting-automation/issues/20): stop/final duration/MIME
- 선행 TASK-005.03 Issue [#21](https://github.com/donghyunlee-dev/meeting-automation/issues/21): final Chunk 저장/nextSequence
- 선행 TASK-005.06 Issue [#24](https://github.com/donghyunlee-dev/meeting-automation/issues/24): flush/reconcile/no pending 계약
- 선행 TASK-005.07 Issue [#25](https://github.com/donghyunlee-dev/meeting-automation/issues/25): API-009 202/idempotency
- 본 TASK Issue [#26](https://github.com/donghyunlee-dev/meeting-automation/issues/26)

## 변경 경계

- Frontend end orchestration hook/use case: state guard, stop completion, final persistence, upload flush, API request, navigation
- API client: API-009 typed request/response 및 stable key
- Recording view/modal: busy/progress/upload/API error/retry rendering
- Processing route: 목적지 path/navigation contract만 연결; 화면/polling 구현은 후속 task
- Backend 변경 없음

## 구현 순서

1. predecessor callbacks, pending repository/upload flush, API-009 DTO 및 route contract를 대조한다. 결과: 실제 종료 metadata가 일관된 입력으로 모인다.
2. end confirm 중복, stop ordering, upload pending/failure, API key/retry/navigation test를 먼저 작성한다. 결과: 사용자 action 경계가 고정된다.
3. end action guard 및 stop/final append/persist completion wait를 구현한다. 결과: 종료보다 먼저 마지막 chunk가 누락되지 않는다.
4. upload flush/reconcile와 pending gate를 연결한다. 결과: 완료 ACK 없이는 processing request가 없다.
5. stable processing key/payload와 API-009 호출/retry/navigation을 구현한다. 결과: timeout/중복 클릭에도 job이 하나다.
6. busy/error/retry view state를 Recording UI에 연결한다. 결과: 실패 뒤 데이터를 지우지 않고 복구 action을 제공한다.
7. FE unit/component/integration tests, lint/build 및 API contract QA를 수행한다. 결과: 동작 순서 증거가 기록된다.

## 검증 명령

Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행한다. API-009 202 및 replay 응답을 contract mock으로 확인하고 end-to-end QA에서 실제 navigation을 확인한다.

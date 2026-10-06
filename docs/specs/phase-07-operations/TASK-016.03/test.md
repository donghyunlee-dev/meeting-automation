# 검증 계획

## 자동화 테스트

FE component/router/integration tests에서 deterministic API mocks를 사용한다.

| ID | 화면/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| ERR-SCR003-UPLOAD | upload timeout 또는 chunk 409 | 미전송 data/sequence 안내, process start 없음 | recording integration test |
| ERR-SCR005-POLL | API-010 GET 일시 실패 | bounded polling backoff, POST/restart mutation 없음 | processing test |
| ERR-SCR005-TERMINAL | PROCESSING_FAILED/SESSION_NOT_FOUND | safe stage 안내, pipeline 재호출 없음, missing session 새 회의 경로 | processing test |
| ERR-SCR006-VALIDATION | REVIEW_VALIDATION_FAILED issues | 관련 field/group에 error 연결, draft 보존 | review test |
| ERR-SCR006-CONFLICT | version/state conflict | 최신 API-010 조회, edits 보존, 자동 mutation 없음 | review test |
| ERR-SCR007-UNCONFIGURED | provider null/configured false | Settings 안내, API-015 호출 0회, Review snapshot 보존 | share test |
| ERR-SCR007-VALIDATION | API-014 422 | issue 표시, API-015 호출 0회 | share test |
| ERR-SCR007-AMBIGUOUS | API-015 timeout after request | API-010 reconcile, POST 재호출 없음 | publish integration test |
| ERR-SCR008-GATING | SENT/PENDING/SENDING/retryable false | 해당 delivery에 Retry 없음 | component assertion |
| ERR-SCR008-RETRY | FAILED/retryable true | user click 때 해당 delivery 하나에 API-016 | interaction test |
| ERR-SCR008-CONFLICT | API-016 404/409 | API-010 refresh, safe copy, 다른 delivery rows 보존 | retry integration test |
| ERR-SCR008-AMBIGUOUS | API-016 timeout | API-010 refresh/idempotent reconcile, 새 시도 없음 | retry test |
| ERR-REDACTION | error body에 provider text/Secret/PII/Transcript | 사용자 text/DOM에 원문 없음 | DOM assertion |
| ERR-A11Y | inline issue/page error/retry | accessible name/live announcement/focus 규칙 충족 | accessibility test |

## 수동 QA

- Mock flow로 Recording→Processing→Review→Share→Complete 오류 복구를 확인한다.
- 오류 전 Review edits와 이미 성공한 다른 Delivery row가 유지되는지 확인한다.
- Keyboard-only로 retry/Settings/new meeting/list return action을 사용할 수 있는지 확인한다.
- Network panel에서 timeout 뒤 위험 mutation이 중복 호출되지 않는지 확인한다.

## 릴리스 확인

- Repository-defined FE unit/integration tests, lint/build를 실행하고 docs/evidence/TASK-016.03.md에 기록한다.
- Evidence나 capture에 email/meeting body/Secret이 포함되지 않는지 검토한다.

# 작업 목록

## 사전 조건

- TASK-016.01 mapping and TASK-006.07, TASK-010.03, TASK-013.02 FE contracts are available.
- Tests use mock server/fixtures and never call real Providers.

## 구현 단계

- [ ] Screen-to-error matrix를 owner specs와 대조하고 validation/conflict/not-found/provider/timeout fixtures를 만든다.
- [ ] SCR-003/005 tests: upload error preservation, polling-only retry, terminal failure에서 API-009 재호출 없음.
- [ ] SCR-006/007 tests: 422 issue mapping, stale version refresh, provider null Settings route, ambiguous Publish reconcile, draft preservation.
- [ ] SCR-008 tests: FAILED/retryable Delivery만 action, 404/409 API-010 refresh, ambiguous retry response reconciliation.
- [ ] Safe message selector를 검증하고 Provider/exception/PII 원문이 UI에 없는지 확인한다.
- [ ] Error state의 accessible name/live region/focus 유지 및 keyboard recovery action을 검증한다.
- [ ] FE tests/lint/build 후 docs/evidence/TASK-016.03.md에 비민감 결과를 기록한다.

## 완료 확인

- matrix의 모든 row에 자동화 scenario가 있다.
- timeout 뒤 unsafe mutation이 재전송되지 않고 draft/independent result가 보존된다.
- git diff --check와 sensitive data review를 통과한다.

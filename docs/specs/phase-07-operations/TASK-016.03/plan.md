# 구현 계획

## 의존성

- TASK-016.01 API code/category mapping (#57)
- TASK-005.08 Recording interactions
- TASK-006.07 Processing/API-010 polling
- TASK-010.03 Review validation/Publish state
- TASK-013.02 Complete/Delivery retry UX
- PRD v1.7.0 (2026-10-05), SCR-003~008, FR-027
- GitHub Issue: [#59](https://github.com/donghyunlee-dev/meeting-automation/issues/59)

## 변경 대상

- FE fixtures: common error envelopes for validation, conflict, not found, provider failure, timeout/response loss.
- Screen error selectors: code/category to safe Korean copy and actionable control.
- Recording/Processing/Review/Share/Complete component tests and mock API flows.
- Only proven FE error handling gaps; API contract changes remain with owning task.
- Accessibility checks for inline issues, retry, live regions, focus preservation.

## 구현 순서

Error mapping and representative screen owners precede this cross-screen audit. Add a test matrix first and fill only tested FE gaps.

1. Map each screen/API call to existing owner spec and error contract.
2. Add component/integration tests for error envelopes, preserved data, safe copy and allowed actions.
3. Run mocked flows across five screens and identify mismatches.
4. Correct FE mapping/action only when tests show a gap; avoid duplicating owning task behavior.
5. Verify mutation call counts on timeout, polling behavior, explicit delivery retry gate, accessibility and redaction.
6. Run FE tests/lint/build and record evidence.

## 검증

Use API mocks/service worker or repository integration fixture. Do not invoke real recording devices or real Slack/Email endpoints. Cases are in [검증 계획](./test.md).

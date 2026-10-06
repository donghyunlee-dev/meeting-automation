# Document·Email·Slack E2E 작업 목록

- [ ] TASK-021.01 harness와 TASK-010~013 실제 publish/API/provider adapter/UI contract 선행 완료 여부를 확인한다. 의존: Issue #77, #44~#51, #8. 완료 결과: browser test route, provider HTTP client injection seam, API-010 Delivery projection 준비.
- [ ] failure-first test를 작성해 Document 저장 전 channel side effect 금지, auth/request shape, 독립 Delivery 상태, API-016 retry gate를 재현한다. 의존: interface inventory. 완료 결과: 각 핵심 scenario가 red로 확인된다.
- [ ] e2e-only loopback provider transport/mock fixture를 구성하고 unknown external endpoint fail-closed를 검증한다. 의존: Backend provider HTTP clients. 완료 결과: real provider adapters는 request 생성하고 실제 외부 network 없이 deterministic response 사용.
- [ ] Confluence Basic, Gmail OAuth/token/send, Slack webhook wire assertions 및 sanitized capture를 추가한다. 의존: transport. 완료 결과: 가짜 credentials만 사용하고 request semantics/redaction이 검증된다.
- [ ] Document-first success와 Document failure/corrected retry E2E를 browser UI/API-015로 구현한다. 의존: publish scenario. 완료 결과: Document가 한 번 저장된 후에만 channel adapter가 호출된다.
- [ ] 혼합 recipient/channel success/failure, notification disabled, API-010 state/display를 검증한다. 의존: async delivery tracking. 완료 결과: Email/Slack row는 서로 독립적이고 비선택 recipient는 전송되지 않는다.
- [ ] API-016 단건 retryable failure 및 nonretryable ambiguous result 시나리오를 구현한다. 의존: TASK-013.01/.02. 완료 결과: 선택 Delivery만 한 번 추가 호출되고 다른 Document/channel/recipient는 재전송되지 않는다.
- [ ] FE/BE build/unit 및 publish E2E를 두 번 clean run하고 Evidence 작성 및 fixture cleanup을 확인한다. 의존: 전체 scenario. 완료 결과: `docs/evidence/TASK-021.02.md`에 IDs/counts/result/cleanup만 기록된다.

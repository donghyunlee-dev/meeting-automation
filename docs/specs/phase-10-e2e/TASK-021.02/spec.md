# Document·Email·Slack E2E 및 독립 재시도 검증

## 작업 식별 정보

- 작업: TASK-021.02
- 상위 작업: TASK-021 End-to-End Acceptance
- 단계: Phase 10 — End-to-End Acceptance
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: FR-016~019, SCR-007~008, API-010/014~016, EXT-003~005, DEC-013~015
- 선행 작업: TASK-021.01 Issue #77; TASK-010.02 #44; TASK-010.03 #45; TASK-011.01/.02 #46/#47; TASK-012.01/.02 #48/#49; TASK-013.01/.02 #50/#51; Confluence Basic 결정 TASK-002.03 #8
- GitHub Issue: [#78](https://github.com/donghyunlee-dev/meeting-automation/issues/78)

## 결과

Review 확정 뒤 UI/API-015부터 Document 저장, Gmail API Email, Slack Incoming Webhook과 API-010 결과 UI까지 이어지는 E2E를 외부 network 없이 검증한다. 실제 provider adapter 및 publish/delivery orchestration은 사용하되 HTTP request는 test-only local mock transport로 격리한다. 각 channel Delivery가 서로의 성공/실패를 덮지 않고 명시 재시도 한 건이 독립적으로 수행되는지 확인한다.

## 통합 계약

1. SCR-007에서 선택된 Session roster ID와 `notificationEnabled`만 API-015로 제출한다. API-015는 `If-Match`와 `Idempotency-Key`를 요구하고 `202 PUBLISHING`을 응답한다.
2. Backend는 Provider 연결이 유효할 때 Confluence Document를 먼저 찾고(`externalSessionId=sessionId`) 없으면 한 번 생성한다. document URL/reference가 저장되어 성공한 뒤에만 선택된 Email recipients 및 설정된 Slack notification 전달을 시작한다.
3. Confluence Cloud 인증은 기존 승인 결정에 따라 account email + Atlassian API token으로 UTF-8 `email:token`을 Base64 인코딩한 `Authorization: Basic ...`을 사용한다. 계정 비밀번호/OAuth로 바꾸지 않는다.
4. Email adapter는 Gmail API + OAuth 2.0 refresh-token flow를 쓴다. `gmail.send` scope, Backend-only `client ID/secret`, refresh token, sender를 사용하며 선택된 각 recipient에게 개별 RFC 2822 message를 한 건씩 보낸다. 수신자별 API-010 Delivery 행으로 분리한다.
5. Slack adapter는 고정 Incoming Webhook URL로 한 번 POST하며 제목/날짜/document URL만 전송한다. UI에서 notification을 해제하면 호출하지 않는다.
6. 모든 결과는 같은 Session의 API-010 snapshot `document` 및 `deliveries[]`에서 조회한다. `SENT`는 Provider가 전송 요청을 받아들였다는 뜻이며 실제 inbox/channel 도착은 뜻하지 않는다.
7. `FAILED && retryable=true` 한 Delivery만 사용자가 API-016으로 재시도할 수 있다. 새 key는 새 사용자 시도마다 한 번 만들며 나머지 성공/진행/실패 delivery, 이미 저장한 Document 및 다른 channel에는 다시 요청하지 않는다.

## 실패 및 순서 경계

- Document lookup/create 실패 시 Session은 `DOCUMENT_FAILED`로 기록하고 Gmail message send 및 Slack notification POST는 0회다. API-019가 사전에 수행할 수 있는 부작용 없는 OAuth token refresh는 mail Delivery가 아니므로 이 조건과 구분한다. 문서 실패 회복은 최신 Session version 및 새 API-015 key로 수행하고 provider의 `externalSessionId` lookup이 기존 문서 중복 생성을 막는다.
- Email recipient 한 건의 명확한 `429`/수신 거절은 그 행만 retryable failure가 된다. 나머지 선택 수신자와 Slack 결과는 계속 독립 진행한다. OAuth refresh가 send 전 실패하면 미처리 recipient 결과는 실패하되 Slack은 독립 실행한다.
- Slack `429`는 명시 재시도 가능. Gmail/Slack `5xx`, 연결 timeout/응답 유실은 Provider가 이미 전송했을 수 있어 `retryable=false`; 재발송하지 않는다.
- Retryable 한 행 재시도는 API-016으로 해당 adapter 호출만 1회 수행한다. `SENT`, `PENDING`/`SENDING`, `retryable=false`는 호출되지 않는다. 동일 key replay는 첫 접수 응답만 replay하고 추가 전송을 만들지 않는다.
- publish idempotency replay는 document나 channel deliveries를 추가 생성하지 않는다. Confirmed Meeting에 Publish를 두 번 submit해도 provider side effect는 key/session/domain rules에 맞춰 수렴한다.

## Mock/보안 경계

- `npm run test:e2e:publish`는 TASK-021.01 Playwright/Vite/Backend harness를 재사용한다. Backend e2e profile에서 실제 `DocumentProvider`, Gmail `EmailProvider`, Slack `NotificationProvider` adapter를 주입하고, HTTP client transport는 loopback-only mock server에 연결한다.
- Adapter가 Basic/OAuth/Bearer/webhook request를 실제 코드로 구성하게 하며 high-level provider port를 전부 fake로 대체하지 않는다. Mock transport는 현재 구현 계약의 host/path/query/body/status를 관찰하고 fixture 응답을 반환한다.
- Confluence logical origin, OAuth token endpoint, Gmail REST endpoint, Slack webhook을 외부 network로 resolve하지 않는다. e2e profile은 loopback fake 이외 outbound 연결을 거부한다. production URL/HTTPS/provider validation 및 Runtime Config는 변경하지 않는다.
- Confluence, Google OAuth/Gmail, Slack의 secret은 test 전용 가짜 값이다. Authorization, access/refresh token, raw MIME, 전체 이메일, webhook URL, provider raw response를 assertion report, browser trace, screenshot, app log에 남기지 않는다. Header/body는 test process memory에서 조건 확인만 한다.
- fake Meeting/recipient/address/document URL은 synthetic fixture를 사용한다. 실제 Gmail inbox, Confluence space 또는 Slack channel은 이 실행에서 접속하지 않는다.

## 포함/제외

- 포함: document-first 순서/idempotency, 선택 roster만 Email, Gmail token/send wire boundary, Slack payload/disable, 혼합 channel/recipient 결과, API-010 결과 연결, API-016 단건 재시도 및 retry 금지 오류.
- 제외: OAuth consent/refresh token 발급, real service credentials/send, provider adapter 기능 재설계, 실제 inbox delivery 확인, Email template 문구 작성, delivery history/read-only 보안 acceptance (`TASK-021.03`), release evidence audit (`TASK-021.04`).

## 완료 기준

- `frontend/`의 `npm run test:e2e:publish`가 실제 UI→API→publish/orchestration→Provider adapter→HTTP mock을 실행한다.
- 문서 저장 성공 전 Email/Slack 호출 0건, 문서 실패 시 channel side effect 0건이며 문서가 성공한 뒤에만 channel 작업이 시작된다.
- Confluence Basic header는 synthetic email/token 조합과 일치하며 비밀번호 방식이 아니고 어떤 증거/로그에도 노출되지 않는다.
- Gmail은 token refresh 뒤 selected recipient별 request를 개별 발송하고 API-010에 recipient IDs/status/attempt count를 반환한다. unselected roster ID는 send되지 않는다.
- Slack은 enabled일 때만 최소 Meeting title/date/document URL payload로 1회 호출하고 Email과 독립 결과를 기록한다.
- 명확하게 retryable로 표시된 한 실패 Delivery의 API-016 재시도만 추가 network mock call 한 건을 만들고 다른 channel/recipient/document는 중복 실행되지 않는다. 모호한 전송 결과의 retry는 막힌다.
- task-local sanitized report와 `docs/evidence/TASK-021.02.md`에 flow ID, provider fake response/status, Delivery aggregate, build/commit 결과가 기록되며 token/email/raw body는 없다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [TASK-021.01 핵심 E2E 기반](../TASK-021.01/spec.md)
- [Confluence Cloud Basic 결정 및 Adapter](../../phase-02-documents/TASK-002.03/spec.md)
- [Document Publish idempotency](../../phase-06-publish/TASK-010.02/spec.md)
- [Gmail API OAuth Adapter](../../phase-06-publish/TASK-011.01/spec.md)
- [Slack Incoming Webhook Adapter](../../phase-06-publish/TASK-012.01/spec.md)
- [사용자 단건 재시도 정책](../../phase-06-publish/TASK-013.01/spec.md)
- [Atlassian Basic auth 공식 안내](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/)
- [Google Gmail send method](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send)
- [Google OAuth server-side flow](https://developers.google.com/identity/protocols/oauth2/web-server)

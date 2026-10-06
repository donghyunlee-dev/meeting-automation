# Document·Email·Slack E2E 구현 계획

## 선행과 경계

- TASK-021.01 Issue #77: Playwright dual-server app, `e2e` profile, synthetic Session/chunk fixture
- TASK-010.02 Issue #44 / TASK-010.03 #45: API-015 document-first publish state, response and UI route
- TASK-011.01/.02 Issues #46/#47: Gmail OAuth/provider contract, per-recipient delivery/API/UI
- TASK-012.01/.02 Issues #48/#49: Slack webhook and independent notification result
- TASK-013.01/.02 Issues #50/#51: API-016 explicit single-delivery retry and result view
- TASK-002.03 Issue #8: approved Confluence Cloud Basic email+API token adapter

Implementation starts after those interface/product tasks are complete. Reuse TASK-021.01's Browser test command/reporting/server process. This work adds a publish-tagged suite and real provider adapters with transport interception; it does not replace Provider implementations with high-level mock stubs.

## 변경 대상

| 위치 | 책임 |
|---|---|
| `frontend/tests/e2e/publish.spec.ts` | SCR-007 Publish selection/confirm, API-010 Document/delivery state, independent retry UI scenarios |
| `frontend/package.json` | `npm run test:e2e:publish` script invoking the existing Playwright config with publish test match/tag |
| `backend/src/test/.../E2EProviderTransport` | local-only dispatcher for HTTP requests from real Confluence/Gmail/Slack adapters; deterministic fixture response and request capture in memory |
| `backend/src/main/resources/application-e2e.yml` or equivalent | synthetic fake credential values and endpoint selection/transport dependency injection for test context; no production config changes |
| `backend/src/test/.../PublishE2eFixture` | Confirmed Session/selected roster fake addresses, deterministic Document URL, per-channel reply/fault sequence, reset between runs |
| `docs/evidence/TASK-021.02.md` | scenario ID, result counts/status/attempt aggregates, credential/privacy and no-live-network confirmation |

Use the existing module/package convention when creating files. Do not add UI or endpoint routes solely to inspect fake-server counters; the test dispatcher is an in-process backend test fixture and browser-visible state remains API-010/016 only.

## Mocked HTTP setup

- Extend TASK-021.01 test runner with `npm run test:e2e:publish`; use its Backend e2e profile, Vite server, loopback-only fixture isolation, Playwright browser project and cleanup.
- Inject the transport at the provider HTTP client boundary. Run the normal provider command/request serializers so Basic header, OAuth token request, Gmail API `raw` message, Slack JSON payload and vendor URL path are still produced by product code.
- Mock response server binds an ephemeral loopback port only. Preserve/logically inspect provider scheme/host/path in the adapter request model but dispatch the actual socket to loopback; unknown target or any non-test transport throws before DNS/network access.
- For Confluence keep the existing production origin validator (`https://<site>.atlassian.net`) unchanged; the test transport maps it to local fixture internally. For Google token/Gmail API and Slack Incoming Webhook, intercept the fixed/selected host similarly. No changes to secret key names or production URL allowlists.
- Use only generated test secrets. Verify decoded Confluence Basic credential pair in memory, form-encoded OAuth refresh parameters, OAuth Bearer token on Gmail call, distinct Gmail `raw` payload for each selected recipient, and minimal Slack payload.
- Fixture response plan is scriptable by provider/method/recipient/run ID: success, Document lookup-existing, Document create, explicit 429, clear auth rejection, 5xx, response loss/timeout. No mock response contains real token or customer data.

## Test scenarios and order

1. Test selection: seed a Confirmed Session and publish request with two roster IDs selected, third left out; `notificationEnabled=true`.
2. Confluence path: expected Root/page lookup and `externalSessionId` document search, then create once. On response success, store one synthetic document ID/URL. On duplicate publish/retry use existing document lookup before create.
3. Fanout gate: before document save success, no Gmail message-send or Slack webhook POST occurs; after success, Email recipient sends and Slack POST are initiated independently. Any health-triggered OAuth token refresh is tracked separately from message Delivery.
4. Gmail behavior: fake OAuth token refresh, Gmail `/users/me/messages/send`; one message per selected recipient; assert sender/subject/body/document link and RFC 2822 Base64URL envelope without collecting full payload in reports.
5. Slack behavior: one webhook POST with title/date/document URL only; toggled-off notification produces zero request. Email result changes do not mutate Slack result and vice versa.
6. Poll API-010 until document reference and all initiated `deliveries[]` resolve. Assert generic status/attempt/error code/retryable fields contain no email address, provider response or Secret.
7. Script one Gmail recipient or Slack Delivery as explicit retryable 429 and another as successful. Click only failed row's retry; API-016 new idempotency key and exactly one selected adapter call; document/other recipients/other channel call count unchanged.
8. Script a Gmail/Slack response-lost or 5xx result and assert `retryable=false`; no Retry control/API-016/provider resend. Document-stage failure asserts Email/Slack 0, then corrected/retry Document flow creates at most one logical page and only afterward starts channels.
9. Reset Session/transport fixtures, run complete publish suite a second time without shared counters/secrets/output bleed; persist sanitized evidence.

## Existing contracts to preserve

- API-015 requires Confirmed or Document-failed Session, current If-Match and idempotency key. Only successful Document write unlocks Email/Notification stage.
- Email recipient request holds participant IDs only. Backend fetches the latest selected participant Email; delivery rows keep participant IDs, no addresses.
- Gmail sends each recipient independently with existing `EMAIL_PROVIDER=GMAIL_API` and OAuth refresh-token configuration; `messages.send(userId="me")` and `gmail.send` minimum scope stay as task011 contract.
- Slack webhook body carries only title, meeting date and Document URL. Incoming Webhook has no app idempotency key; ambiguous timeout/5xx remains non-retryable.
- API-016 applies to `FAILED && retryable=true` only. New explicit retry uses new key and retries exactly that delivery; same-key replay never re-sends.
- All providers are test-intercepted; no test depends on real Confluence site, Google mailbox, Workspace, Slack workspace, or external secrets.

## 명령 및 완료 자료

- `frontend/`: existing `npm run test`, `npm run lint`, `npm run build`, `npm run test:e2e`; this task adds `npm run test:e2e:publish`.
- `backend/`: `./gradlew clean build` (Windows `gradlew.bat clean build`).
- Use test naming/project tag to run provider HTTP adapter request contracts in a controlled publish suite; do not use live provider credentials for a smoke send.
- Evidence: `docs/evidence/TASK-021.02.md`. Keep raw request/response, HTTP auth header, email addresses, message MIME, webhook URL and document response body out of artifact/report.

## 공식 통합 참조

- [Atlassian Basic auth for REST APIs](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/)
- [Gmail users.messages.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send)
- [Google OAuth web-server refresh tokens](https://developers.google.com/identity/protocols/oauth2/web-server)
- [Slack Incoming Webhooks](https://api.slack.com/messaging/webhooks)

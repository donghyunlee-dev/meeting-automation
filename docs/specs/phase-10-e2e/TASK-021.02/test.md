# Document·Email·Slack E2E 검증 계획

## 실행 명령과 외부 연동 격리

- Frontend: `frontend/`에서 기존 `npm run test`, `npm run lint`, `npm run build`, `npm run test:e2e`; 이 작업 deliverable인 `npm run test:e2e:publish`.
- Backend: `backend/`에서 `./gradlew clean build` (Windows `gradlew.bat clean build`).
- Playwright app origin/backend health는 TASK-021.01이 설정한 loopback Vite/Backend server를 사용한다. Publish 시나리오에는 production Provider adapter와 API를 태운다.
- 실제 Atlassian/Google/Slack endpoint 대신 e2e-only injected transport가 HTTP를 loopback mock dispatcher에 전달한다. 테스트 runner는 허가되지 않은 DNS/socket egress가 있으면 실패해야 한다.
- Credentials는 가짜값이며 assertions는 test memory 내에서만 비교한다. 로그/Playwright trace/video/report/screenshot에는 인증 header, token, MIME, 개인주소, provider response raw body를 수집하지 않는다.

## 자동 E2E 시나리오

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| PUB-CONFLUENCE-BASIC | selected Confluence provider로 Publish; mock은 Root lookup, Session document 미존재, create 성공 응답; synthetic account email/API token 사용 | adapter가 UTF-8 `email:token`의 Basic Authorization을 구성하고 REST API v2 순서대로 한 번 생성; 기존 Basic 결정 준수; 조회/본문 URL/새 문서 1개 | assertion pass, safe request route/count, created fixture ID |
| PUB-EMAIL-OAUTH | 2 roster recipients만 선택하고 fake token endpoint refresh success, Gmail send success 설정 | OAuth `grant_type=refresh_token` exchange 뒤 Bearer Gmail `users.messages.send(userId="me")`; recipient별 1개 message, selected IDs만 수신; API-010 address-free Delivery 2행 | call counts, recipient ID/status/attempt count only |
| PUB-GMAIL-MESSAGE | synthetic Meeting content/Document URL로 한 recipient send | RFC 2822 raw MIME이 Base64URL request로 구성되고 From/To/Subject/body/URL 계약이 맞음; request full body는 report 미수록 | in-memory header/payload assertions result only |
| PUB-SLACK-WEBHOOK | `notificationEnabled=true`, mock webhook 200 `ok` | Incoming Webhook 정확히 1회, title/date/document URL만 포함; Slack `SENT`와 성공 의미는 API acceptance만 | safe status/call count/payload field names |
| PUB-DOC-FIRST | Document lookup/create를 pending response로 지연하고 browser/API-010 상태 조회 | document 성공 전 Gmail message-send와 Slack webhook POST 0회; 성공 뒤 선택 채널만 시작. 필요 시 OAuth health token refresh는 message send와 별도 집계 | ordered event IDs/timestamps/counts |
| PUB-DOC-FAIL-RETRY | Document create 명시 오류 후 API-010 `DOCUMENT_FAILED`, 새 key/current version으로 user retry; mock 기존 document를 찾거나 create 성공 | 첫 실패 동안 Email/Slack 0; retry 뒤 논리 document 최대 1개, 이후 channel 1회씩 시작 | create/find counts, API status/version/key fingerprint, delivery calls |
| PUB-EMAIL-INDEPENDENT | 선택 recipient A Gmail success, B explicit 429; Slack success | A `SENT`, B `FAILED && retryable=true`, Slack `SENT`; 두 Email이 개별 메시지이고 status/channel 교차 덮어쓰기 없음 | API-010 delivery projection IDs/status/attempts |
| PUB-OAUTH-REFRESH-FAIL | Document 성공 후 OAuth refresh endpoint가 revoked/invalid grant를 반환; Slack은 200 `ok` fixture | 선택 Email recipient별 `EMAIL_FAILED`가 기록되고 Gmail messages.send 0; Slack은 `SENT` 유지; token 응답 원문은 숨김 | safe email error codes/status, Gmail/Slack call counts |
| PUB-SLACK-INDEPENDENT | Gmail recipients success, Slack explicit 429/permission failure | Email `SENT` 유지, Notification만 해당 error/retryable 상태. notification false fixture는 webhook 0회 | per-channel delivery + mock call counts |
| PUB-RETRY-ONE-DELIVERY | B 또는 Notification FAILED/retryable=true 행의 SCR-008 retry 클릭 | API-016 해당 deliveryId에 fresh Idempotency-Key 한 번; 추가 호출은 해당 recipient/webhook에만 1회, 다른 Delivery/Document는 resend 0 | retry delivery ID, key fingerprint, changed call count |
| PUB-RETRY-DENIED | SENT/PENDING/nonretryable response-lost/5xx 결과로 각 행 표시 및 retry action 시도 | API-016 호출/재발송 없음; ambiguous outcome `retryable=false`, 원문 대신 safe 안내 | action absence, provider call count unchanged |
| PUB-IDEMPOTENCY | 같은 API-015 request/key 반복; 같은 API-016 retry key replay | 최초 접수 response replay, document/email/webhook call count 증가 없음; 같은 key 다른 target은 conflict | status/code and counts |
| PUB-DELIVERY-PRIVACY | API-010, error copy, app log/report 및 Browser test artifacts 검사 | 이메일주소, Authorization, OAuth/token, raw provider body, Slack webhook URL, minutes/message raw copy 없음; 채널별 participant ID/errorCode/attempt 상태만 안전 노출 | sanitized scan/report result |
| PUB-NO-LIVE-EGRESS | 전체 suite 동안 outbound adapter traffic 수집/unknown endpoint 호출 감시 | Atlassian, Google, Slack public network request 0; 모든 endpoint loopback dispatcher로만 처리됨 | egress guard result, dispatcher request counts |
| PUB-RESET-REPEAT | test state/dispatcher reset 후 전체 publish suite 두 번째 실행 | 문서/delivery IDs/counters/secret fixtures 이전 run과 분리, 두 실행 같은 pass | run IDs, reset/cleanup result |

## 오류 분류와 재시도 확인

- Gmail 명시 429, Slack 명시 429, 수락되지 않은 credential/권한 오류는 기존 Adapter `retryable` contract에 따라 사용자 재시도 가능일 때만 행 action을 보인다.
- OAuth token refresh 실패는 Gmail message send 전에 발생한다. selected Email recipient 결과만 실패하며 Slack notification 결과는 바뀌지 않는다. API-019의 부작용 없는 refresh probe는 발송 단계 전후의 send call count와 따로 기록한다.
- Gmail `messages.send` 5xx, Slack 5xx, timeout/응답 유실은 성공 수락 여부가 불명확하므로 `retryable=false`; API-016이 이를 거절하고 provider call이 재발생하지 않는다.
- OAuth token refresh 실패는 Gmail send 전에 발생한다. 선택 Email recipients의 status만 실패하고 document 및 Slack 흐름은 독립되며 refresh token 원문이 응답되지 않는다.
- Document 오류 시 API-015의 Publish version/state 전이와 DocumentProvider lookup/create 계약을 사용한다. `DOCUMENT_FAILED`에서 API-016을 호출하지 않으며 API-015 새 idempotency key로 문서 단계만 재개한다.

## 완료 증거 및 판정

- Evidence: `docs/evidence/TASK-021.02.md`
- 기록: commit/build, browser/Node/Java/Gradle versions, command, scenario ID, document find/create count, selected recipient IDs, delivery channel/status/attempt/retryable, mock response class/status, retry/call count, no-egress/cleanup.
- 금지: decoded Authorization text, client secret/refresh/access token, email address, MIME/full message body, Slack webhook, Document response/raw meeting body, actual URLs/PII.
- 성공: Document success 이후에만 channel send가 시작되고, Document failure는 channel 0회로 유지된다. recipient/channel result와 retry가 독립적이며 하나의 allowed API-016 action만 선택 delivery를 추가 전송한다.
- 실패: document 전 channel call, 선택하지 않은 recipient 발송, 다른 channel/recipient 덮어쓰기·재전송, duplicate page, ambiguous outcome retry, unselected notification webhook call, public endpoint egress, sensitive artifact.

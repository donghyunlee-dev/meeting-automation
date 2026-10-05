# Gmail API OAuth Email Provider Adapter

## 목표

Document 저장 성공 뒤 회의 작성자가 선택한 수신자에게 Gmail API로 각각 회의 요약 Email을 보내고 독립 Delivery 결과를 반환한다. 사용자는 V1 발송 방식을 Gmail API + OAuth 2.0으로 승인했다. PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-017`, `EXT-004`를 구체화한다. Issue [#46](https://github.com/donghyunlee-dev/meeting-automation/issues/46).

## 범위

- Provider-neutral `EmailProvider` port의 Gmail API adapter
- OAuth 2.0 refresh-token 기반 Backend 인증 및 Gmail `gmail.send` 최소 scope
- RFC 2822 MIME message 구성 및 `users.messages.send` API 연결
- 선택 recipient별 분리 전송/독립 결과와 실패 재시도 가능 여부 정규화
- 설정·token refresh 확인과 secret/privacy 경계
- Mock 기반 provider contract 및 Gmail/OAuth HTTP 상호작용 검증

## 비범위

- OAuth 동의/authorization code 발급 UI 또는 계정 연결 API는 만들지 않는다. 최초 refresh token은 운영자 bootstrap 절차로 얻어 Backend secret 설정에 주입한다.
- Service account/domain-wide delegation, Gmail mailbox 읽기/검색/수정, 첨부파일은 구현하지 않는다.
- 수신자 selection UI 및 수신자별 결과 화면은 TASK-011.02가 소유한다. API-015 orchestration 연결은 그 task가 담당한다.
- Subject와 body 문안 composition/template은 이 adapter의 책임이 아니다. 입력된 title/subject, summary/body, Document URL을 전송 형식으로 감쌀 뿐이다.

## 설정과 인증

```text
EMAIL_PROVIDER=GMAIL_API
EMAIL_OAUTH_CLIENT_ID=<Google OAuth client ID>
EMAIL_OAUTH_CLIENT_SECRET=<secret>
EMAIL_OAUTH_REFRESH_TOKEN=<secret for one authorized Gmail account>
EMAIL_SENDER_ADDRESS=<same authorized Gmail address or verified send-as alias>
```

OAuth refresh flow는 Gmail 사용자가 최초 동의한 `https://www.googleapis.com/auth/gmail.send` 권한을 가진 refresh token으로 access token을 Backend에서 발급/갱신한다. `EMAIL_SENDER_ADDRESS`는 authenticated mailbox의 기본 주소 또는 Gmail에서 검증된 send-as alias여야 한다. Gmail API 호출의 `userId`는 `me`다. access token은 요청 간 공유 캐시에 보관하지 않고 프로세스 메모리에서 만료 시간까지만 재사용한다. Refresh token과 OAuth client secret은 Render Backend secret에만 둔다.

`validateConnection()`은 필수 설정 유무와 OAuth token endpoint에서 refresh 성공 여부만 확인하며 실제 메일을 보내지 않는다. Gmail `users.getProfile`에 필요한 mailbox metadata scope를 추가하지 않는다. token refresh 성공은 이메일 발송/수신함 도착을 보증하지 않는다. Gmail API send 성공은 API가 메시지를 접수했다는 의미이며 최종 수신/열람 확인은 아니다.

Google Cloud project에서 Gmail API를 활성화하고 OAuth consent/app 설정을 배포 전 준비한다. `gmail.send` scope는 Google 분류상 sensitive scope이므로 앱 배포/사용자 유형에 적용되는 검증 요건을 확인한다. 외부 user type의 Testing 상태에서는 현재 Google 정책에 따라 refresh token 수명 제한이 적용될 수 있으므로 개발 token을 production secret으로 재사용하지 않는다. Google Workspace 내부 전용 app은 조직 OAuth 정책과 app user type을 확인한다. App이 OAuth refresh token을 발급하는 consent flow는 제품 범위 밖이다.

## Adapter 계약

```text
sendMeetingEmail(command) -> RecipientDeliveryResult[]

MeetingEmailCommand = {
  subject,
  textBody,
  documentUrl,
  recipients: [{participantId, email}]
}

RecipientDeliveryResult = {
  recipientParticipantId,
  status: SENT | FAILED,
  retryable: boolean,
  errorCode?: EMAIL_FAILED
}
```

Adapter result에는 recipient participant ID를 유지한다. `deliveryId` 발급과 Session delivery 이력 기록은 application/orchestration layer가 소유한다.

요청 수신자 순서대로 각 주소에 하나의 message를 만든다. 다른 수신자를 `To`/`Cc`/`Bcc`에 함께 넣지 않는다. `From`은 `EMAIL_SENDER_ADDRESS`; `To`는 현재 수신자 하나; `Subject`는 CR/LF header injection을 거부; body는 UTF-8 `text/plain`이며 입력 요약 및 저장된 Document URL을 보존한다. RFC 2822 bytes를 Base64 URL-safe encoding하고 Gmail `users.messages.send(userId="me")` 요청의 `raw` field에 전달한다. HTML rendering이나 임의 URL 변환은 하지 않는다.

빈 recipients는 빈 결과를 반환한다. 주소의 trim 후 형식 검증은 수신자마다 수행한다. 잘못된 주소 하나는 그 수신자 결과만 실패시키고 이후 유효 recipient 처리를 계속한다. Email Provider는 meeting roster를 다시 조회하거나 선택 목록을 변경하지 않는다. 선택한 participant ID는 각 delivery 결과에 이어지고 전체 주소는 결과/Delivery history에 저장하지 않는다.

## 결과·오류·재시도

- Gmail API 성공 응답은 해당 recipient Delivery `SENT`, `retryable=false`로 기록한다. 이미 `SENT`인 delivery는 재시도하지 않는다.
- 잘못된 주소, OAuth config/refresh/revoked credential, 권한 거부는 safe `EMAIL_FAILED`, 재시도 불가로 반환한다. 자격/권한 수정 후 사용자가 다시 시도할 수 있게 failure는 보존한다.
- 명시적인 quota/rate limit 또는 일시 5xx 응답만 `retryable=true`로 표기하고 재시도는 TASK-013.01 delivery orchestration 정책을 따른다.
- 연결 timeout이나 응답 유실처럼 Gmail이 메시지를 접수했는지 판단할 수 없으면 `FAILED`, `retryable=false`로 보수 처리한다. blind retry로 중복 이메일을 만들지 않고 수동 확인이 필요함을 안전하게 표시한다.
- Token refresh가 batch 시작 전에 실패하면 Gmail API send를 하지 않고 아직 미처리 recipient 각각의 실패 결과를 반환한다. 한 recipient의 확정 실패는 나머지 수신자 발송을 막지 않는다.
- 원 Google HTTP error body는 로깅/응답하지 않는다. API error는 common envelope와 기존 `EMAIL_FAILED` 코드만 노출하고 오류 category는 `EMAIL_FAILURE`다.

## 보안 및 개인정보

- OAuth client secret, refresh token, access token, Authorization header, MIME 원문, 전체 이메일 주소는 API response, 일반 로그, Admin Slack, delivery history에 기록하지 않는다.
- 메시지 payload는 전송 직전에만 구성하고 디버그 로그에서 제거한다.
- Redirect/redirect URI/OAuth state 구현은 별도 auth flow scope를 끌어들이지 않으며 제품 client/browser에 credential/token을 전달하지 않는다.
- Gmail SDK/HTTP client는 Backend only이며 Domain/Application 계층은 Google API 타입을 노출하지 않는다.

## 완료 기준

- Gmail API OAuth refresh와 `gmail.send` scope로 설정된 계정이 성공 메시지를 보낸다.
- 수신자별 단일 message, 유효/무효 주소 혼합, 빈 목록, 혼합 provider 결과를 독립 검증한다.
- 성공 recipient 중복 재발송 금지와 모호한 timeout blind retry 차단이 검증된다.
- Document 저장 전 invocation이 없고 선택 roster 외 수신자 발송이 없다.
- 설정 누락, token revoke, auth/permission, rate limit, 5xx, timeout을 safe normalized result로 변환한다.
- Secret/PII 경계와 Mock Provider contract가 자동 검증된다.

## 공식 참조

- [Google Gmail API — Server-side authorization](https://developers.google.com/workspace/gmail/api/auth/web-server)
- [Google Gmail API — Send messages](https://developers.google.com/workspace/gmail/api/guides/sending)
- [Google Gmail API — Choose OAuth scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)
- [Google OAuth — Web server apps and offline access](https://developers.google.com/identity/protocols/oauth2/web-server)
- [Google OAuth — App publishing state behavior](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

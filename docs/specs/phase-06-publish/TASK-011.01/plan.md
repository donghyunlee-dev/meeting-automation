# 구현 계획

## 기준 및 선행 조건

- PRD v1.7.0 (2026-10-05): `DEC-015`, `FR-017`, `EXT-004`
- 승인 결정: Gmail API + OAuth 2.0; Issue [#46](https://github.com/donghyunlee-dev/meeting-automation/issues/46)
- 선행: TASK-010.02 Issue [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44)의 API-015 Document-first workflow와 successful document reference
- 결정 참조: [Google server-side OAuth](https://developers.google.com/workspace/gmail/api/auth/web-server), [Gmail send API](https://developers.google.com/workspace/gmail/api/guides/sending), [OAuth scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [OAuth app publishing states](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)
- 설계 문서: [spec.md](./spec.md), [tasks.md](./tasks.md), [test.md](./test.md)

## 코드 소유 경계

- Domain Port: `EmailProvider`, command와 recipient별 표준 delivery result만 공개한다.
- Outbound Adapter: Gmail API/auth client 및 Google SDK types를 `adapter/out/email` package 안에 격리한다.
- Configuration: `EMAIL_PROVIDER=GMAIL_API` factory selection과 OAuth secret property를 Backend 설정으로 binding한다.
- Application integration: TASK-011.02가 API-015 workflow에서 선택 수신자를 command로 보내고 독립 Delivery 상태를 저장하도록 연결한다. 이 task는 실제 provider adapter와 contract만 제공한다.
- Admin Settings/Health surface는 기존 API-019 `email.configured/reachable` contract를 유지하며 인증/검증 로직만 연결한다.

Backend source와 `build.gradle`은 현재 repository에 없으므로 구현 시작 시 Java 21/Gradle project layout과 기존 HTTP/client/auth dependency를 확인한다. Google official Java client/auth libraries를 사용할 때 Java 21 compatible 최신 patch를 선택하고 dependency version을 Gradle에 고정한다. Source file/package path와 build command는 그 확인 후 계획 evidence에 확정한다.

## 구현 순서

Port command/result가 recipient Delivery model과 일치해야 adapter 호출/재시도가 안전하므로 contract부터 고정한 뒤 인증과 전송을 붙인다.

1. Backend build/layout, external HTTP client standard, configuration binding, error redaction, Delivery model을 확인한다. 결과: 구현 파일과 실제 `./gradlew` 명령을 확인한다.
2. EmailProvider contract tests를 먼저 작성한다. mixed recipient/empty list, per-recipient result isolation, accepted-success no repeat 및 safe errors가 테스트에서 고정된다.
3. Gmail OAuth config validation/token refresh와 `validateConnection()` tests를 작성한다. 결과: secret 누락/refresh failure가 Gmail send를 부르지 않는다.
4. Gmail API fake/mock client를 통해 MIME/header/base64url contract와 `users.messages.send(userId="me")` interaction tests를 먼저 작성한다.
5. Domain-neutral port/DTO와 Gmail adapter/factory를 최소 구현한다. Google SDK types가 Port/Application 밖으로 나오지 않는다.
6. Recipient loop를 연결해 주소 검증/individual sends/각 결과 mapping을 구현한다. 한 주소 실패가 다른 수신자 결과를 삼키지 않는다.
7. OAuth invalid/revoked, insufficient scope, quota/rate limit, 5xx, ambiguous timeout 분류 및 retryable boundary를 적용한다.
8. Secret/PII log tests, API-019 health semantics와 설정 예시를 점검하고 Backend test/build/quality commands를 실행한다.
9. Gmail real-account smoke test가 승인된 별도 test mailbox/recipient에 수행된 경우만 evidence에 남기고 #46에 비민감 결과를 기록한다.

## 설정 및 계층 계약

- `EMAIL_PROVIDER=GMAIL_API`; secrets: `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`; sender identity: `EMAIL_SENDER_ADDRESS`.
- OAuth scope는 `https://www.googleapis.com/auth/gmail.send` 단독이다. 최초 사용자 동의/offline refresh token은 운영환경 밖 bootstrap 절차가 제공한다.
- `validateConnection()`은 required config 및 token refresh만 수행한다. 실제 Gmail message 전송은 하지 않는다.
- Provider command는 title/subject, plain-text summary/body, Document URL, 선택된 participant ID/email 목록을 갖는다. provider별 message object는 adapter 안에서 만든다.
- Token/Google error classification은 공통 `EMAIL_FAILED`, category `EMAIL_FAILURE`, Data Specification `retryable` field와 연결한다.

## 검증 접근

JUnit 5로 port contract, config/token client, Gmail request adapter, recipient orchestration을 구분한다. Network-boundary test는 fake Google OAuth/Gmail HTTP endpoint 또는 mock client를 사용해 실계정에 연결하지 않는다. 실제 명령은 backend project setup에서 확인하고, 설계 단계에서 실행하지 않는다.

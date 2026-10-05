# 구현 작업 목록

기준: `TASK-011.01`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-017`, `EXT-004`, Issue [#46](https://github.com/donghyunlee-dev/meeting-automation/issues/46).

## 사전 확인

- [ ] TASK-010.02에서 Email Provider 호출 시점이 Document 저장 완료 뒤임을 확인한다. 결과: Document reference가 없는 command는 전송할 수 없다.
- [ ] Backend Java 21/Gradle setup 및 HTTP/OAuth convention과 secret property loading 경계를 확인한다. 결과: 실제 package, dependency version, build command를 기록한다.
- [ ] Data Specification Delivery status/attempt/retryable와 EXT-004 `validateConnection/sendMeetingEmail`를 cross-check한다. 결과: provider result와 application Delivery mapping이 모순되지 않는다.

## 구현 단계

- [ ] Mixed valid/invalid recipients, empty recipient list, mixed success/failure, already-sent no-repeat의 port contract test를 작성한다.
- [ ] OAuth credential 누락/refresh rejection/success의 test를 작성하고 실패 때 Gmail API 호출 수 0을 assertion한다.
- [ ] Gmail request mock으로 one recipient per message, From/To/Subject, text body, MIME Base64URL `raw`, `userId="me"`를 검증하는 failing test를 작성한다.
- [ ] Domain-neutral `EmailProvider`, `MeetingEmailCommand`, `RecipientDeliveryResult`를 정의하고 Google types를 경계 밖으로 누출하지 않는다.
- [ ] `EMAIL_PROVIDER=GMAIL_API` selection, secret configuration validation, refresh-token credential client, `validateConnection()`을 구현한다.
- [ ] Gmail API sender를 구현해 RFC 2822 `text/plain; charset=UTF-8` message를 만들고 recipient별로 별도 전송한다.
- [ ] Per-recipient result/status mapping을 구현한다. 한 recipient format/API error는 다음 사람의 send를 중단하지 않는다.
- [ ] Auth/config/permission failures, explicit quota/rate-limit, 5xx, ambiguous timeout을 `EMAIL_FAILED` 및 `retryable`로 normalize한다. Ambiguous outcome의 자동 retry는 차단한다.
- [ ] Secret/PII/raw request/error body redaction과 API-019 configured/reachable semantics를 regression 검증한다.
- [ ] Project test/lint/build/release-check 명령을 실행하고 결과를 Issue #46에 기록한다. 실제 Gmail smoke test는 승인된 test account만 사용한다.

## 완료 확인

- Gmail `gmail.send` 외 scope가 요청되지 않는다.
- Gmail 계정 OAuth/user-specific authorization이 서버에만 남는다.
- participant마다 한 통이 발송되고 To header에 다른 참가자가 섞이지 않는다.
- 성공 recipient는 retry 대상이 아니며 실패 recipient만 downstream retry 대상이 될 수 있다.
- 공급자별 메시지/오류 원문은 표준 port 결과에 포함되지 않는다.

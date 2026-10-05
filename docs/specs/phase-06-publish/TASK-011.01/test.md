# 검증 계획

기준: `TASK-011.01`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-017`, `EXT-004`, Issue [#46](https://github.com/donghyunlee-dev/meeting-automation/issues/46).

## 자동화 검증

Backend 구현 시 Java 21/Gradle의 JUnit 5 검증을 사용한다. Exact build commands는 Backend project setup을 확인한 뒤 고정한다. OAuth/Gmail network calls는 stub/fake로 대체하고 실제 Gmail 계정을 자동화 test에서 사용하지 않는다. 설계 단계에서는 tests를 실행하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| MAIL-01 | 모든 OAuth setting과 sender address 정상; mocked refresh 성공 | `validateConnection()` configured/reachable true; Gmail send 0회 | Config/OAuth client test |
| MAIL-02 | OAuth client/refresh secret 하나 이상 누락 | configured=false/reachable=false; token/Gmail endpoint 호출 없음 | Configuration validation test |
| MAIL-03 | refresh token 승인 거부/철회 | safe `EMAIL_FAILED`, Gmail send 0회, token/error 원문 미노출 | OAuth error normalization test |
| MAIL-04 | 만료 access token 및 유효 refresh response | token refresh 후 Gmail request에 bearer access token 전달 | OAuth refresh interaction test |
| MAIL-05 | 유효 recipient 한 명과 subject/summary/document URL | MIME From/To/Subject/body/URL 보존, Base64URL raw, `users.messages.send(userId="me")` 한 번 | Gmail request contract test |
| MAIL-06 | 두 명의 수신자 | 별도 Gmail message 두 건, 각 To에 해당 recipient만 있고 recipient 간 주소 비노출 | Per-recipient isolation test |
| MAIL-07 | 빈 recipient 배열 | empty result, OAuth/Gmail send 호출 없음 | Empty batch test |
| MAIL-08 | 잘못된 이메일 하나와 올바른 이메일 하나 | 잘못된 주소의 FAILED 결과, 정상 주소 send 성공; 다음 recipient 진행 | Recipient validation isolation test |
| MAIL-09 | 다수 중 Gmail API success/failure 혼합 | 각 participant의 SENT/FAILED가 분리되며 성공 결과 불변 | Partial result mapping test |
| MAIL-10 | Gmail API success response | 해당 Delivery SENT/retryable false; 이후 retry 실행 대상에 포함 안 됨 | Successful delivery idempotency test |
| MAIL-11 | explicit quota/rate-limit 응답 | recipient 실패 `EMAIL_FAILED`, retryable true, 원 Provider body 미노출 | Retryable provider error test |
| MAIL-12 | invalid scope/forbidden/invalid recipient | safe FAILED, retryable false, 다음 유효 수신자 처리는 계속 | Nonretryable error test |
| MAIL-13 | 5xx response | `EMAIL_FAILED`, retryable true; 자동 loop retry 없음 | Transient error classification test |
| MAIL-14 | send request timeout/response loss | FAILED, retryable false; 재전송하지 않고 uncertain outcome을 기록 | Ambiguous send outcome test |
| MAIL-15 | subject CR/LF, malformed URL, body containing plain text punctuation | header injection은 거부; URL/content를 HTML 실행 없이 text/plain으로 전송 | MIME/input safety test |
| MAIL-16 | captured logs and API error response | oauth client secret/refresh/access token/Authorization/email/MIME/provider body 0건 | Redaction/log capture test |
| MAIL-17 | Session document save 미완료 또는 command에 저장된 URL 없음 | application boundary가 adapter를 호출하지 않음 | Publish workflow gate test |
| MAIL-18 | Session roster 중 작성자가 선택한 participant 일부 | 선택 ID만 provider command recipients가 되고 미선택 ID는 Gmail request 0회 | Recipient selection boundary test |

## 수동/환경 QA

- Google Cloud test project에서 Gmail API를 enable하고 `gmail.send`만 승인된 OAuth refresh token으로 Backend 환경을 구성한다.
- 승인된 테스트 발신 mailbox에서 선택된 테스트 recipient 한 명에게 plain-text 회의 요약과 document URL을 보내 Gmail API success receipt와 수신 결과를 확인한다.
- 다중 recipient test는 본인 소유/승인 받은 테스트 mailbox만 사용하고 To header가 각자 분리됐는지 확인한다.
- Google API 권한 취소 또는 잘못된 refresh token fixture에서 안전한 오류가 표시되고 secret/provider 원문이 보이지 않는지 확인한다.
- Google consent screen의 Gmail sensitive scope에 대한 배포 verification requirement를 출시 전 확인한다.
- 외부 OAuth app이 Testing 상태일 때 Google의 현재 refresh token lifetime 제한에 걸리지 않는 production app state와 credential을 사용한다.

## 증거 및 안전 기준

- OAuth/Gmail mocking test 결과, retryable matrix, redaction 결과를 Issue #46에 남긴다.
- Gmail 실제 발송은 승인된 test account/recipient에 한정하고 자동화 build에서 발생시키지 않는다.
- 전달 완료는 Gmail API 성공 응답을 의미하며 mailbox inbox placement/read receipt로 과장하지 않는다.

## 공식 참조

- [Gmail API `users.messages.send`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send)
- [Gmail API scope classification](https://developers.google.com/workspace/gmail/api/auth/scopes)

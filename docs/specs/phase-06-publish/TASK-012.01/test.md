# 검증 계획

기준: `TASK-012.01`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `EXT-005`, Issue [#48](https://github.com/donghyunlee-dev/meeting-automation/issues/48).

## 자동화 검증

Backend 구현 시 Java 21/Gradle JUnit 5를 사용하고 Slack HTTP 요청은 모의 endpoint로 격리한다. 실제 Slack 채널에는 자동 메시지를 보내지 않는다. 설계 단계에서는 테스트를 실행하지 않는다. 명령은 Backend wrapper 경로를 확인해 정한다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SLACK-01 | `NOTIFICATION_PROVIDER=SLACK`, 유효한 HTTPS 회의 webhook URL | factory가 Slack adapter를 선택하고 Admin webhook은 분리됨 | 설정/factory 테스트 |
| SLACK-02 | provider 누락/미지원 또는 webhook URL 누락/오형식 | `configured=false/reachable=false`, Slack 요청 0회, 안전한 Health 응답 | 설정 검증 테스트 |
| SLACK-03 | 전송 전 `validateConnection()` 호출 | HTTP POST/시험 메시지 없이 설정만 확인, 실제 성공 전까지 reachable false | Health 무부작용 테스트 |
| SLACK-04 | 제목/날짜/문서 URL command와 mock 200 `ok` 응답 | 회의 webhook으로 HTTPS JSON POST 1회, 세 값 포함, SENT/retryable false, reachable true | webhook 요청 계약 테스트 |
| SLACK-05 | 문서 URL이 null/오형식/HTTP(S) 이외 scheme | 요청 없이 안전한 `NOTIFICATION_FAILED`, retryable false | 입력 검증 테스트 |
| SLACK-06 | 제목에 CRLF, `<@U…>`, `<!channel>`, mrkdwn 구문 포함 | 일반 텍스트로 정규화되고 의도하지 않은 mention/markup 해석 및 추가 정보가 없음 | payload escaping 테스트 |
| SLACK-07 | HTTP 200 응답 본문이 `ok`가 아님 | FAILED `NOTIFICATION_FAILED`; 상태 코드만으로 성공 처리하지 않음 | 응답 본문 계약 테스트 |
| SLACK-08 | HTTP 400 잘못된 요청 또는 403 `action_prohibited` | FAILED/retryable false, 안전한 오류만 반환하고 원문 본문은 미기록 | 영구 HTTP 오류 매핑 테스트 |
| SLACK-09 | HTTP 404 무효/폐기된 webhook | FAILED/retryable false, reachable false, URL/token 가림 | endpoint 오류 테스트 |
| SLACK-10 | HTTP 429 및 `Retry-After` | FAILED/retryable true, 내부 자동 재시도 없음, 헤더 값을 로그에 노출하지 않음 | rate limit 매핑 테스트 |
| SLACK-11 | 전송 결과가 불명확한 HTTP 5xx | 중복 방지를 위해 FAILED/retryable false, adapter 내부 반복 없음 | 불명확 서버 오류 테스트 |
| SLACK-12 | 요청 전송 뒤 발생한 timeout/reset | 게시 결과를 알 수 없으므로 FAILED/retryable false, 자동 재호출 없음 | 불명확 전달 테스트 |
| SLACK-13 | HTTP, log, API 오류 출력 검사 | webhook token/전체 URL/payload 원문/Transcript/Minutes/참석자 이메일/Slack 원문 본문이 없음 | 민감정보 가림 검증 |
| SLACK-14 | Document 참조 생성 전에 Provider send 호출 시도 | Application guard가 adapter 호출을 막음 | Document 선행조건 테스트 |
| SLACK-15 | 기존 Email 전달 성공 뒤 Slack 실패 | Notification 전달만 실패하고 Email/Document 상태는 유지 | 채널 독립성 계약 테스트 |
| SLACK-16 | Slack 전송 수락 뒤 API-016 재시도 대상 확인 | `SENT` 전달은 재시도 불가 | 전달 멱등성 테스트 |
| SLACK-17 | 회의 adapter 설정/실패와 Admin webhook 설정 동시 존재 | 회의 알림이 Admin webhook을 호출/노출/변경하지 않음 | Admin 경로 격리 테스트 |

## 통합 및 수동 QA

- 시험용 Slack 앱의 별도 시험 채널 Incoming Webhook을 설정해 제목/날짜/문서 URL 메시지가 해당 채널에 한 번 게시되는지 확인한다.
- webhook URL 폐기 또는 endpoint 오류 응답을 재현해 오류 분류, 재시도 가능 여부, Admin webhook 격리 및 로그 가림을 확인한다.
- 채널은 설치 시 결정되며 API payload에서 변경되지 않는지 확인한다.
- Email 성공/실패 각각에서 Slack 전달이 독립 기록되고 Document 결과가 바뀌지 않는지 확인한다.

## 완료 증거

자동화 테스트/build 결과, payload mock 검증, secret 가림 점검 및 시험 채널 수동 확인 결과를 Issue #48에 기록한다. 운영 Slack 채널 smoke test는 이 작업에서 실행하지 않는다.

## 공식 참조

- [Slack Incoming Webhooks](https://api.slack.com/messaging/webhooks)
- [Slack message payload and plain-text options](https://api.slack.com/messaging/overview)
- [Slack webhook rate limits](https://api.slack.com/apis/rate-limits)

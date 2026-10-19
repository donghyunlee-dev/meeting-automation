> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# 검증 계획

기준: `TASK-012.01`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `EXT-005`, Issue [#48](https://github.com/donghyunlee-dev/meeting-automation/issues/48).

## 자동화 검증

Backend implementation uses Java 25 and the Windows Gradle Wrapper. Isolate Slack HTTP calls behind a mock endpoint. Run only focused tests for changed classes with `test --tests <fully.qualified.TestClass>`; do not run the full Backend suite, clean build, local service, or a separate acceptance pass.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SLACK-01 | `NOTIFICATION_PROVIDER=SLACK`, 유효한 HTTPS 회의 webhook URL | factory가 Slack adapter를 선택하고 Admin webhook은 분리됨 | 설정/factory 테스트 |
| SLACK-02 | provider 누락/미지원 또는 webhook URL 누락/오형식 | `configured=false/reachable=false`, Slack 요청 0회, 안전한 Health 응답; Notification Delivery는 retryable true | 설정 검증 테스트 |
| SLACK-03 | 전송 전 `validateConnection()` 호출 | HTTP POST/시험 메시지 없이 설정만 확인, 실제 성공 전까지 reachable false | Health 무부작용 테스트 |
| SLACK-04 | 제목/날짜/문서 URL command와 mock 200 `ok` 응답 | 회의 webhook으로 HTTPS JSON POST 1회, 세 값 포함, SENT/retryable false, reachable true | webhook 요청 계약 테스트 |
| SLACK-05 | 문서 URL이 null/오형식/HTTP(S) 이외 scheme | 요청 없이 안전한 `NOTIFICATION_FAILED`, retryable false | 입력 검증 테스트 |
| SLACK-06 | 제목에 CRLF, `<@U…>`, `<!channel>`, mrkdwn 구문 포함 | 일반 텍스트로 정규화되고 의도하지 않은 mention/markup 해석 및 추가 정보가 없음 | payload escaping 테스트 |
| SLACK-07 | HTTP 200 응답 본문이 `ok`가 아님 | FAILED `NOTIFICATION_FAILED`; 상태 코드만으로 성공 처리하지 않음 | 응답 본문 계약 테스트 |
| SLACK-08 | HTTP 400 payload 오류 또는 403 `action_prohibited` 권한 거절 | payload 오류는 retryable false, 확정된 권한 거절은 retryable true; 원문 미기록 | 거절 유형 매핑 테스트 |
| SLACK-09 | HTTP 404 무효/폐기된 webhook | FAILED/retryable true, reachable false, URL/token 가림; 수정 후 API-016 허용 | endpoint 오류 테스트 |
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

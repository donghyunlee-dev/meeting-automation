# 검증 계획

## 자동화 테스트

Backend JUnit/fake HTTP tests를 사용하며 실제 Slack webhook 호출은 하지 않는다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| ADMIN-PROCESSING | PROCESSING_FAILURE context | 허용 field만 포함한 message 1건 | payload test |
| ADMIN-DOCUMENT | DOCUMENT_FAILURE context | 분류/trace/session/stage/safe message 정확 | payload test |
| ADMIN-EMAIL | EMAIL_FAILURE context | recipient address 없이 message 전송 | payload/redaction test |
| ADMIN-NOTIFICATION | NOTIFICATION_FAILURE context | 분류 지원, recursive send 없음 | dispatcher test |
| ADMIN-OPTIONAL | sessionId 또는 stage 누락 | 누락 field는 생략, 임의 값 미생성 | mapper test |
| ADMIN-TEXT-SAFETY | control chars/Slack markup fixture | plain text 정규화 및 mention/markup 비실행 | mapper test |
| ADMIN-ALLOWLIST | serialized JSON keys 검사 | incidentType/sessionId/stage/traceId/message/retryable/occurredAt만 포함 | serialization test |
| ADMIN-CONFIG | webhook absent/invalid | 외부 request 0회, safe send failure | adapter test |
| ADMIN-SUCCESS | Slack HTTP 200 body ok | 한 POST로 수락 성공 | HTTP contract test |
| ADMIN-HTTP-ERROR | HTTP refusal, 5xx, non-ok body | 원 업무 오류 보존, raw body 숨김, 재전송 없음 | adapter/boundary test |
| ADMIN-AMBIGUOUS | timeout/reset/response loss | 결과 불명, retry 0회 | HTTP test |
| ADMIN-NO-RECURSION | notifier 자체 실패 | Admin incident 재전송 없음 | dispatcher test |
| ADMIN-ISOLATION | Meeting/Admin webhook 서로 다른 설정 | Admin은 admin URL만 사용, meeting delivery 영향 없음 | config test |
| ADMIN-LOG-REDACTION | URL/provider body/PII 포함 fixture | request/response/log에서 모두 제거 | log capture test |

## 수동 QA

- Stub server로 허용 payload의 plain text 표현을 확인한다.
- Admin webhook 미설정 때 원 사용자/Session failure가 유지되고 업무 처리가 재실행되지 않는지 확인한다.
- Webhook URL, Provider 오류, Audio/Transcript/Minutes/email address가 log에 없는지 검토한다.

## 릴리스 확인

- repository-defined ./gradlew test 및 ./gradlew clean build를 실행하고 docs/evidence/TASK-016.02.md에 결과를 기록한다.
- 운영 webhook 연결 전 승인된 sandbox channel/config로 별도 배포 QA를 한다. 자동 테스트에서는 실제 message를 보내지 않는다.

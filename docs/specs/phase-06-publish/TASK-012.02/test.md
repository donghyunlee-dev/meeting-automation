# 검증 계획

기준: `TASK-012.02`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `SCR-007`, `API-010`, `API-015`, `EXT-005`, Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49).

## 자동화 검증

구현 시 Backend Java 21/Gradle JUnit 5와 저장소의 Frontend component/API 테스트 체계를 사용한다. 저장소에 구현/build wrapper가 아직 없으므로 정확한 명령은 해당 코드가 마련될 때 기록하며 임의의 실행 명령을 만들지 않는다. Slack HTTP는 TASK-012.01 mock 계약으로 격리하고 실제 채널 전송은 자동 테스트에서 하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| PUB-NOTI-01 | API-015 `notificationEnabled=false`, Document 성공 | Slack adapter 호출 0회, Notification Delivery 생성 0건, Email 결과 보존 | Backend coordinator test |
| PUB-NOTI-02 | 알림 선택, Document Provider 실패 | Slack/Email 호출 0회, Notification row 없음, Document 오류 유지 | Publish ordering test |
| PUB-NOTI-03 | 알림 선택, Document 성공, Adapter SENT | adapter 한 번 호출, generic `NOTIFICATION/SENT` row가 API-010에 포함 | Delivery mapping/API test |
| PUB-NOTI-04 | Adapter FAILED, retryable false/true 변형 | 안전한 `NOTIFICATION_FAILED` 및 Adapter retryable이 API-010에 반영 | Failure mapping test |
| PUB-NOTI-05 | Email 일부 실패 및 Slack 성공 | Email FAILED row와 Notification SENT row가 독립 유지 | Mixed channel integration test |
| PUB-NOTI-06 | Email 성공 및 Slack 실패 | Email SENT와 Notification FAILED를 모두 보존 | Mixed channel integration test |
| PUB-NOTI-07 | API-010 조회: NOTIFICATION row | 공통 `{data}` envelope, recipient ID/주소 없이 delivery 필드만 반환 | API serialization test |
| PUB-NOTI-08 | notification provider null/unconfigured, SCR-007 진입 | Slack 선택 비활성/해제, Settings 연결 경로 표시, Publish body는 false | AppConfig component test |
| PUB-NOTI-09 | 선택 true/false로 API-015 실행 | 화면의 명시 선택값과 request boolean 일치 | Share request test |
| PUB-NOTI-10 | API-010 Notification PENDING/SENDING/SENT/FAILED | 각각 진행/접수/실패 텍스트를 Email 목록과 별도로 표시 | Delivery status component test |
| PUB-NOTI-11 | timeout 뒤 API-010에서 PENDING, 뒤이어 FAILED 조회 | 결과 확인 전 SENT로 표시하지 않고 새 Publish 자동 호출 없음 | Poll reconciliation test |
| PUB-NOTI-12 | 오래된 version의 API-010 응답이 최신 뒤 도착 | 최신 상태/Delivery 표시를 되돌리지 않음 | Stale response test |
| PUB-NOTI-13 | API-010 응답, 화면, log/error 수집 검사 | webhook URL, 채널명, Slack 원문 본문/payload, Email 주소/Secret 없음 | 개인정보·Secret 가림 검증 |
| PUB-NOTI-14 | 360px 화면, keyboard 및 screen reader 사용 | 선택/결과가 접근 가능하고 상태가 텍스트/live update로 전달됨 | 접근성 component/browser test |

## 수동 QA

- 시험용 Slack channel webhook과 저장할 회의를 사용해 선택 켬/끔 각각 Publish하고 선택 시 메시지 1건, 해제 시 0건인지 확인한다.
- Slack HTTP 오류를 mock 환경에서 재현해 Email 및 Document 결과가 그대로 남고 Notification 행만 실패하는지 확인한다.
- API-010 polling 중 route를 나갔다 돌아와도 최신 Session Delivery가 다시 표시되는지 확인한다.
- Provider 미설정 상태에서 SCR-007이 Settings 연결 안내를 제공하고 Provider를 임의 선택하지 않는지 확인한다.

## 완료 증거

Backend 통합/API 결과, Frontend component/accessibility 결과, 혼합 성공·실패 화면, 민감정보 가림 검증을 Issue #49에 기록한다. 자동화 검증에서 운영 Slack 채널로 메시지를 보내지 않는다.

# Slack 전달 결과와 Publish 연결

## 목표

API-015의 Slack 선택을 TASK-012.01 `NotificationProvider`에 연결하고, 결과를 API-010 공통 Delivery 계약 및 SCR-007에 반영한다. PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `SCR-007`, `API-010`, `API-015`, `EXT-005`를 구체화한다. 선행 설계는 [TASK-012.01](../TASK-012.01/spec.md), 수신자 결과 연결은 [TASK-011.02](../TASK-011.02/spec.md)다. Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49).

## 범위

- API-015 `notificationEnabled` 값을 Publish orchestration에 전달
- Document 저장 성공 후 선택된 경우 Slack Provider를 호출하고 generic `NOTIFICATION` Delivery 기록
- API-010 polling에서 Slack 전달 결과를 Email 수신자별 결과와 독립적으로 제공
- SCR-007의 Slack 선택, 설정 안내, 전송 중/성공/실패 결과 표시
- 응답 유실·지연 polling에서 API-010 최신 Delivery를 기준으로 화면을 조정

## 비범위

- Slack HTTP payload, webhook 설정, HTTP 오류 정규화는 [TASK-012.01](../TASK-012.01/spec.md) 소유다.
- API-016 retry endpoint 및 개별 재시도 조작, 최종 SCR-008 Complete 결과 요약은 TASK-013.01/.02 소유다.
- Admin Slack incident, Email 전송 결과, Document 상태는 이 작업에서 변경하지 않는다.
- Slack message 내용은 이 작업에서 확장하지 않는다. 제목/회의 날짜/Document URL 계약을 유지한다.

## API 및 orchestration 계약

API-015 body는 `{emailRecipientParticipantIds, notificationEnabled}`를 받는다. `notificationEnabled`는 boolean 선택값이며 Frontend가 화면의 명시된 값을 전송한다. 기존 API 예시의 `true`를 초기 선택값으로 사용하고 사용자가 SCR-007에서 해제할 수 있다.

처리 순서는 Document 저장, Email 전달, Notification 전달이다. Document 저장 실패 시 Email/Slack Provider를 호출하지 않는다. `notificationEnabled=false`이면 Slack Provider 호출 및 Notification Delivery 생성을 생략한다. `true`이면 Document 저장이 완료된 다음 `sendMeetingPublished({title,meetingAt,documentUrl})`를 한 번 호출한다. Slack 설정 누락이나 Adapter 실패는 해당 Notification Delivery만 `FAILED`로 남기며 Document/Email 결과를 변경하지 않는다.

API-010 공통 `{data}` envelope와 Delivery 모델을 유지한다. 전송을 선택하지 않으면 Publish 이후 Notification 행은 생성하지 않는다. 선택한 경우 `channel=NOTIFICATION`, `recipientParticipantId` 없음, `status=PENDING|SENDING|SENT|FAILED`, `attemptCount`, 선택적 `lastAttemptAt`/`errorCode`, `retryable`을 반환한다. 응답에 webhook URL, 채널명, Slack 원문 응답, 메시지 payload, Secret을 포함하지 않는다. `SENT`는 Slack의 HTTP 200 `ok` 접수를 뜻한다.

## 화면 동작

- SCR-007은 Slack 알림 선택값을 명시적으로 API-015에 전달한다. 초기 선택은 기존 API 예시와 같이 켜짐이며 사용자가 끌 수 있다.
- API-019 `notification.provider=null` 또는 `configured=false`이면 Slack 전송 선택을 끄고 알림 연결을 위한 Settings 경로를 제공한다. 임의 Provider 선택이나 연결 확인용 메시지 전송은 하지 않는다.
- Publish 중 API-010을 조회해 Notification Delivery 상태를 갱신한다. `PENDING`/`SENDING`은 전송 중, `SENT`는 Slack 요청 수락, `FAILED`는 안전한 실패 안내와 `retryable` 상태로 표시한다.
- Slack 결과 영역의 갱신은 Email participant Delivery 목록의 선택/결과를 덮거나 초기화하지 않는다. 채널 사이 성공/실패를 합산하지 않는다.
- `FAILED`에서 retryable이어도 이 화면은 직접 재호출하지 않는다. retry action은 TASK-013.02에서 제공한다.

## 경계와 오류 처리

- `notificationEnabled=false`는 정상적인 미선택이며 오류나 실패 Delivery를 만들지 않는다.
- 선택했지만 Slack 설정이 없거나 provider 응답이 실패하면 `NOTIFICATION_FAILED`와 Adapter의 `retryable`을 공통 Delivery에 반영한다. 원문 오류는 노출하지 않는다.
- HTTP 요청 결과가 불명확하거나 Frontend 응답이 끊기면 새 Publish를 자동 실행하지 않는다. API-010을 다시 조회하고 Delivery가 확인될 때까지 성공으로 추정하지 않는다.
- API-010 지연 응답이 더 최신 Session version/Delivery를 덮지 않게 한다. 알림 완료 전 사용자가 화면을 떠나도 재진입 시 API-010 결과를 다시 읽는다.
- Email 부분 실패와 Slack 성공, Email 성공과 Slack 실패 모두 각각의 Delivery에 유지된다. 어떤 전달 실패도 Document 저장 성공을 되돌리지 않는다.

## 완료 기준

- 선택 해제 시 Slack 호출 및 Notification Delivery가 생성되지 않는다.
- 선택 시 Document 저장 이후 Slack Adapter가 한 번만 호출되고 generic Notification Delivery로 기록된다.
- API-010의 Notification 결과를 SCR-007에서 Email 결과와 분리해 표시한다.
- 미설정, 진행, 성공, 실패, retryable, 지연/유실 응답 사례가 테스트로 검증된다.
- Email/Document 결과와 webhook Secret/provider 원문은 Slack UI/응답에 섞이지 않는다.
- Complete 화면과 retry action은 기존 TASK-013.02 계약에 남긴다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

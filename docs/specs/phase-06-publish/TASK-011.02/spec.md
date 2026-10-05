# 수신자 선택과 Email Delivery 결과 연결

## 목표

회의 작성자가 Session roster 안에서 Email 수신자를 선택하고 Publish 뒤 각 수신자별 전달 결과를 확인한다. PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, `EXT-004`를 구체화한다. 선행 TASK-011.01은 Gmail API OAuth Adapter를 제공한다. Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47).

## 범위와 소유 경계

- SCR-007에서 Meeting Session roster 참가자만 Email 대상 후보로 제시하고 checkbox 선택을 제공
- 선택 IDs를 API-015 `emailRecipientParticipantIds`로 전달
- Email Provider에 전달할 주소/이름을 ID 기준 Participant records에서 조회해 구성
- API-010에 immutable Session roster reference와 generic Delivery projection 노출
- API-010 polling으로 수신자별 `PENDING`/`SENDING`/`SENT`/`FAILED`, `retryable` 결과를 표시
- Empty selection, participant metadata/Email 주소 누락, list failure 및 URL/API 오류 처리

Gmail OAuth/Gmail API 구현은 TASK-011.01, Notification 수신 여부·결과 UI는 TASK-012.01/.02, API-016 조작과 SCR-008 완성 화면은 TASK-013.01/.02가 소유한다. 여기서는 retry button이나 Email Provider 재호출을 만들지 않는다.

## 참가자 선택 및 주소 매핑

- API-010 `meeting.participantIds`가 Session의 authoritative roster ID 집합이다. API-003 Participants 결과를 표시 정보와 Email 주소 조회에 사용하되 roster 밖 ID는 화면 후보/Publish request에 절대 넣지 않는다.
- 화면에서 제공되는 이름/Email은 API-003 `{id,name,email}` record를 roster ID와 join해 얻는다. 신규 Session roster record를 추가하거나 다른 attendee를 자동 포함하지 않는다.
- API-003 record를 일시 조회하지 못해도 Session roster 자체를 변경·거절하지 않는다. 이미 확보한 이름/email을 화면에서 보존하며, 데이터가 없을 때는 participant ID를 fallback label로 사용한다. 사용자는 각 ID의 선택을 취소할 수 있다.
- Provider Participant record에 해당 ID/email이 없거나 email 형식이 잘못됐으면 API-015 request를 Session roster만으로 유효하게 접수하되 해당 recipient Delivery를 `FAILED`, safe `EMAIL_FAILED`, `retryable=false`로 기록한다. 참석자 자체를 Session에서 제거하거나 active/existence 상태로 표현하지 않는다.
- 보이는 Email 주소는 전달 대상을 사람이 확인하는 화면에만 표시한다. API-015 request, API-010 delivery response, delivery history, 로그에 주소를 넣지 않는다.
- PRD 화면 예처럼 roster 참가자는 기본 선택 상태다. 작성자가 모두 해제하면 빈 `emailRecipientParticipantIds:[]`를 전달하며 Email send 0건으로 문서/다른 채널 Publish를 계속한다.

## API-010 response 확장

공통 `{data}` response를 유지한다. Session Status/Review response에는 다음 정보를 포함한다.

```json
{
  "meeting":{"participantIds":["pt_001","pt_002"]},
  "deliveries":[
    {
      "deliveryId":"dlv_xxx",
      "channel":"EMAIL",
      "recipientParticipantId":"pt_001",
      "status":"SENT",
      "attemptCount":1,
      "retryable":false
    }
  ]
}
```

`meeting.participantIds`는 Session 생성 때 저장된 immutable roster references다. `deliveries`는 Publish 접수 이전 생략하고 접수 뒤 generic Data Specification `Delivery[]`를 제공한다. Email row는 `recipientParticipantId`와 delivery status만 보여주고 Email 주소/Provider message ID/raw response를 포함하지 않는다. `lastAttemptAt`, `errorCode` 등 optionals는 값이 있을 때만 내보낸다.

## API 호출 및 UI 동작

1. SCR-007은 API-010 Session snapshot과 API-003 Participant cache/query를 읽고 API-010 `meeting.participantIds`만 선택 목록에 projection한다.
2. 사용자가 선택을 바꾸면 participant ID 집합을 local screen state에 유지한다. `저장하고 공유`는 TASK-010.03이 보장한 API-014 Confirm 성공 이후 새 Session version과 API-015를 사용한다.
3. API-015 body에는 선택된 ID 목록과 기존 `notificationEnabled`만 넣는다. 성공 `202`는 저장 완료가 아니며 `PUBLISHING` 상태와 document URL result는 TASK-010.03에 따라 처리한다.
4. API-010 polling은 최신 version 기준으로 같은 Session `deliveries`를 갱신한다. UI는 recipient별 진행/성공/실패와 `retryable` 여부를 표시하며 별도 resend를 실행하지 않는다.
5. `status=SENT`는 Gmail API 전송 요청 성공으로 표시한다. recipient inbox 도착/열람을 주장하지 않는다. API-010 `EMAIL_FAILED` 및 safe retryable은 재시도 action을 후속 TASK-013 화면에서 제공한다.

## 실패와 개인정보 경계

- roster IDs가 없거나 Session이 없으면 API-010/API-015의 기존 공통 오류로 처리한다. client는 수신자 선택값을 임의 생성하지 않는다.
- API-003 loading/error는 email metadata 표시 query의 문제다. Session state나 다른 channel 결과에 덮어쓰지 않는다. 현재 선택 정보를 보존하고 재조회 가능 안내를 제공한다.
- API-015 400/409/412/502는 common error envelope의 safe code/message로 표시한다. 요청 결과가 불명확하면 API-010으로 조정하고 다른 Idempotency-Key로 자동 재전송하지 않는다.
- Email list/Delivery result 영역의 오류는 Document 저장 성공과 Notification 독립 결과를 되돌리지 않는다.
- 전체 주소, OAuth token, Authorization header, email body, Provider error 원문을 log/history/API-010 delivery row에 저장하지 않는다.

## 접근성

- 각 checkbox는 Participant name과 Email로 accessible label을 제공하고, ID fallback 시에도 식별 가능한 label을 제공한다.
- 결과 status는 텍스트와 live region으로 제공하며 색상만 사용하지 않는다. Loading/partial failure 업데이트가 focus를 강제 이동하지 않는다.
- keyboard-only 및 360px 폭에서 참가자 선택과 결과 확인이 가능하다.

## 완료 기준

- Session roster 외 참가자를 선택하거나 API-015 request에 넣는 UI/API 경로가 없다.
- API-015에는 선택 IDs만 전송되고 recipient Email 주소는 Client→API request에서 제외된다.
- API-010 generic deliveries가 recipient별 결과를 보이며 PENDING/SENDING/SENT/FAILED와 retryable 조건이 일관된다.
- 빈 목록, 주소 누락, provider별 mixed success/failure에서도 각 결과가 독립 표시되고 document/notification 성공 상태가 보존된다.
- 전송되지 않은 결과를 SENT로 표시하지 않으며 Gmail send 성공을 inbox 도착으로 과장하지 않는다.
- Address/Secret/provider raw data가 response/log/history에 노출되지 않는다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

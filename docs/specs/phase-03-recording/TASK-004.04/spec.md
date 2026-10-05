# Meeting Session 생성 API 설계

## 목표

검증된 New Meeting 입력으로 휘발성 MeetingSession을 만들고 녹음에 필요한 upload policy를 반환한다. PRD v1.7.0 (2026-10-05), `FR-002`, `API-006`, `TASK-004.04`를 구체화한다. Issue [#17](https://github.com/donghyunlee-dev/meeting-automation/issues/17).

## 범위

- `POST /api/v1/meeting-sessions`와 `Idempotency-Key`
- 입력 필드 검증, Template/Participant 참조 확인
- `CREATED` 상태/version 1 Session memory 저장
- sessionId와 서버 upload policy 반환
- 공통 검증/충돌/Provider 오류 처리

## 비범위

브라우저 녹음, Audio 업로드, recovery API, DB/Session 영속화, Frontend POST 및 Recording 이동은 포함하지 않는다. Frontend mutation 연결은 `TASK-004.05`다.

## 요청 및 검증

Request는 `{title,templateId,participantIds,timezone,recoveryKey}`다. `Idempotency-Key`는 필수다. title 앞뒤 공백을 제거한 뒤 비어 있지 않아야 한다. `templateId`는 현재 Template 목록의 `default.md` 또는 `project.md`여야 한다. participantIds는 1개 이상, 중복 없는 문자열 ID로 구성하고 모든 ID가 API-003 roster에 있어야 한다. `timezone`은 유효한 IANA zone이어야 한다. `recoveryKey`는 Browser가 제공하는 비어 있지 않은 opaque string이며 Provider Meeting document에는 기록하지 않는다.

모든 입력 참조 오류와 누락 `Idempotency-Key`는 Provider Session 생성 전에 400 `VALIDATION_FAILED`로 반환한다. Participants 조회 오류는 502 `PARTICIPANT_LIST_FAILED`; Template static resource/config 내부 오류는 공통 500 `INTERNAL_ERROR`로 변환한다.

## 생성/멱등성

유효 요청은 `sessionId=ms_<opaque>`, `version=1`, `status=CREATED`인 MeetingSession을 memory store에 저장한다. `meeting.title`, `templateId`, `participantIds`, `timezone`을 Session에 포함한다. 같은 key와 동일 요청은 기존 생성 응답/Session을 재사용하고, 같은 key의 다른 payload는 409 `IDEMPOTENCY_KEY_CONFLICT`다. Session/idempotency 결과는 process memory에만 있으므로 process restart 이후 복구를 보장하지 않는다.

`201` uploadPolicy는 서버 설정값을 반환한다. PRD baseline 예시는 `chunkDurationSeconds=15`, `maxChunkBytes=5242880`, `acceptedMimeTypes=["audio/webm","audio/mp4"]`다. Client 입력값으로 policy를 바꿀 수 없다.

## 수용 기준

- 유효 요청이 정확한 Session snapshot을 한 건 만들고 명세된 201 응답을 반환한다.
- title/template/participant/timezone/recoveryKey/Idempotency-Key 입력 검증이 수행된다.
- Participant 참조는 roster ID 및 uniqueness만 검증한다.
- 동일 요청 재전송은 중복 Session을 만들지 않고 payload 충돌은 409다.
- 오류 envelope에서 Provider 원문/Secret과 내부 stack이 제거된다.
- Session과 key 결과가 memory-only이고 restart 이후 복구를 주장하지 않는다.

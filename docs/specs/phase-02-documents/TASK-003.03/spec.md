# 참가자 정보 수정 API 설계

## 목표

Participant roster의 name 또는 email을 부분 수정하는 Backend API를 구현한다. PRD v1.7.0 (2026-10-05), `FR-024`, `API-005`, `TASK-003.03`를 구체화한다. 관련 이슈는 [#12](https://github.com/donghyunlee-dev/meeting-automation/issues/12)다.

## 범위

- `PATCH /api/v1/participants/{participantId}` Controller와 Service
- 부분 입력 `{name?,email?}` 및 정규화/검증
- 단계별 `ParticipantUpdatingProvider.updateParticipant(participantId, command)` capability 호출
- 표준 `{id,name,email}` 응답 및 입력/미존재/Provider 오류 변환

## 비범위

Participant 생성/목록, 관리 UI, 삭제, 별도 저장소 및 변경 이력 기능은 포함하지 않는다.

## 계약과 동작

Body에 `name`, `email` 중 적어도 하나가 있어야 한다. 제공된 필드만 trim 후 검증하고 수정하며 생략된 필드는 기존 값을 보존한다. name은 trim 후 비어 있으면 안 된다. email은 trim 후 유효한 단일 주소 형식이어야 한다. 제공 필드가 null이거나 형식이 틀리면 `VALIDATION_FAILED`(400)다. 빈 body도 400이다.

대상 ID가 없으면 `PARTICIPANT_NOT_FOUND`(404)를 반환한다. 성공은 200 `{data:{id,name,email}}`다. Provider 저장 오류는 `DOCUMENT_FAILED`(502, `DOCUMENT_FAILURE`)로 변환한다. TASK-003.01 규칙(title=name, 본문 `Email: <address>`, page ID=id)을 그대로 사용하고 Provider 원문/인증정보를 응답·로그에 노출하지 않는다.

기존 Adapter는 단계별 capability를 구현하므로 이번 작업도 같은 방식으로 확장하며 후속 CRUD stub을 추가하지 않는다. 선택된 root의 Participants roster 소속을 확인해 다른 페이지를 수정하지 않는다. 수정하지 않은 본문·필드를 보존하고 mutation 결과가 불명확한 실패를 자동 재시도하지 않는다.

## 수용 기준

- name만 수정하면 기존 email을 보존한다.
- email만 수정하면 기존 name을 보존한다.
- 두 필드를 보내면 두 필드를 모두 갱신한다.
- 빈 body, null, 공백 name, 잘못된 email은 Provider 호출 없이 400이다.
- 존재하지 않는 ID는 404이며 성공 응답은 완전한 roster DTO다.
- provider page title/body가 표준 데이터와 일치하고 예외 원문은 격리된다.

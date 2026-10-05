# 구현 계획

## 의존성

- `TASK-004.02` (#15): App Config/static Templates, upload policy 설정
- `TASK-003.01` (#10): roster Participant lookup
- `TASK-001.04` (#4): 공통 API error envelope
- `TASK-004.03` (#16): 요청을 구성하는 UI와 `{title,templateId,participantIds,timezone}` callback
- PRD v1.7.0 `FR-002`, `API-006`; Issue [#17](https://github.com/donghyunlee-dev/meeting-automation/issues/17)

## 변경 대상

Meeting Session REST Controller, CreateSession request DTO/validator, application service/use case, in-memory Session store와 Idempotency-Key 협력자, Template/Participant lookup adapter, response DTO 및 Controller/Service tests를 구현한다. 정확한 코드 경로는 Backend scaffold에서 확인한다.

## 소유권과 계약

- API: `POST /api/v1/meeting-sessions`, 필수 `Idempotency-Key`
- Input: `{title,templateId,participantIds,timezone,recoveryKey}`
- Provider-neutral domain: `Session{sessionId,version=1,status=CREATED,meeting}`
- Output: `{sessionId,version:1,status:CREATED,uploadPolicy}`
- 오류: 400 `VALIDATION_FAILED`; 409 `IDEMPOTENCY_KEY_CONFLICT`; 502 `PARTICIPANT_LIST_FAILED`; 500 `INTERNAL_ERROR`

## 구현 순서

1. Request validation, lookup, persistence, idempotency success/conflict Controller/Service tests를 먼저 추가한다.
2. Request DTO와 title/timezone/participant/template/recovery key 검증을 구현한다.
3. Template과 전체 Participant roster를 읽어 참조를 확인한다. provider lookup 실패는 해당 표준 오류로 분리한다.
4. Session snapshot과 idempotency response를 memory store에 원자적으로 생성한다.
5. response upload policy를 server config에서 구성해 반환한다.
6. 오류 처리, duplicate 요청 및 volatile store 경계를 테스트하고 전체 Backend 검증을 수행한다.

Session 생성은 Audio 임시 자원과 외부 부작용 전의 상태 기반이므로 검증/저장을 Controller API 경계에서 먼저 고정한다.

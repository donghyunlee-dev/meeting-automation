# 구현 계획

## 의존성

- `TASK-003.01` (#10): 전체 roster DTO, Page ID/title/`Email:` 본문 매핑
- `TASK-002.01` (#6): `DocumentProvider.createParticipant(command)` Port 및 공통 오류 계약
- `TASK-002.02` (#7) 또는 `TASK-002.03` (#8): Provider별 Participant Page 생성
- `TASK-001.04` (#4): 공통 오류 envelope
- PRD v1.7.0, `FR-024`, `API-004`; Issue [#11](https://github.com/donghyunlee-dev/meeting-automation/issues/11)

## 변경 대상

Backend Participant Controller, application Service와 request validator, 생성 요청용 memory idempotency 조정 구성요소, Provider adapter 계약 테스트 및 API 문서. 현재 checkout에는 idempotency 구성요소가 없어 이번 Task에서 도입한다.

## 소유권과 계약

- API: `POST /api/v1/participants`, `Idempotency-Key` 필수
- 입력: `{name,email}`; 성공 응답은 `{data:{id,name,email}}`
- Service: 정규화/검증 후 Port 호출, 동일 키 요청을 멱등 처리
- Adapter: Participants child page 아래에 title과 Email 본문 규칙으로 생성
- 오류: `VALIDATION_FAILED` 400, `IDEMPOTENCY_KEY_CONFLICT` 409, `DOCUMENT_STRUCTURE_NOT_FOUND` 422, `DOCUMENT_FAILED` 502

UI는 이 Task에서 변경하지 않는다. 목록을 제공하는 TASK-003.01 계약을 재사용하며 독립 검증 가능한 Backend API 구현을 먼저 완료한다.

## 구현 순서

1. Validator, Controller, Service 및 idempotency 테스트를 실패 우선으로 추가한다.
2. 요청 정규화와 필드 검증을 구현한다. 잘못된 입력은 Provider를 호출하지 않아야 한다.
3. Service가 생성 요청용 memory idempotency 조정 구성요소를 사용해 동일 key의 Provider create를 한 번만 실행하고 최초 결과를 재사용하도록 연결한다.
4. 단계별 참가자 생성 capability와 두 Adapter가 TASK-003.01의 Page 저장 형식을 사용하도록 계약을 확인한다.
5. 오류 변환, 중복 재전송/충돌, secret 및 Provider 원문 비노출 테스트를 실행한다.

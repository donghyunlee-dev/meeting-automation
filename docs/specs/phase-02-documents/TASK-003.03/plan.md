# 구현 계획

## 의존성

- `TASK-003.01` (#10): `{id,name,email}` 및 Provider Page 표현
- `TASK-003.02` (#11): Participant create command와 입력 정규화 관례
- `TASK-002.01` (#6): 공통 DocumentProvider Port
- `TASK-002.02` (#7) 또는 `TASK-002.03` (#8): Provider update adapter
- PRD v1.7.0, `FR-024`, `API-005`; Issue [#12](https://github.com/donghyunlee-dev/meeting-automation/issues/12)

## 변경 대상

Participant API Controller, application Service, update request 검증, Provider adapter contract tests, 공통 404 오류 변환 및 API 문서다. 소스의 정확한 경로는 구현 전 모듈에서 확인한다.

## 소유권과 인터페이스

- API: `PATCH /api/v1/participants/{participantId}`; 성공 200
- 입력: `name`, `email` 중 적어도 하나. 생략된 값은 유지
- Service: Provider에서 기존 레코드를 찾아 부분 갱신을 수행하고 전체 DTO 반환
- Adapter: Provider page title 또는 Email 본문만 바꾸면서 나머지 필드 보존
- Port: ParticipantUpdatingProvider capability를 확장하며 후속 CRUD stub은 추가하지 않는다. Service는 선택된 root의 Participants 구조와 대상 소속을 확인한다.
- 오류: 400 `VALIDATION_FAILED`, 404 `PARTICIPANT_NOT_FOUND`, 502 `DOCUMENT_FAILED`

## 구현 순서

1. 필드별 수정/보존과 오류 상황에 대한 Service/API 및 Provider contract 테스트를 실패 우선으로 추가한다.
2. 요청 필드 존재 여부, null, trim, name/email 유효성을 검증한다.
3. 생략 필드를 보존하는 부분 update command를 Port에 전달하고 Provider가 전체 DTO를 반환하도록 Adapter를 구현한다.
4. 미존재 ID 및 Provider 예외를 표준 오류로 변환하고 민감 원문을 격리한다.
5. 관련 테스트 및 API/Data/Port 계약을 대조한다.

UI보다 Backend API를 먼저 안정화해 후속 참가자 관리 화면에서 부분 수정 결과를 신뢰할 수 있게 한다.

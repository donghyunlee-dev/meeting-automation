# 구현 계획

## 의존성

- `TASK-002.01` (#6): 공통 `DocumentProvider` Port 및 표준 DTO
- `TASK-002.02` (#7) 또는 `TASK-002.03` (#8): Provider Adapter와 Participants Page 탐색
- `TASK-001.04` (#4): 공통 오류 응답
- PRD v1.7.0 / `FR-024`, `API-003`; Issue [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10)

## 변경 대상

Backend Participant API Controller, application Service, Provider Port DTO/예외 변환, API/서비스/Provider contract tests, 그리고 구현에 필요한 API 문서 연결을 포함한다. 실제 파일 경로는 현재 backend 모듈 구조에 맞춰 구현 시 확인한다.

## 소유권과 계약

- API: `GET /api/v1/participants`, query parameter 없음
- Backend: `DocumentProvider.listParticipants()` 결과를 표준 envelope로 직렬화
- Provider Adapter: page ID/title/body에서 `{id,name,email}` 구성
- 오류: `DOCUMENT_STRUCTURE_NOT_FOUND` 422; `PARTICIPANT_LIST_FAILED` 502, 일시 오류만 재시도 가능
- Frontend는 이 Task에 포함되지 않는다. 소비 화면 연결은 후속 Task에서 수행한다.

## 구현 순서

1. 응답/오류 변환과 함께 성공, 빈 목록, 구조 누락, Provider 실패의 자동화 테스트를 먼저 추가해 실패를 확인한다.
2. Service와 Controller를 구현하고 기존 공통 Provider Port에서 전체 roster를 조회한다.
3. Page 필드 매핑 및 안전 오류 변환을 구현한다. malformed 필드는 부분 성공 대신 표준 실패로 처리한다.
4. 테스트를 통과시키고 API 명세 및 `DocumentProvider` 구현체 간 계약을 대조한다.

UI보다 API/Provider 계약을 먼저 완성해야 회의 선택 화면이 안정된 목록 및 오류 응답에 의존할 수 있다.

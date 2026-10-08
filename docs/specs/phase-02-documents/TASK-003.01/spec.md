# 참가자 목록 조회 API 설계

## 목표

회의 작성자가 관리하는 Participant roster를 API로 조회해 회의 생성, 화자 매핑, Email 수신자 선택에서 공통으로 사용한다. PRD v1.7.0 (2026-10-05), `FR-024`, `API-003`, `TASK-003.01`을 구현 가능한 계약으로 구체화한다. 관련 이슈는 [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10)이다.

## 범위

- `GET /api/v1/participants` Controller, Service 및 선택된 Provider의 참가자 조회 capability 연동
- 성공 응답 `{data:{items:[{id,name,email}]}}`; 결과가 없으면 빈 배열
- Provider Page ID를 `id`, Page title을 `name`, 본문 첫 `Email: <address>` 항목을 `email`로 매핑
- Provider 오류 원문과 인증정보를 노출하지 않고 표준 오류로 변환

## 비범위

참가자 생성/수정 및 화면 구현, 삭제 기능은 이 Task에서 구현하지 않는다.

## 결정된 규칙

Participant는 회의 작성자가 지정하는 roster record이며 `{id,name,email}`만 가진다. Provider Page 본문은 `Email: <address>` 단일 항목을 저장 형식으로 사용한다. 필수 구조 누락은 `DOCUMENT_STRUCTURE_NOT_FOUND`(422, 재시도 불가), Provider 조회 실패 또는 필드 해석 실패는 `PARTICIPANT_LIST_FAILED`(502, 일시 오류에 한해 재시도 가능)다. 부분 목록을 성공으로 반환하지 않는다.

미팅과 화자 매핑에서 선택된 Participant ID가 해당 roster 결과에 속하는지 확인하는 참조 무결성을 유지한다.

전체 `DocumentProvider` Port는 후속 CRUD까지 포함한다. 이 Task의 운영 Adapter는 `DocumentStructureProvider`를 확장한 `ParticipantListingProvider.listParticipants(participantsPageId)`를 구현하고, Application Service가 설정된 root의 필수 구조를 탐색한 뒤 Participants Page ID를 전달한다. 공개 API에는 query/filter 인자가 없으며 전체 roster를 반환한다. 단계별 capability 분리는 아직 구현하지 않은 CRUD를 runtime stub으로 제공하지 않기 위한 구현 결정이다.

## 수용 기준

- 목록 API가 항상 `id`, `name`, `email`만 포함한 표준 item을 반환한다.
- 목록이 비면 HTTP 200과 빈 `items`를 반환한다.
- Provider의 필수 구조 누락은 422 `DOCUMENT_STRUCTURE_NOT_FOUND`로 반환한다.
- 일시 Provider 오류 및 잘못된 Page 데이터는 502 `PARTICIPANT_LIST_FAILED`로 반환하고 원문을 응답/로그에 노출하지 않는다.
- 자동화 테스트에서 성공, 빈 목록, 구조 누락, Provider 실패 및 매핑 실패를 확인한다.

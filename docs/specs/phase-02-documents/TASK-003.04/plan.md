# 구현 계획

## 의존성

- `TASK-003.01` (#10): Participant 목록/빈 배열 계약
- `TASK-003.02` (#11): 생성 Request, 201 response, `Idempotency-Key`
- `TASK-003.03` (#12): 부분 수정 Request/response와 오류 계약
- `SCR-012`, `FR-024`, UI design의 상태 피드백/접근성 기준
- PRD v1.7.0; Issue [#13](https://github.com/donghyunlee-dev/meeting-automation/issues/13)

## 변경 대상

Participants 설정 화면과 route 연결, API client 타입 및 호출 함수, 화면별 form/list 상태, 컴포넌트·API mock tests, 필요 시 frontend test/build script를 변경한다. 현재 저장소에는 `frontend/` 앱이 없으므로 구현 시 scaffold/실제 디렉터리를 확인하고 구체 경로 및 npm 명령을 결정한다. API client와 form/list 컴포넌트는 역할별로 분리한다.

## 소유권과 계약

- Frontend: API 응답을 Participant `{id,name,email}`로 표시, 로컬 검색, 필드 검증 결과 연결, 성공 데이터 갱신
- Backend API dependency: API-003 GET, API-004 POST, API-005 PATCH를 변경 없이 소비
- 생성 재시도는 한 제출 시도 동안 같은 Idempotency-Key를 보존한다. 사용자가 payload를 바꾸면 새 시도용 Key를 만든다.
- 수정 실패 뒤 입력값은 유지한다. 404는 대상이 사라졌다는 안전 안내와 목록 재조회 action을 제공한다.

## 구현 순서

1. API client schema 및 테스트 환경의 현황을 확인한다. frontend/test script가 없으면 재현 가능한 Vite/React test/build 명령을 task 결과로 설정한다.
2. API mock 기반 화면 테스트를 먼저 작성해 로딩, Empty, Error, 검색, create/edit 흐름이 실패하는지 확인한다.
3. API client와 typed request/response/error mapping을 만든다.
4. Participants route, 검색 가능한 목록, add/edit form과 UI 피드백을 구현한다.
5. API mutation, key 유지, 성공 갱신, 오류 시 값 보존을 연결한다.
6. 모바일/키보드/보조기술 확인과 전체 frontend test/build를 수행한다.

API contract이 고정되어 있으므로 UI 상태 테스트와 API wrapper를 만들고, 이후 상호작용을 화면에 연결한다.

# 구현 계획

## 의존성

- `TASK-003.01` (#10): Participant 목록/빈 배열 계약
- `TASK-003.02` (#11): 생성 Request, 201 response, `Idempotency-Key`
- `TASK-003.03` (#12): 부분 수정 Request/response와 오류 계약
- `SCR-012`, `FR-024`, UI design의 상태 피드백/접근성 기준
- PRD v1.7.0; Issue [#13](https://github.com/donghyunlee-dev/meeting-automation/issues/13)

## 변경 대상

현재 `frontend/`에는 React 19.3, TypeScript, Vite 7, Vitest 5, Testing Library가 있는 앱이 존재한다. 기존 scaffold, package scripts 및 의존성을 재사용한다. 화면/API client/form 상태와 component/API mock tests를 추가하며 scaffold, framework, package scripts 또는 dependency 변경은 요구사항에 필요한 경우만 수행한다. API client와 화면 상태 책임은 역할별로 나눈다.

## 소유권과 계약

- Frontend: API 응답을 Participant `{id,name,email}`로 표시, 로컬 검색, 필드 검증 결과 연결, 성공 데이터 갱신
- Backend API dependency: API-003 GET, API-004 POST, API-005 PATCH를 변경 없이 소비
- 생성 재시도는 한 제출 시도 동안 같은 Idempotency-Key를 보존한다. 사용자가 payload를 바꾸면 새 시도용 Key를 만든다.
- 수정 실패 뒤 입력값은 유지한다. 404는 대상이 사라졌다는 안전 안내와 목록 재조회 action을 제공한다.

## 구현 순서

1. 기존 fetch/API, React, Vitest 및 test scripts를 확인한다. scaffold나 package scripts를 다시 만들거나 불필요한 dependency를 설치하지 않는다.
2. API mock component tests를 먼저 추가해 loading, roster/search Empty, retry, search, create/edit 흐름의 실패를 확인한다.
3. API-003~005의 typed request/response 및 안전한 error mapping을 API client에 구현한다.
4. SCR-012 Participants 설정 화면, 검색 가능한 roster 및 add/edit form을 기존 앱에 연결한다.
5. Idempotency-Key 재사용, 수정 필드별 PATCH, 성공 목록 반영 및 실패 입력 보존을 구현한다.
6. keyboard/focus, label/error association, 44×44px touch target, 모바일 overflow와 전체 test/lint/build를 확인한다.

API contract이 고정되어 있으므로 UI 상태 테스트와 API wrapper를 만들고, 이후 상호작용을 화면에 연결한다.

# 참가자 관리 화면과 API 연결 설계

## 목표

Settings의 Participants 화면에서 회의 작성자가 roster를 검색·추가·수정하도록 React UI와 API-003~005를 연결한다. PRD v1.7.0 (2026-10-05), `FR-024`, `SCR-012`, `TASK-003.04`를 구체화한다. 관련 이슈는 [#13](https://github.com/donghyunlee-dev/meeting-automation/issues/13)이다.

## 범위

- Participant 목록 조회, 검색, Loading/Empty/Error/Ready 표시 및 재시도
- `{name,email}` 생성 폼과 생성 API
- 기존 값이 채워진 `{name,email}` 수정 폼과 수정 API
- 성공/검증/네트워크 오류 표시 및 데이터 갱신
- mobile/keyboard/accessibility 기준 적용

## 비범위

Participant 삭제, 별도 계정 연결, 미팅 생성·Speaker mapping 화면, 서버 검색/pagination 및 백엔드 API 구현은 포함하지 않는다. 검색은 API-003이 반환한 전체 목록에서 수행한다.

## 화면 동작

Settings에서 Participants 관리 진입 시 목록을 조회한다. 최초 요청 중에는 Skeleton/progress, 응답이 빈 목록이면 Empty 설명과 추가 action, 응답에 항목이 있으면 목록을 표시한다. 검색은 이름/email에 대소문자 구분 없이 부분 문자열을 적용한다. 검색 결과만 없으면 검색어 삭제 action을 제공한다. 조회 실패는 안전한 오류 문구와 재시도 action을 제공하고 다른 화면으로 이동하지 않은 상태에서 다시 조회할 수 있다.

목록 row에는 이름, 이메일, 수정 action을 표시한다. 추가 action은 빈 폼을 열고 row 수정 action은 현재 두 필드를 채운 폼을 연다. name/email label과 입력 검증 오류를 각 필드에 연결한다. 빈 이름/email, 잘못된 이메일 오류는 Backend `VALIDATION_FAILED` 기준으로 보이며 입력값은 보존한다.

생성은 POST와 `Idempotency-Key`, 수정은 PATCH를 호출한다. 같은 생성 시도를 재전송하면 같은 Key를 유지한다. 성공하면 폼을 닫고 목록에 반환된 Participant를 반영하며 짧은 성공 안내를 보인다. 실패하면 폼과 입력을 유지한다. 수정 성공도 변경된 row를 반영하고 폼을 닫는다. 삭제 action은 없다.

## 접근성·반응형 기준

기존 SCR-012와 UI 설계의 기준을 따른다: label 연결, 오류의 텍스트 안내, 키보드 순서 및 visible focus, 주요 터치 대상 최소 44×44 CSS px, 모바일 폭에서 잘리지 않는 목록/폼, 색상만으로 오류를 표현하지 않는다.

## 수용 기준

- API 목록 조회·검색과 빈 결과/조회 오류 복구가 동작한다.
- 생성과 수정이 실제 API 계약을 사용하고 성공 결과를 화면에 반영한다.
- 동일 생성 시도의 재시도는 동일 Key를 사용하며 실패 폼 데이터가 유지된다.
- 입력 오류가 필드에 연결되고 민감한 Provider 원문은 표시되지 않는다.
- 화면에서 삭제를 제공하지 않으며 UI 접근성/모바일 기준을 통과한다.

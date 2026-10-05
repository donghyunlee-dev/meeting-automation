# App Config 및 Template 조회 API 설계

## 목표

Frontend에 공개 가능한 앱 설정과 Template metadata를 표준 API로 제공한다. PRD v1.7.0 (2026-10-05), `FR-002`, `API-001`, `API-002`, `TASK-004.02`를 구체화한다. Issue [#15](https://github.com/donghyunlee-dev/meeting-automation/issues/15).

## 범위

- `GET /api/v1/app-config`
- `GET /api/v1/templates`
- Backend 환경 설정의 공개 필드 mapping과 provider 미선택 처리
- Backend static resource의 default/project Template metadata 조회
- 공통 오류 envelope 및 secret 비노출 자동화 테스트

## 비범위

Frontend의 연결 안내/설정 화면, API-019 Integration Health, Secret 변경 UI, Provider 자동 선택, Template 편집/저장, Document Provider 내 Template 탐색은 포함하지 않는다.

## API-001 App Config

`200 {data:{company,document,email,notification,recording}}` 형태를 유지한다. 모든 영역은 항상 반환한다. Document provider 미설정 또는 공백은 `provider:null, configured:false`; 지원 Provider 선택 후 credentials 누락/형식 오류는 선택한 `provider`를 유지하고 `configured:false`다. 다른 provider로 자동 fallback하지 않는다. 유효한 설정은 configured true다. 미지원 enum은 500 `INTERNAL_ERROR`다. 설정 원문 중 Secret과 자격 값은 응답하지 않는다.

## API-002 Templates

`GET /api/v1/templates`는 Backend static resources의 `default.md`, `project.md`를 순서대로 `{id,name,version}` 목록으로 반환한다. 이름은 각각 `기본 회의록`, `프로젝트 회의`, version은 PRD baseline의 `1.0.0`이다. 두 파일 중 하나라도 누락되거나 읽지 못하면 부분 목록을 만들지 않고 500 `INTERNAL_ERROR` envelope를 반환한다. 오류 응답에 경로/stack trace를 노출하지 않는다.

## 수용 기준

- App Config가 공통 응답의 다섯 영역과 공개 field를 모두 반환한다.
- 미선택, 정상 설정, credentials 누락/오류, 미지원 enum 경계가 계약대로 처리된다.
- API-002가 두 Template metadata를 static resource에서 반환한다.
- Template 리소스 누락/읽기 실패가 안전한 공통 500 오류로 반환된다.
- 인증정보, Secret, 환경 변수 값과 내부 오류 세부내용은 response/log에 포함되지 않는다.

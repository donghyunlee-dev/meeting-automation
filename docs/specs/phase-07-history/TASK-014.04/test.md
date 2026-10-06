# 검증 계획

## 자동화 테스트

FE pure function/component/router tests에서 fixture data를 사용한다. API-018은 mock/fake 응답으로 확인한다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR009-TEXT-TITLE | query에 제목 일부, 대소문자/전각 variation | NFKC 및 case-insensitive 부분 일치 | filter unit test |
| SCR009-TEXT-PARTICIPANT | query가 attendee 일부와 일치 | 해당 Meeting 포함, 이메일은 비교/표시하지 않음 | filter unit test |
| SCR009-TEXT-DATE-AND | text match와 date filter 각각 다른 row에만 해당 | AND 결합 결과는 빈 목록 | filter unit test |
| SCR009-DATE-BOUNDARY | from/to의 첫날·마지막 날 및 인접 날짜 | 양 끝 날짜 포함, 인접한 바깥 날짜 제외 | timezone-fixed unit test |
| SCR009-DATE-PARTIAL | from만/to만 지정 | 해당 한쪽 경계만 적용 | filter unit test |
| SCR009-DATE-INVALID | from > to | 안내 문구를 보여주고 결과 필터에 잘못된 범위를 적용하지 않음 | component/unit test |
| SCR009-CLEAR | query 및 날짜를 모두 설정 후 초기화 | 모든 control 초기화, 전체 원본 목록 복원 | interaction test |
| SCR009-ORDER-GROUP | 날짜 월 경계를 가로지르는 API 최신순 fixture | 결과 순서 보존, local month별 group, 최신 월 우선 | filter/component test |
| SCR009-NO-MATCH | 원본 items는 있으나 filter 0건 | dataset Empty와 다른 “검색 결과 없음” 및 초기화 action | component test |
| SCR010-DETAIL | list row 선택 | `/meetings/{documentId}` 이동 및 API-018 GET 1회 | router/API test |
| SCR010-CONTENT | 상세 성공 DTO | 제목/일시/참석자/Minutes sections 표시; email 및 상태 badge 없음 | component test |
| SCR010-TRANSCRIPT | Transcript action | accessible dialog/drawer에 segment 순서, text, speaker label 표시; 닫기/키보드 동작 | interaction/a11y test |
| SCR010-404-ERROR | API-018 404 `MEETING_NOT_FOUND` | 없는 회의 안내 및 Meetings 목록 복귀 action | detail test |
| SCR010-PROVIDER-RETRY | API-018 provider error 후 retry | safe error 안내, GET 재호출, 원문 오류 미표시 | detail interaction test |
| SCR010-URL-SAFE | 유효 https/http URL | 명시적 click에서 새 탭, `noopener noreferrer` | link test |
| SCR010-URL-UNSAFE | null, javascript/data URL 또는 hostname 없음 | 외부 링크 control 렌더링 안 함 | link test |
| SCR010-READ-ONLY | 상세 DOM/actions 검사 | 편집/삭제 action 없음 | component assertion |

## 수동 QA

- Meetings 화면에서 제목/참석자 텍스트와 다양한 날짜 범위를 결합해 결과를 확인한다. 날짜는 browser local timezone의 하루 경계에서 테스트한다.
- 회의 row를 열어 Summary/sections와 Transcript drawer를 확인하고 목록으로 돌아온다.
- 비생산 환경의 유효 Provider URL을 click했을 때 새 탭에서 열리고, 유효하지 않은 URL은 action이 없는지 확인한다.
- 360 CSS px viewport, keyboard-only interaction, focus return 및 accessible names를 확인한다.

## 릴리스 확인

- FE 관련 unit/component/router tests, lint/build를 실행하고 `docs/evidence/TASK-014.04.md`에 결과를 기록한다.
- 최대 100건 초과 검색이 API를 호출하지 않는지 network panel로 확인한다.
- 응답/화면에 Participant email, Secret 또는 Provider 오류 원문이 노출되지 않는지 검토한다.

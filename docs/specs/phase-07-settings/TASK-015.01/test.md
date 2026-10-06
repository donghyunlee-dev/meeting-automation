# 검증 계획

## 자동화 테스트

Frontend unit/component/router tests에서 API-001/API-019 mock 응답을 사용한다. 테스트에서 Gmail API, Slack webhook 또는 Document Provider에 실제 연결하지 않는다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR011-LOAD | Settings route, 두 API pending | API-001/API-019 병렬 호출, skeleton 표시 | query/page test |
| SCR011-COMPANY | Company fixture | Company name 및 공개 timezone 표시, credential 없음 | component test |
| SCR011-DOC-NULL | provider null, configured false | Provider 미선택 연결 안내 및 관리자에게 Notion/Confluence 설정을 요청하는 다음 행동 | component assertion |
| SCR011-DOC-UNCONFIGURED | provider 지정, configured false | Provider 이름 유지, 설정 확인 안내, 자동 전환 없음 | selector/component test |
| SCR011-DOC-UNREACHABLE | configured true, reachable false | 연결 확인 필요 안내와 GET refresh action, secret 미표시 | interaction test |
| SCR011-DOC-ROOT | reachable true, rootAccessible false | Provider 접근과 Meetings/Participants 구조 문제를 구분 | selector/component test |
| SCR011-EMAIL-SLACK | 공개 설정 및 API-019 contributor 조합 | 각 영역의 안전 요약, 발송 성공 보장 문구 없음 | selector/component test |
| SCR011-SLACK-INITIAL | Slack configured true, reachable false | 아직 실제 게시하지 않았을 가능성을 반영하는 중립 문구 | component assertion |
| SCR011-PARTIAL-FAILURE | 각 endpoint 중 하나만 실패 | 성공한 섹션 유지, 실패한 섹션만 retry 가능 | interaction test |
| SCR011-ALL-FAILURE | 양 endpoint network failure | safe page error 및 전체 retry | page test |
| SCR011-REFRESH-GET-ONLY | refresh action | 두 GET만 발생, send/webhook 호출 0회 | mock call assertion |
| SCR011-NO-SECRET | token-like/Provider raw error fixture | DOM/visible error에 secret, root ID, provider body 없음 | DOM test |
| SCR011-NAV-A11Y | keyboard navigation | integration rows, Participants navigation, retry accessible/focusable | accessibility test |

## 수동 QA

- provider null, selected provider but unconfigured, unreachable, root inaccessible fixture로 Settings 안내가 구분되는지 확인한다.
- Refresh가 read-only GET만 호출하고 실제 Gmail/Slack message가 발생하지 않는지 확인한다.
- 360 CSS px에서 horizontal overflow가 없는지, Tab/Enter/Space로 retry와 navigation이 가능한지 확인한다.
- API payload와 화면에 Secret/token/Root ID/provider 원문 오류가 없는지 검토한다.

## 릴리스 확인

- repository-defined FE test/lint/build를 실행하고 docs/evidence/TASK-015.01.md에 기록한다.
- 응답, browser network panel, rendered DOM의 비밀정보 비노출을 확인한다.

# Settings 및 Integration Health 조회 연결 설계

> 📌 v1.9.0 변경 계약: TASK-022.06을 선행으로 추가한다. 문서 연결/변경 위저드와 상태 gate는 TASK-022가 소유한다. 이 작업은 그 route/action을 재사용하고 Company/Email/Notification health 조회를 보충한다. credential form을 별도로 복제하지 않으며 새 연결을 금지하는 옛 범위 문장은 적용하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 작업 식별 정보

- 작업: TASK-015.01
- 상위 작업: TASK-015 Settings / Integration Health
- 단계: Phase 7 — History / Settings / Operations
- PRD 기준: PRD-MA-001 v1.7.0, 2026-10-05
- 관련 요구사항: FR-026, API-001, API-019, SCR-011
- 영역: Frontend, Backend integration
- 선행 작업: TASK-002.04 (#9), TASK-011.01 (#46), TASK-012.01 (#48)
- GitHub Issue: [#56](https://github.com/donghyunlee-dev/meeting-automation/issues/56)

## 결과

Settings에서 API-001 공개 Company/Provider 설정과 API-019의 공통 integration health를 읽어 Company, Document, Email, Notification 정보를 표시한다. 문서 Provider가 null이거나 설정되지 않았으면 UI가 연결이 필요한 영역을 감지해 적절한 Settings 안내와 공용 연결 위저드의 다음 행동을 제안한다. 연결 입력·서비스 선택/전환은 TASK-022 공용 위저드를 재사용한다. 이 작업에서 별도 위저드나 OAuth 흐름을 중복 구현하지 않는다.

## 데이터 조회와 조합

- Settings 진입 시 GET /api/v1/app-config와 GET /api/v1/integrations/health를 병렬로 한 번 호출한다. 두 결과는 각각 독립 query/cache/error 경계를 가진다.
- Company 이름은 API-001 company.name, timezone은 공개 설정에 포함된 값만 표시한다. Document provider/configured는 API-001 공개 선택 값을 설정 요약에 표시하고 API-019 document 객체로 연결/reachability/root 접근 문구를 만든다.
- Email provider/configured/enabled는 API-001 공개 설정과 API-019 Email contributor를 결합한다. Notification은 provider/enabled와 API-019 notification contributor를 결합한다.
- API-019의 ai 객체는 공통 계약에 계속 포함되지만 SCR-011에 해당 설정 row가 없으므로 이 화면에서는 표시하지 않는다.
- 새로고침 action은 두 GET을 다시 호출한다. 자동 polling, test send, 실제 메일/Slack message를 보내는 동작은 없다.

## 화면 상태와 연결 안내

- Company row는 공개 Company 이름 및 설정 정보를 표시한다.
- Document Provider null은 문서 Provider 미선택으로 구분해 연결 필요 안내와 공용 Notion 또는 Confluence 연결 위저드를 여는 다음 행동을 보인다. 다른 Provider를 자동 선택하지 않고 TASK-022 공용 credential form을 연다.
- Provider가 선택됐지만 configured=false이면 Provider 이름을 유지하고 설정 확인이 필요하다는 안내를 표시한다.
- configured=true, reachable=false이면 설정은 존재하지만 연결 확인을 마치지 못한 상태로 표시하고 새로고침을 제안한다. rootAccessible=false면 Provider 접근과 필수 Meetings/Participants 구조 확인을 구분해서 표시한다.
- Email/Notification row는 각자 공개 설정과 health 값을 기준으로 표시한다. reachable=false만으로 실제 전송 실패를 단정하지 않는다. Slack은 무부작용 probe가 없어 아직 게시 전일 수도 있다.
- 첫 로딩 중 skeleton을 표시한다. 한 API가 실패하면 해당 영역만 안전한 오류와 retry를 보이고 성공한 다른 API의 데이터는 계속 표시한다. 전체 응답 실패 때는 page-level retry를 제공한다.
- Secret 원문, OAuth token, webhook URL, Root ID, Provider 오류 본문은 화면에 표시하지 않는다.

## 오류/보안 경계 및 비범위

- API-001과 API-019 common data envelope를 typed frontend 모델로 해석한다. 미선택 Provider의 provider:null을 문자열로 바꾸지 않는다.
- API-019는 HTTP 200이어도 각 integration contributor boolean을 정상 데이터로 표시하며 한 영역의 미연결을 전체 API 실패로 취급하지 않는다.
- 문서 credential 저장과 명시적 Provider 변경은 TASK-022가 소유한다. 이 작업은 공용 route/action을 연결한다. 자동 Provider 전환·전송 probe는 추가하지 않는다.
- Participants roster CRUD는 별도 Participants Settings 작업의 책임이다.

## 완료 기준

- API-001 Company/공개 설정과 API-019 integration data를 병렬 읽고 화면에 표시한다.
- document.provider=null, configured=false, unreachable, root inaccessible 시나리오가 구분되고 적절한 공용 연결/변경 안내를 제시한다.
- API 부분 실패는 다른 성공 정보 표시를 막지 않고 영역 단위 재시도를 제공한다.
- Email/Notification 상태는 실제 발송 성공을 보장한다고 표현하지 않으며 실제 test message를 보내지 않는다.
- Secret/provider details가 응답 모델에서 UI까지 노출되지 않는 것을 검증한다.
- 접근성과 작은 모바일 viewport를 지원하고 모든 기준이 검증 사례에 대응한다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

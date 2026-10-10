# Provider 선택 및 Health 작업 목록

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.04의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

구현 순서는 [구현 계획](./plan.md), 응답과 경계는 [명세](./spec.md), 검증은 [테스트 계획](./test.md)을 따른다. Provider 구현은 선행 Issue #7과 #8의 Adapter를 소비한다.

## Backend 구현

- [ ] API-001/019 응답 DTO 계약 테스트를 작성한다. 결과: provider nullable, document status, 네 integration key가 고정되며 구현 전 테스트가 실패한다.
- [ ] optional `DOCUMENT_PROVIDER` resolver와 NOTION/CONFLUENCE registry를 작성한다. 결과: blank는 선택 없음, 유효 enum은 해당 Adapter, unknown non-empty 값은 설정 오류다.
- [ ] 활성 Adapter별 필수 설정을 검증한다. 결과: 선택 없음/활성 credentials 누락이 `configured=false`이며 선택된 다른 Adapter로 fallback하지 않는다.
- [ ] API-001을 configured/provider 응답에 연결한다. 결과: 일반 설정 외 Provider credentials 및 Root ID는 노출하지 않는다.
- [ ] 공통 `IntegrationHealth` 및 contributor interface를 작성한다. 결과: document, email, notification, ai 객체가 항상 생성된다.
- [ ] 미등록 Email/Notification/AI 상태 contributor 기본값을 추가한다. 결과: 각 미연동 영역의 configured/reachable은 false이며 이후 전용 contributor로 교체할 수 있다.
- [ ] Document Health contributor를 구현한다. 결과: 연결 인증과 Root/필수 child 구조 발견을 구분하고 구조 미완성은 rootAccessible=false로 응답한다.
- [ ] Health 오류를 API-019 status DTO로 격리한다. 결과: external error body와 자격증명이 제거되고 한 contributor 실패가 다른 영역에 영향을 주지 않는다.
- [ ] Health probe에 쓰기 요청이 없음을 테스트한다. 결과: fake transport에서 Page create/update 요청이 없다.
- [ ] `./gradlew test`, `./gradlew clean build`를 실행하고 `docs/evidence/TASK-002.04.md`를 작성한다. 결과: 전체 검증 명령과 비민감 결과가 기록된다.

## 완료 결과

- [ ] API-001/019 JSON이 명세의 상태 행렬과 일치하고 frontend 연결 안내가 `provider:null`/미설정을 소비할 수 있다.
- [ ] Regression 및 Secret/Provider error 비노출 증거가 연결된다.

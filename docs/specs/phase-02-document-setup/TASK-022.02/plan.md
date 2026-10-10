# 🛠️ Notion과 Confluence 기본 페이지 초기화 구현 계획

> TASK-022.02 · PRD v1.9.0 · 선행: TASK-022.01
> GitHub Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)

## 🧩 변경 범위와 책임

Document bootstrap Port/UseCase, NotionPageHierarchyAdapter, ConfluencePageHierarchyAdapter, 전역 상태 journal, operation Controller

Backend가 Port/유스케이스/Adapter와 unit·계약 테스트를 소유한다. 공개 API와 durable 상태를 먼저 완성해 후속 UI가 임의 상태를 만들지 않게 한다.

## 🔌 인터페이스

initializeStructure는 discoverStructure와 분리한다. API-026은 테스트된 동일 draft revision만 허용하고 202 operation을 반환한다. API-027은 public summary만 반환한다. 신규 최초 연결은 페이지 확인 후 active/version을 원자적으로 교체한다. Confluence space 최상위 또는 parentId 아래, Notion 지정 부모 아래 생성한다.

## 🧭 구현 순서

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- Document bootstrap Port/UseCase, NotionPageHierarchyAdapter, ConfluencePageHierarchyAdapter, 전역 상태 journal, operation Controller에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.02/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

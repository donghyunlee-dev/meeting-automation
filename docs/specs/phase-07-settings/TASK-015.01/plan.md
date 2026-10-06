# 구현 계획

## 의존성

- TASK-002.04 API-001/API-019 공통 설정·Integration Health (#9)
- TASK-011.01 Gmail Email health contributor (#46)
- TASK-012.01 Slack Notification health contributor (#48)
- PRD v1.7.0 (2026-10-05), FR-026, API-001, API-019, SCR-011
- GitHub Issue: [#56](https://github.com/donghyunlee-dev/meeting-automation/issues/56)

## 변경 대상

- Settings route/page: 기존 navigation shell, Company row, Document/Email/Notification cards 및 Participants 관리 진입점을 재사용한다.
- API client/types: AppConfig 및 IntegrationHealth public DTO와 common data envelope.
- Query/cache layer: 두 endpoint의 독립 query key, 병렬 로드, 영역별 retry/refetch 및 기존 cache 정책.
- View-model/selector: 공개 provider/enabled/configured 값과 API-019 contributors를 섹션별 표시 모델로 조합한다. Secret/root identity 필드를 추가하지 않는다.
- UI components: skeleton, inline error/retry, safe connection copy, connection indicators, 관리자 설정 요청 안내.
- Tests: API mapping/selector, provider null/configured/reachability/root 조합, 독립 실패, no-secret/no-side-effect UI 검증.

## 구현 순서

Backend common response contract와 Provider contributors가 선행되어 있으므로 typed client/query부터 구성하고 화면 상태를 연결한다.

1. API-001/API-019 response fixture 및 selector tests를 작성한다: common envelope, provider null, configured/reachable/rootAccessible 조합, email/slack unknown semantics.
2. Settings page tests를 작성한다: 병렬 요청, partial failure, 영역 재시도, 전체 재시도, no secret, 안내 동작.
3. typed API clients와 독립 query hooks를 추가하고 응답이 섹션별 view-model로 바뀌는지 검증한다.
4. Settings sections/loading/error/retry/provider-null 안내를 구현하고 기존 Participants entry와 Bottom Navigation을 유지한다.
5. API failure/health false states, accessibility, keyboard, 360px viewport, no-side-effect interaction을 통합 검증한다.

## 검증

정확한 FE scripts는 구현 단계에서 저장소 package 설정을 확인해 사용한다. 실제 Email/Slack posting은 검증에 포함하지 않는다. 사례와 evidence는 [검증 계획](./test.md)을 따른다.

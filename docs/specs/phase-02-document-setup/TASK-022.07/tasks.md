> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# TASK-022.07 작업 목록

- [x] 기존 최초 연결 UI의 360px 문제를 확인하고 스크린샷/관찰 결과를 기록한다.
- [x] Notion 안내에 내부 연결 생성, token 발급, 부모 페이지 공유, 필요한 권한을 단계별로 설명하고 공식 문서 링크를 추가한다.
- [x] Confluence 안내에 운영 계정/API token, 사이트 URL, Space ID, 권한 확인 절차와 공식 문서 링크를 추가한다.
- [x] 테스트 범위, token 임시 입력/비저장 동작, 실제 쓰기 권한 확인 시점을 명확히 알린다.
- [x] 모바일 너비에서 Provider 선택과 가이드/폼 레이아웃, 버튼 문구, 하단 내비게이션 안전 여백을 개선한다.
- [ ] 변경된 화면과 기존 Provider 흐름을 검증하고 `docs/evidence/TASK-022.07/verification.md`를 작성한다.
- [ ] 변경을 commit/push하고 `master` 대상 PR을 생성한다. 정확한 PR head에서 리뷰와 필수 CI를 통과한 뒤 leader가 병합한다.
- [ ] Issue/PR 상태, Evidence, PRD 개발 tracker를 갱신한다.

> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# TASK-022.07 검증 계획

## 집중 검증

- 연결 안내를 렌더링하는 기존 `DocumentSetupWizard` 테스트 중 Provider 선택/전환/입력/테스트 관련 케이스를 실행한다. 안내 문구/링크에 회귀가 있으면 구체적인 사용자 관찰 검증을 추가한다.
- 관련 ESLint와 production build를 실행해 TSX/CSS 변경의 정적 오류 및 패키징 오류를 확인한다.
- 사용자 제공 개발 서비스에서 360px 모바일 viewport로 초기 연결 화면을 확인한다. Provider 선택, 각 Provider 안내/입력, 마지막 버튼과 고정 하단 내비게이션을 확인하고 가로 넘침/문구 잘림/가림 여부를 검사한다.
- Notion과 Confluence를 오가며 입력값 초기화 동작이 보존되는지 확인한다. token 등 실제 비밀정보는 입력하지 않는다.

## 기록과 완료 조건

### 현재 결과

- PASS — `npm run test -- src/DocumentSetupWizard.test.tsx` (15 tests)
- PASS — `npm run lint`
- PASS — `npm run build`
- PASS — `npx playwright test e2e/responsive-viewports.spec.ts --config playwright.viewport.config.ts --project=chromium --grep "TASK-022.07"` (360px; Notion/Confluence 안내, 버튼 줄바꿈, 문서 폭, 하단 메뉴 여백)
- 모바일 캡처: Playwright test output의 `notion-first-connection-mobile.png`; 비밀정보 입력 없이 촬영.

정확한 PR head SHA와 최종 브라우저 검토를 `docs/evidence/TASK-022.07/verification.md`에 기록한다. 실제 Provider API 연동은 이 화면 변경의 필요 조건이 아니며, 실제 계정 연결 여부를 확인한 것처럼 기록하지 않는다. 필수 검증 실패 또는 미실행은 DONE 처리하지 않는다.

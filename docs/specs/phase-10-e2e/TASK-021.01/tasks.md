# Mock 기반 핵심 E2E 작업 목록

- [ ] 기능 선행 작업과 recording/processing/review contracts가 실제 route/API/provider seam으로 구현됐는지 확인한다. 의존: PRD TASK-001.05~TASK-020.04 predecessors. 완료 결과: FE/BE boot instructions, endpoint/route inventory, mock ports가 소스에 존재하거나 구체 선행 blocker로 연결된다.
- [ ] failing Backend test를 먼저 작성해 API-006/007/009/010과 fake provider order/safe stage failure를 검증한다. 의존: Backend API 구현. 완료 결과: success/failure scenario별 provider call restriction과 Session snapshot assertion red.
- [ ] Backend `e2e` test profile/adapters/reset을 최소 구현한다. 의존: red tests. 완료 결과: external SDK/network 없이 Gradle profile이 boot되고 fixture가 분리된다.
- [ ] Playwright config의 dual web servers/readiness/CORS/test-origin 및 `npm run test:e2e` script를 추가한다. 의존: FE/BE bootable. 완료 결과: runner가 Vite와 Gradle wrapper 서버를 함께 준비하고 종료한다.
- [ ] deterministic MediaRecorder browser stub + UI happy path를 구현한다. 의존: browser UI/Recording/uploader. 완료 결과: API-007 bytes/hash/length와 API-008 accepted sequence가 UI에서 검증된다.
- [ ] processing poll, Speaker mapping, Minutes edit, confirmed template regeneration flow를 검증한다. 의존: API-010~013/UI features. 완료 결과: Transcript/mapping invariant와 STT/diarization no-recall guard green.
- [ ] one-shot retryable STT failure → 사용자의 API-020 재시도 → REVIEW 복구 시나리오를 추가한다. 의존: TASK-017.02/.03 failure/retry contracts. 완료 결과: private Audio 만료 metadata, API-020 once, completed assembly skip, retry success, no attendee publish 검증.
- [ ] FE/BE suite, lint/build 및 repeated clean E2E 실행 후 `docs/evidence/TASK-021.01.md`를 기록한다. 의존: happy/failure scenario. 완료 결과: report ID, build/commit, isolated storage cleanup, 개인정보 redaction이 확인된다.

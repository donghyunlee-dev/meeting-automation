# 구현 계획

## 선행 조건

- TASK-017.02 Issue [#61](https://github.com/donghyunlee-dev/meeting-automation/issues/61): API-010 failure action, API-020 retry, API-021 attachment download, API-022 failure finalize
- TASK-006.06 #32: API-010 common response/status snapshot
- TASK-006.07 #33: `SCR-005` stage mapping, polling, cancellation, route handling
- Source of truth: PRD v1.8.1, `docs/product/api-spec.md`, `docs/product/ui-design.md`

## 변경 경계

| 파일/컴포넌트 | 담당 | 변경 |
|---|---|---|
| Processing page / route | FE | PROCESSING_FAILED, completed warning, SESSION_NOT_FOUND states |
| API client/types | FE | API-010/020/022 typed response, action enum, request id/version headers, common error parsing |
| processing stage/error mapper | FE | stage → Korean user copy; raw errorCode/provider response hidden |
| retry mutation | FE | new key per attempt, current If-Match, lock/reconcile uncertain response via API-010 |
| native Audio download anchor | FE | API-010 preflight, API-021 attachment navigation, return-to-page saved confirmation |
| finalization interaction | FE | explicit `DOWNLOADED`/`DISCARDED`, same-key transport replay/new-key next attempt |
| expiry refresh lifecycle | FE | local informational countdown, server-authoritative actions, single expiry timer, visibility refresh |
| failure-state components/styles | FE | action priority, safe status/error copy, 44px target, 360px, reduced motion |
| component/API integration tests | FE/INTEGRATION | action visibility, requests, downloads, async state reconciliation, accessibility |

## 구현 순서

1. Frontend workspace location, route ownership, API base URL, common envelope/error client, test runner, shared buttons/modal, and current Bottom Navigation hiding are discovered at implementation start. Current documentation checkout contains no `frontend/` directory, so exact file paths and package scripts are implementation-time facts, not guessed here.
2. API-010 response/action union and localized failure stage mapper are typed first. This creates one server-authoritative UI contract before adding controls.
3. Failure-state component and allowedActions visibility tests are written before handlers. Rendered text/buttons are checked across stage and retryability combinations.
4. API-020 retry mutation is connected. One key is generated per user attempt; network replay shares that key. On uncertain outcome, GET API-010 reconciles before any new attempt. Single-flight prevents duplicate provider work.
5. API-021 native download anchor and save-confirmation UI are connected. Using the browser attachment avoids loading large Audio into React state. Only server `Content-Disposition` determines the saved filename/MIME.
6. API-022 `DOWNLOADED`/`DISCARDED` finalize controls and safe DOCUMENT_FAILED recovery are connected. Success navigates to completed-warning result and does not invoke share APIs.
7. TTL/visibility refresh lifecycle is wired. Failure state stops frequent polling and uses an expiry timer plus refresh on visibility/action; server actions remain the authorization source.
8. Keyboard/screen reader/reduced-motion/mobile viewport checks and end-to-end request inspection complete the integration.

## 상태 전이 및 FE/BE 소유권

- BE owns `status`, `processing.stage`, `retryable`, `audioAvailable`, `audioExpiresAt`, and `allowedActions`; FE presents those values and never guesses eligibility.
- API-020 acceptance returns the session to `PROCESSING`; API-010 subsequently determines progress/failure/review. FE never resubmits API-009 to retry.
- API-021 binary success remains browser-managed; FE does not read the entire file into a JavaScript Blob or log its bytes/path.
- UI expiry and availability come from API-010; Frontend never accesses object-storage URLs or credentials directly.
- API-022 is the only FE command that finalizes conversion failure. Successful finalization has no Email/Slack interaction.
- Failure screen displays no partial Transcript/Minutes and no Provider detail.

## 검증 접근

Automated component/client tests use the repository's discovered frontend package scripts and existing test libraries. The current checkout has no frontend manifest, so no test command is invented. Manual checks cover browser-native download behavior on Android Chrome and iOS Safari, saving confirmation, background/foreground expiry refresh, keyboard/screen reader, and 360px viewport.

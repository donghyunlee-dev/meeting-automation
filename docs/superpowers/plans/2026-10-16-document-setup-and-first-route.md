# Document Setup and First Route Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first production visit present a concise, mobile friendly Notion/Confluence connection flow, create the complete default document structure, and enter New Meeting after the first successful setup.

**Architecture:** Keep document credentials and all Provider writes in the Spring Backend. Extend the existing durable bootstrap journal so it creates and verifies the full hierarchy and seeded default template idempotently. Keep the React frontend responsible for setup state, compact connection UI, route gating, and the one-time transition caused by the successful initialization operation.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, React 19, TypeScript 5.9, React Router 8, Vitest, Testing Library, Vite 7, Vercel frontend, Render Docker backend.

**Spec:** `docs/superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md`

## Global Constraints

- Notion or Confluence credentials are sent only to the Backend and remain encrypted in the global settings snapshot.
- The connection test performs no Provider write.
- Initialization is explicit, journaled, idempotent, and verifies every created or reused page before activation.
- The root is `Meeting Automation`; its children are `Home`, `Meetings`, `Participants`, `Templates`, and `Speaker Memory`.
- `Templates` contains one seeded `기본 회의록` page whose content is never overwritten after successful creation.
- The normal app remains unavailable until setup is `READY`, while `/setup/document`, `/settings`, and `/settings/document` remain reachable.
- A setup completed from the first-run route goes to `/meetings/new`; a later visit to `/` shows Home.
- The UI uses short Korean copy, no external UI dependency, keyboard accessible controls, and a single-column mobile layout.
- Vercel hosts the Vite frontend only. Audio upload and processing remain Backend responsibilities.
- The repository's production branch is `master`, despite the conversational use of `main`.

## Review Focus

- A lost Provider create response is reconciled by marker for every new page and never creates a duplicate.
- A hierarchy with duplicate titles or a non-page child fails before a later write is attempted.
- Existing Provider pages are reused without replacing user-edited Home or template content.
- Secret fields clear after testing and no secret appears in browser storage, URLs, messages, or the public bundle.
- A narrow mobile viewport keeps labels, inputs, Provider cards, and the primary action readable without horizontal scroll.

---

### Task 1: Restore the approved Backend-owned audio boundary before release

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/audioUploadApi.ts`
- Modify: `frontend/src/audioUploadApi.test.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Delete: `frontend/api/audio-storage.ts`
- Delete: `frontend/api/audio-upload-token.ts`
- Delete: `frontend/src/server/audioBlobManifest.ts`
- Delete: tests under `frontend/src/server/` that only cover Vercel Blob
- Modify: Backend audio files changed by commit `95a8669` to restore direct Backend chunk receipt and private Backend storage
- Preserve: `Dockerfile`, `.dockerignore`, `.github/workflows/frontend-validation.yml`, Node 24, and the restricted Vite public environment prefix

**Interfaces:**
- Browser uploads recording chunks to the existing Backend audio endpoint.
- The production frontend needs only `VITE_API_BASE_URL`; it does not need `BLOB_READ_WRITE_TOKEN` or Render/Blob shared secrets.

- [ ] In an isolated worktree based on `origin/master`, add or restore the upload contract tests that expect Backend chunk upload and confirm they fail against the Blob implementation.
- [ ] Remove the Vercel Blob client, Functions, manifest code, package dependency, and Blob specific Backend grant/control code while preserving the Docker and CI files required for deployment.
- [ ] Run the focused frontend upload tests and Backend audio tests until they pass.
- [ ] Commit only this release prerequisite as `fix(audio): restore backend-owned recording uploads`.

### Task 2: Bootstrap the complete Provider document structure

**Files:**
- Modify: `backend/src/main/java/com/meetingautomation/document/DocumentStructure.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/bootstrap/BootstrapPages.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/bootstrap/DocumentStructureInitializer.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/bootstrap/NotionBootstrapPages.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/bootstrap/ConfluenceBootstrapPages.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/bootstrap/DocumentBootstrapUseCase.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/settings/GlobalDocumentSettings.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/settings/GlobalSettingsUseCase.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/transfer/DocumentSwitchUseCase.java`
- Modify: `backend/src/test/java/com/meetingautomation/DocumentStructureInitializerTests.java`
- Modify: `backend/src/test/java/com/meetingautomation/DocumentBootstrapProviderTests.java`
- Modify: `backend/src/test/java/com/meetingautomation/DocumentBootstrapUseCaseTests.java`
- Modify: `backend/src/test/java/com/meetingautomation/document/GlobalSettingsTests.java`
- Modify: `backend/src/test/java/com/meetingautomation/document/transfer/DocumentSwitchUseCaseTests.java`

**Interfaces:**
- `DocumentStructure` returns identifiers for root, Home, Meetings, Participants, Templates, default template, and Speaker Memory.
- `BootstrapPages` creates a page with a marker and optional seed content; Provider adapters translate the shared seed into their native page body.
- The initialization journal uses stable steps `ROOT`, `HOME`, `MEETINGS`, `PARTICIPANTS`, `TEMPLATES`, `DEFAULT_TEMPLATE`, and `SPEAKER_MEMORY`.

- [ ] Change `DocumentStructureInitializerTests` first to require the five root children plus one `기본 회의록` child under Templates, correct order, complete journal entries, reuse, conflict detection, and lost-response recovery; run the test and confirm the old three-page initializer fails.
- [ ] Expand `DocumentStructure`, the initializer definitions, validation, and durable journal reconciliation with the minimum fields needed by later template and Speaker Memory work.
- [ ] Add Provider adapter tests first for Notion block payloads and Confluence storage payloads containing the marker, concise Home text, Speaker Memory guidance, and the default minutes sections; confirm they fail before adapter changes.
- [ ] Extend the Notion and Confluence bootstrap adapters to create the seeded content and read back the page identity and marker without overwriting reused pages.
- [ ] Update activation and document-switch construction to persist all structure identifiers in the encrypted settings snapshot.
- [ ] Add compatibility coverage for reading an older connection snapshot with absent new identifiers; report it as requiring setup attention instead of claiming `READY` or discarding credentials.
- [ ] Run the focused bootstrap, settings, App Config, and document-switch tests until they pass.
- [ ] Commit the Backend behavior as `feat(document): create complete default workspace`.

### Task 3: Redesign the first connection experience

**Files:**
- Modify: `frontend/src/DocumentSetupWizard.tsx`
- Modify: `frontend/src/DocumentSetupWizard.test.tsx`
- Modify: `frontend/src/app.css`

**UI contract:**
- Provider selection shows two calm, full-card choices with a small product mark, Provider name, and one short description.
- The selected Provider form shows only essential fields. Setup instructions move into a compact expandable help section and external documentation link.
- The primary sequence is `서비스 선택` → `연결 테스트` → `연결하고 시작하기`; long operational text appears only for failures that need action.
- Inputs are at least 48 px tall and 16 px on small screens; cards and actions become one column below 640 px.

- [ ] Update `DocumentSetupWizard.test.tsx` first for the concise Provider cards, essential labels, expandable help, preserved secret clearing, full structure preview, and the new primary action copy; run it and confirm the current UI fails.
- [ ] Refactor only the first-run selection and input sections into small local view components while retaining the existing draft, test, initialization, recovery, and settings-switch behavior.
- [ ] Replace the long always-visible guide and dense copy with short labels and contextual help; keep accessible names, focus-on-error, status announcements, and password autocomplete behavior.
- [ ] Add responsive setup styles for provider cards, progress cue, form spacing, input sizing, focus states, and full-width mobile actions without adding a component library or image assets.
- [ ] Run the focused wizard tests, viewport checks for the setup route, and accessibility checks.
- [ ] Commit the UI as `feat(frontend): simplify document connection setup`.

### Task 4: Make setup gating and first success routing consistent

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/DocumentSetupWizard.tsx`
- Modify: `frontend/src/DocumentSetupWizard.test.tsx`

**Interfaces:**
- SetupGate sends every meeting, recording, processing, review, share, completion, and participant business route to `/setup/document` unless setup is ready.
- Public setup and settings routes remain available when the Provider is not configured or settings storage is unavailable.
- Only a successful initialization reached from the first-run gate navigates with `replace` to `/meetings/new`.

- [ ] Add route tests first for unconfigured deep links, READY routes, storage unavailable settings access, ordinary Home revisit, and first-run initialization success; confirm missing gates and redirect behavior fail.
- [ ] Centralize the protected business routes under the existing gate without changing their page behavior.
- [ ] Carry a safe first-run navigation state from SetupGate and use `useNavigate` after the accepted initialization operation reaches `SUCCEEDED`; do not use local storage as setup truth.
- [ ] Run the focused App and wizard route tests until they pass.
- [ ] Commit the routing change as `feat(frontend): route first setup into new meeting`.

### Task 5: Prepare Vercel and Render deployment contracts

**Files:**
- Create: `frontend/vercel.json`
- Modify only if inaccurate: `docs/setup/frontend-setup.md`
- Modify only if inaccurate: `docs/setup/backend-setup.md`
- Verify: `Dockerfile`
- Verify: `frontend/vite.config.ts`

**Deployment contract:**
- Vercel project root: `frontend`; framework: Vite; install: `npm ci`; build: `npm run build`; output: `dist`; production branch: `master`.
- Vercel environment: `VITE_API_BASE_URL=https://<render-backend-host>` for Production and Preview.
- Render source: repository root Dockerfile; health check: `/actuator/health`; service port: `$PORT`.
- Render document settings require an absolute `DOCUMENT_SETTINGS_DIR` on a persistent disk and a 32-byte Base64 `DOCUMENT_SETTINGS_ENCRYPTION_KEY`. A free ephemeral filesystem cannot satisfy the current safety contract.
- Render also needs the already documented OpenAI, Gmail, company, CORS, and recording variables; secret values are set in the dashboard and never committed.

- [ ] Add a Vercel SPA rewrite so direct visits to `/setup/document` and `/meetings/new` serve `index.html`.
- [ ] Verify the Docker image binds to Render `$PORT`, includes Backend code only, and has no Vercel Blob configuration.
- [ ] Compare documented environment names with actual `@Value` bindings and correct only mismatches that block this deployment.
- [ ] Run configuration syntax checks and the Vite production build.
- [ ] Commit deployment configuration as `chore(deploy): configure frontend and backend hosting`.

### Task 6: Verify locally, publish `master`, and inspect both deployments

**Files:**
- No new product files expected.

- [ ] Run `./gradlew test` from `backend` and record the exact pass/fail result.
- [ ] Run `npm test`, `npm run lint`, and `npm run build` from `frontend` and record the exact pass/fail result.
- [ ] Start the Backend with a temporary local settings directory and generated test encryption key, then start the Vite frontend with the local API URL.
- [ ] Open the app at desktop and mobile viewport sizes and verify the unconfigured root reaches the redesigned Provider selection, Provider selection reaches its compact form, and no horizontal overflow or browser console error appears.
- [ ] Verify the test can reach the connection-test request boundary without exposing a supplied secret. A real Notion/Confluence success remains dependent on credentials entered by the user.
- [ ] Re-read this plan and the approved spec, inspect the complete diff, and confirm no unrelated dirty-tree files are included.
- [ ] Use the repository GitUp workflow to rebase the scoped commits onto current `origin/master`, preserve unrelated local documentation changes, and push the verified commits to `origin/master` without force.
- [ ] Inspect the Git-connected Vercel and Render deployments for the pushed commit. If credentials or a Render persistent disk are missing, report the exact dashboard values or infrastructure action required instead of weakening the application safety checks.
- [ ] Verify the deployed frontend loads, the Backend health endpoint responds, CORS permits the Vercel origin, and `/api/v1/document-setup` returns a usable setup state.

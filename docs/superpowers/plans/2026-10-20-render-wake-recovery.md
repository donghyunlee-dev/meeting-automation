# Render Wake Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect when the configured backend origin is unavailable, show one animated recovery overlay, poll backend readiness, and resume safe requests after recovery.

**Architecture:** Add a minimal CORS-accessible readiness endpoint under `/api/v1/system/health`. Route frontend API transport failures through one coordinator that probes readiness, publishes overlay state, polls without overlap, and retries only safe or idempotency-keyed requests. Mount one accessible, reduced-motion-aware ice-to-open overlay in the app shell.

**Tech Stack:** Java 21, Spring Boot Actuator project, React 19, TypeScript, Vite, CSS.

**Spec:** `docs/superpowers/specs/2026-10-20-render-wake-recovery-design.md`

## Global Constraints

- Do not send periodic keep-alive requests while the app is idle.
- Do not display elapsed seconds, estimated completion time, or fake percentages.
- Do not label an outage as confirmed Render sleep; use neutral backend availability language.
- Do not show recovery UI when the health endpoint responds successfully.
- Retry read-only requests and requests with an `Idempotency-Key` at most once; do not replay uncertain unkeyed mutations.
- Preserve operation-specific timeouts and caller cancellation behavior.
- Never expose Actuator component details through the public endpoint.
- Do not make production Render calls during local implementation.

## Review Focus

- Original API fails but readiness is UP: keep ordinary API error handling and do not show the overlay.
- Client aborts a request due to route change: do not open recovery UI.
- Concurrent API failures: share one health polling loop and one overlay.
- Timeout during a keyed mutation: retry the identical request at most once after recovery.
- Timeout during an unkeyed mutation: do not replay it; preserve the existing uncertain-outcome error.

---

### Task 1: Public backend readiness endpoint

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/api/health/SystemHealthController.java`

**Interfaces:**
- Produces: `GET /api/v1/system/health` with `200 application/json` body `{"data":{"status":"UP"}}` only after the Spring application accepts requests.

- [ ] Add a controller mapped to `/api/v1/system/health` returning only the fixed `UP` status envelope.
- [ ] Confirm the route is covered by the existing `/api/v1/**` CORS policy; do not modify CORS origins or expose Actuator details.
- [ ] Compile backend with `cd backend && ./gradlew compileJava`.

### Task 2: Shared API recovery coordinator

**Files:**
- Create: `frontend/src/apiRecovery.ts`
- Create: `frontend/src/apiFetch.ts`
- Modify: `frontend/src/apiBase.ts`
- Modify: `frontend/src/audioUploadApi.ts`
- Modify: `frontend/src/processingStartApi.ts`
- Modify: `frontend/src/reviewApi.ts`
- Modify: `frontend/src/minutesRegenerationApi.ts`

**Interfaces:**
- `apiFetch(input: RequestInfo | URL, init?: RequestInit, fetchImpl?: typeof fetch): Promise<Response>` performs backend requests and classifies transport/gateway failures.
- `recoverBackendAvailability(): Promise<boolean>` starts or joins readiness recovery for a timeout raised by an existing operation-specific timeout wrapper; it resolves `true` only after the readiness endpoint reports `UP`.
- `subscribeApiRecovery(listener: (state: ApiRecoveryState) => void): () => void` observes the single shared recovery state.
- `ApiRecoveryState` is a discriminated union: `idle`, `recovering`, `ready`. The initial health probe remains invisible; publish `recovering` only after that probe fails.

- [ ] Implement a health probe to `${getApiBaseUrl()}/api/v1/system/health`, with a bounded probe timeout and validation of `data.status === "UP"`.
- [ ] Classify transport failures and 502/503/504 responses by probing health; when health succeeds, preserve the original response/error without recovery UI.
- [ ] When health is unavailable, publish one shared `recovering` state and poll at a fixed short interval without overlapping requests; resolve all waiters after readiness.
- [ ] Retry the original request once only for GET/HEAD or requests carrying `Idempotency-Key`; preserve method, headers, and replayable body. Do not retry caller-aborted requests or unkeyed mutations.
- [ ] Add a helper path for custom timeout wrappers to join recovery only when their own timeout fires; caller cancellation must not start recovery.
- [ ] Add default timeout handling only where the request has no existing operation-specific timeout, preserving all existing long-running timeout values.

### Task 3: App-shell recovery overlay

**Files:**
- Create: `frontend/src/components/BackendRecoveryOverlay.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- `BackendRecoveryOverlay` subscribes to `subscribeApiRecovery` and renders only for `recovering` or `ready` states.
- The overlay is mounted once inside `App`, above the current route content.

- [ ] Render an accessible modal dialog with concise Korean status copy and no timer, percentage, or ETA.
- [ ] Create a calm ice-to-open-door CSS animation: frozen pulse while waiting, gradual crack/light movement while recovering, and a brief open/reveal state after readiness.
- [ ] Add responsive sizing, keyboard focus handling, and `prefers-reduced-motion` styles.
- [ ] After readiness, show the open state briefly, dismiss automatically, and restore focus to the previously focused element.
- [ ] If health remains unavailable, keep the recovery state and expose explicit retry and dismiss actions without trapping the user.

### Task 4: Connect API modules and compile

**Files:**
- Modify: `frontend/src/documentSetupApi.ts`
- Modify: `frontend/src/emailSettingsApi.ts`
- Modify: `frontend/src/meetingDetailApi.ts`
- Modify: `frontend/src/meetingListApi.ts`
- Modify: `frontend/src/newMeetingApi.ts`
- Modify: `frontend/src/participantsApi.ts`
- Modify: `frontend/src/publishApi.ts`
- Modify: `frontend/src/settingsApi.ts`
- Modify: `frontend/src/slackSettingsApi.ts`

**Interfaces:**
- All backend-origin fetches use `apiFetch`; existing API error types and response parsing remain the responsibility of their current modules.

- [ ] Replace direct backend `fetch` calls with `apiFetch`, preserving injected `fetchImpl` parameters and caller-provided abort signals.
- [ ] Confirm unkeyed create/update/delete/publish actions are not replayed after an uncertain result.
- [ ] Run `cd frontend && npm run build` and `cd backend && ./gradlew compileJava`.
- [ ] Review the final diff for accidental production calls, duplicate request replay, and untracked unrelated files.

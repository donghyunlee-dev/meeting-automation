# Vercel Blob Audio Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the AWS S3-compatible audio adapter with private Vercel Blob while restoring the Vercel frontend deployment and preserving Render's current upload, processing, and retention contracts.

**Architecture:** The Vite frontend uploads chunks directly to private Blob using the Vercel client-upload flow. Vercel Functions authorize uploads and provide authenticated metadata/multipart operations; the Render Java API remains authoritative for session validation and processing, reads private objects with server credentials, and delegates metadata operations through the Functions. A per-session Blob JSON manifest uses ETag conditional writes, avoiding another database service.

**Tech Stack:** Java 25, Spring Boot, Node.js 24, Vite, TypeScript, `@vercel/blob`, Vercel Functions, private Vercel Blob, Render Docker web service.

**Spec:** `docs/superpowers/specs/2026-10-15-vercel-blob-audio-storage-design.md`

## Global Constraints

- Audio objects remain private; no browser-visible long-lived storage token or public read URL.
- Existing maximum chunk size is 5 MiB and the browser retains chunks until Render acknowledgement.
- Audio object leases remain renewable for 24 hours; processing and failure-finalization cleanup safeguards remain in force.
- Vercel project root is `frontend`; Node.js is `24.x`; install/build/output are `npm ci`, `npm run build`, and `dist`.
- Render Java API remains the authority for session validation, upload confirmation, processing reads, and expiry decisions.
- Upstash remains a separate service and is not used as audio storage or manifest storage.
- No AWS audio-storage bucket or credential remains required after migration.

## Review Focus

- A client token request for another session, path, MIME type, size, or digest is rejected — test in the Vercel upload grant handler.
- Concurrent manifest updates cannot lose an upload or lease extension — test conditional ETag conflict retry in the manifest service.
- A 5 MiB browser chunk bypasses Function request-body limits — test the direct client-upload flow and verify the Function only handles token exchange.
- A timeout after Blob upload does not discard the IndexedDB chunk or create a false acknowledgement — test upload resume/confirmation behavior in frontend and Java API.
- Cleanup cannot delete an object after its lease is renewed or while processing/failure finalization is active — test Java cleanup regression cases against the new provider.

---

### Task 1: Configure and verify the Vercel frontend project

**Files:**
- Modify: Vercel project settings (already set to `frontend`, Vite, Node 24, `npm ci`, `npm run build`, `dist`)
- Create: `frontend/vercel.json` only if SPA fallback or function routing requires explicit configuration
- Test: `frontend` production build

**Interfaces:**
- Consumes: existing Vercel project `meeting-automation` and Git repository `donghyunlee-dev/meeting-automation`.
- Produces: a production deployment serving the Vite app from `master` and a verified Render API base URL.

- [ ] Check project Root Directory/build settings and verify the expected Vercel Git connection to `master`; do not create a second project.
- [ ] Run `npm ci && npm run build` from `frontend` and confirm output in `frontend/dist`.
- [ ] Configure `VITE_API_BASE_URL` to `https://meeting-automation-4419.onrender.com` for Production and Preview as intended; confirm Render CORS includes the production domain.
- [ ] Trigger a production deployment from the connected `master` source and verify homepage load plus API connectivity.

### Task 2: Implement private Blob manifest and control Functions

**Files:**
- Modify: `frontend/package.json`, `frontend/package-lock.json`
- Create: `frontend/api/audio-upload-token.ts`
- Create: `frontend/api/audio-storage.ts`
- Create: `frontend/src/server/audioBlobManifest.ts`
- Test: `frontend/src/server/audioBlobManifest.test.ts`
- Test: `frontend/api/audio-upload-token.test.ts`

**Interfaces:**
- Consumes: `BLOB_READ_WRITE_TOKEN` and a server-only Render shared secret.
- Produces: authenticated endpoints for upload grants, manifest get/update, object deletion, and multipart upload part control. Session manifests contain object path, type, sequence, MIME type, byte length, SHA-256, and expiry.

- [ ] Add the smallest compatible `@vercel/blob` dependency and tests for manifest create, update, ETag conflict retry, sequence idempotency, conflicting metadata, lease extension, and expiry listing.
- [ ] Implement manifest storage as private per-session JSON blobs; first write is no-overwrite and subsequent writes use `ifMatch` ETags with bounded retry.
- [ ] Implement server-to-server authentication for Render control operations and reject requests lacking the shared secret.
- [ ] Implement short-lived, path-bound client upload grants that limit content type and size and never expose the read/write token.
- [ ] Implement multipart control endpoints so Render can upload assembled audio in sub-4.5 MiB parts without sending a large body through one Function request.
- [ ] Run the focused TypeScript tests and Vite production build.

### Task 3: Replace the Java S3 adapter with a Vercel Blob adapter

**Files:**
- Modify: `backend/src/main/java/com/meetingautomation/api/audio/PrivateAudioStorageConfiguration.java`
- Modify: `backend/src/main/java/com/meetingautomation/api/audio/S3PrivateAudioStorageAdapter.java` or replace with `VercelBlobPrivateAudioStorageAdapter.java`
- Create: `backend/src/main/java/com/meetingautomation/api/audio/VercelBlobControlClient.java`
- Test: storage adapter and configuration tests under `backend/src/test/java/com/meetingautomation/api/audio/`

**Interfaces:**
- Consumes: `PrivateAudioStoragePort`, Vercel Function base URL, server-only Blob token, Render shared secret.
- Produces: the same `PrivateAudioStoragePort` operations backed by private Blob and durable manifests.

- [ ] Write failing adapter tests for store/open/find/list/listExpired/extendExpiry/delete, idempotent chunk store, and Blob/Function error mapping.
- [ ] Implement authenticated manifest/control calls and direct authenticated private Blob reads using streaming Java HTTP clients.
- [ ] Implement large assembled-object writes by streaming parts through the multipart Function API; do not buffer the full object in memory.
- [ ] Replace S3-specific configuration and dependencies required only by this adapter; remove AWS credential/bucket requirements from Docker/runtime configuration.
- [ ] Run focused backend tests, then `./gradlew test`.

### Task 4: Migrate browser chunk upload to direct private Blob upload

**Files:**
- Modify: `frontend/src/audioUploadApi.ts`
- Modify: `frontend/src/chunkUploadCoordinator.ts`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/src/audioUploadApi.test.ts`
- Test: `frontend/src/chunkUploadCoordinator.test.ts`

**Interfaces:**
- Consumes: the existing `StoredChunk` IndexedDB record and Vercel grant/upload endpoints.
- Produces: the existing Render confirmation and status contracts; IndexedDB chunks are removed only after a successful Render acknowledgement.

- [ ] Add failing tests for grant request, direct Blob upload, confirmation, uncertain timeout recovery, and retention of local chunks on failure.
- [ ] Replace binary PUT-to-Render with client upload to Blob followed by Render confirmation carrying the opaque object identity and expected metadata.
- [ ] Preserve status-based resume, idempotency, timeout handling, and the 5 MiB maximum.
- [ ] Run focused frontend tests and production build.

### Task 5: Configure services and verify end-to-end behavior

**Files:**
- Modify: `docs/setup/frontend-setup.md` only if its setup steps become incorrect
- Modify: Render and Vercel environment configuration

**Interfaces:**
- Consumes: Vercel private Blob store linked to the Vercel project, `BLOB_READ_WRITE_TOKEN`, Render API URL, and Vercel Function URL.
- Produces: a deployed frontend and Render backend that exchange private audio through Vercel Blob.

- [ ] Create/link a private Blob store in the Vercel project and set the token and shared secret in Vercel Functions and Render without replacing unrelated environment variables.
- [ ] Configure Render with the Vercel Function URL and private Blob token; remove AWS audio bucket/credential variables after the new adapter is verified.
- [ ] Deploy Render from `master` and confirm its health endpoint responds.
- [ ] Deploy the Vercel frontend from `master`; smoke-test the production page and API connectivity.
- [ ] Run a small authenticated upload, status resume, processing read, lease extension, and expiry cleanup against a non-production/test session.
- [ ] Verify anonymous Blob reads fail and built frontend assets contain no Blob token or server shared secret.

# Vercel Blob Audio Storage and Frontend Deployment Design

> **취소됨 / 이전 구현 기록.** 2026-10-16에 사용자가 Vercel Blob 및 오디오 Functions 계획을 취소하고 Backend 직접 수신 방향을 승인했다. 이 문서는 과거 설계를 추적하기 위한 기록이며 현재 기준이 아니다. 현재 설계는 [Backend 소유 Audio 설계](./2026-10-16-backend-owned-audio-design.md), 운영 검토는 [운영 테스트 설정 기준](../../setup/production-test.md)을 따른다.

## Intent and constraints

The frontend is hosted on Vercel and the implementation in `origin/master` uses Vercel Blob instead of the former S3-compatible AWS adapter. Audio must remain private. The Java API remains on Render and continues to own upload validation, session state, processing, retry, and expiry decisions. This change introduces no database or external queue.

The current browser upload contract allows chunks up to 5 MiB. The frontend keeps failed or unacknowledged chunks in IndexedDB and resumes by asking the API which sequences it has received. Audio objects receive a renewable 24-hour lease. Processing and failure-finalization flows can extend their availability, and a scheduled cleanup deletes expired objects.

## Deployment boundary

The repository is a monorepo with the Vite frontend under `frontend/`. The Vercel project uses that directory as its root, Node.js 24, `npm ci`, `npm run build`, and `dist`. `VITE_API_BASE_URL` points to the Render API; Render CORS allows the frontend origin. Current deployment observations belong in the dated [deployment audit](../../evidence/2026-10-16-deployment-audit.md), and operational settings belong in the [production test guide](../../setup/production-test.md).

## Recommended architecture

Use a private Vercel Blob store. The browser uploads audio bytes directly to Blob through the Vercel Blob client-upload flow. A Vercel Function handles only the authenticated upload-token exchange and storage control operations; it does not proxy audio bytes. This avoids the Vercel Function request-body limit, which is below the existing 5 MiB maximum chunk size.

The Java API remains the authority for the meeting session and accepted upload metadata. The flow is:

1. The browser requests a short-lived upload grant from the Vercel Function. The request includes the session, chunk sequence, declared MIME type, byte length, SHA-256, and idempotency key.
2. The Vercel Function authenticates the caller through a backend-issued, short-lived grant and permits only the requested private object path and bounded size/type. The grant must not allow arbitrary paths, public access, or unrelated Blob operations.
3. The browser uploads the bytes directly to private Blob and retains the IndexedDB chunk until the backend confirms receipt.
4. The browser asks the Render API to confirm the Blob object. Render retrieves it privately, verifies session authorization, exact byte length, MIME type, and SHA-256, then records the chunk as received and returns the existing acknowledgement contract. An object uploaded but not confirmed is an orphan and is removed by expiry cleanup.
5. Render reads private Blob objects for assembly and processing. The browser never receives a read URL or the Blob storage token.

The server-to-server trust mechanism must be explicit and short lived. The Vercel Function must validate a backend-signed grant or perform an authenticated grant exchange with Render; a user-controlled session ID alone is not authorization. The implementation plan must choose the mechanism that fits the existing session access model and avoid putting long-lived storage credentials in browser code.

## Storage metadata and lifecycle

Vercel Blob is not an S3-compatible Java SDK, so the former Java adapter could not be redirected by changing an endpoint. A Vercel storage control boundary is implemented by the Vercel Function, while `PrivateAudioStoragePort` remains the Java application's provider-neutral boundary.

Store one private JSON manifest per session in Vercel Blob. It contains each object's opaque Blob pathname, type (`CHUNK` or `ASSEMBLED`), sequence, MIME type, byte length, SHA-256, and expiry timestamp. The manifest is authoritative for listing objects by session, finding objects, extending leases, and locating expired objects. Manifest writes use Blob conditional writes with ETags (`ifMatch`) and retry on precondition conflicts; first creation uses no-overwrite semantics. This avoids adding a database service while keeping the index durable across Render restarts.

Manifest updates must be safe under retries and concurrent session operations. Upload confirmation is idempotent by session and sequence. A retry with matching size, MIME type, and digest returns the existing acknowledgement; a conflicting object is rejected. Lease extension and deletion must recheck the latest manifest revision so a stale cleanup listing cannot delete an object whose lease was renewed. Cleanup paginates all session manifests and retains the existing safeguards: do not delete while processing is active, finalize expired failures before deletion, and retry metadata or deletion failures on a later sweep.

Private Blob reads by Render go through the authenticated Vercel storage Function. `BLOB_READ_WRITE_TOKEN` is used by Vercel Functions; `RENDER_AUDIO_STORAGE_SECRET` authenticates Render's control and read requests, and `RENDER_AUDIO_GRANT_SECRET` verifies browser upload grants. The two shared secrets have matching values in Render and Vercel. None is exposed with a `VITE_` prefix. The exact values and Blob store linkage are deployment configuration tasks.

## Components and responsibility

- **Frontend (`frontend/`)**: obtains an upload grant, uploads bytes directly to Blob, confirms each upload with Render, and removes the IndexedDB chunk only after Render acknowledges it.
- **Vercel Function (`frontend/api/` or equivalent Vercel project-root API directory)**: authenticates grant exchange and exposes narrow manifest/object control operations, including private reads for Render. It never returns Blob read URLs to the browser and never proxies browser audio uploads.
- **Render Java API (`backend/`)**: owns session authorization, validation, confirmation, processing reads, lease policy, and cleanup scheduling. It adapts the existing storage port to private Vercel Blob and its manifest control API.
- **Private Vercel Blob**: stores chunk and assembled audio bytes. No object is publicly readable.
- **Manifest storage**: private per-session JSON manifests in Vercel Blob, updated with conditional ETag writes. They survive Render restarts and support lookup by session and object ID; the cleanup sweep paginates manifests to find expired objects. No in-memory-only metadata or additional database service is introduced.

## Error handling and security

- A failed Blob upload or failed confirmation leaves the local IndexedDB chunk available for retry.
- If the Blob upload succeeds but confirmation times out, the browser queries Render's received status before uploading again. Idempotency prevents duplicate objects or conflicting acknowledgements.
- If confirmation cannot verify a private object, return the existing API error envelope and do not acknowledge the chunk.
- Expired or invalid grants are rejected and can be requested again. Grants are bound to a session, sequence, expected object key, size limit, MIME type, and digest, and expire quickly.
- The Vercel Function and Render API authenticate each other for manifest and object control calls. CORS is restricted to configured frontend origins; CORS is not treated as authentication.
- Logs contain opaque IDs and outcomes, not audio bytes, storage tokens, or signed read credentials.
- Public access, browser-visible long-lived tokens, arbitrary object paths, and unbounded upload grants are prohibited.

## Frontend deployment configuration

For the existing Vercel project, verify or configure:

- Git repository `donghyunlee-dev/meeting-automation` and production branch `master` are connected.
- Root Directory is `frontend`.
- Node.js version is 24.x; install command is `npm ci`; build command is `npm run build`; output directory is `dist`.
- `VITE_API_BASE_URL` points to the deployed Render API URL for Production and Preview environments as appropriate.
- Render CORS allows the production Vercel domain and any intended preview domains.
- The private Blob store and required server-only credentials are linked to the correct Vercel project and environment.

The deployment should be checked with a production build and a browser smoke test covering the homepage, API health/connectivity, and a small authenticated audio upload through confirmation. A successful static page load alone does not verify the backend or audio flow.

## Scope and non-goals

This change replaces only the private audio object provider and its required metadata/control integration. It does not move the Java backend to Vercel, move session/job state, or introduce a new recording product flow.

## Acceptance criteria

1. Frontend production build succeeds on Node.js 24 and deploys from the `master` branch with `frontend` as root.
2. The production frontend calls the configured Render API, and the browser can reach the API without localhost URLs or CORS errors.
3. A chunk at the existing 5 MiB limit can be uploaded directly to private Blob without passing through a Vercel Function body.
4. Render confirms only objects whose session, sequence, MIME type, byte length, and SHA-256 match the upload grant and current session state.
5. Resume/status, idempotent retries, assembly, processing reads, lease renewal, failure finalization, and expiry cleanup continue to work across Render restarts.
6. Private audio cannot be fetched anonymously, and no long-lived Blob credential appears in built frontend assets or browser requests.
7. The project has no AWS audio-storage credentials or required AWS bucket configuration after migration.

## Verification approach

Run the frontend unit tests and production build, backend unit/integration tests for storage and upload confirmation, and focused tests for grant validation, manifest concurrency/idempotency, private reads, and expiry cleanup. Use a non-production Vercel Blob store for an end-to-end test. Inspect built frontend assets to ensure server-only tokens are absent. Verify the deployed versions of both services separately.

## Implementation configuration

Create a private Blob store linked to the Vercel project so Vercel supplies `BLOB_READ_WRITE_TOKEN` to its Functions. Configure `RENDER_AUDIO_STORAGE_SECRET` and `RENDER_AUDIO_GRANT_SECRET` as matching secrets in Render and Vercel. Render uses the authenticated Vercel Function for private reads and control operations. These secrets must never enter source control or browser bundles.

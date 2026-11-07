# 이메일 OTP 진입 인증 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이메일로 받은 6자리 OTP를 60초 이내 검증한 경우에 메인 화면 진입 세션을 발급한다. 브라우저 활동이 2시간 멈추면 로그인 화면으로 돌아간다.

**Architecture:** OTP challenge, 이메일별 실패 잠금/발송 쿨다운, 기기별 sliding-idle 세션을 단일 Spring Boot 프로세스 메모리에 저장한다. OTP를 통과할 때마다 서버가 UUID sessionId와 별도의 비밀 세션 쿠키를 발급한다. 브라우저가 앱 진입과 유휴 만료를 관리하고 인증 전용 상태/활동 endpoint와 동기화한다. Backend의 회의·녹음 등 기존 업무 API에는 인증 검사를 넣지 않는다.

**Tech Stack:** React 19, TypeScript, React Router, Vite, Java 25, Spring Boot 4.1, Jakarta Mail/Gmail API OAuth2, JUnit 5, Vitest.

**Spec:** [이메일 OTP 진입 인증 설계](../specs/2026-11-07-email-otp-entry-design.md)

## Global Constraints

- SQL DB와 Redis는 추가하지 않는다.
- OTP는 6자리 숫자이고 유효시간은 발송 성공 시점부터 60초다.
- 이메일별 검증 실패 5회 시 발급과 검증을 5분 차단한다.
- 발송 재요청 간격은 60초이고, 재발송은 실패 횟수를 초기화하지 않는다.
- 마지막 확인된 사용자 활동으로부터 2시간 유휴 시 인증을 만료한다. 인증 세션의 고정 절대 만료는 두지 않는다.
- 인증 쿠키는 HttpOnly이며 브라우저가 전용 활동 endpoint를 호출할 때마다 Max-Age 7200초로 갱신한다. 자격 증명은 브라우저 저장소에 두지 않는다.
- 한 이메일의 여러 기기/로그인 세션을 동시에 허용하고, 세션 UUID는 식별자로만 사용한다. 새 로그인은 기존 세션을 폐기하지 않으며 로그아웃은 현재 세션만 폐기한다.
- `OTP_HMAC_SECRET`은 OTP 코드 verifier 생성에 쓰는 서버 비밀이며 `AUTH_COOKIE_SECURE=true`를 HTTPS 운영에서 명시한다.
- `AUTH_COOKIE_PATH`는 auth endpoint만 포함한다: 개발 `/api/v1/auth`, 운영 `/api/backend/api/v1/auth`.
- 단일 Backend 인스턴스를 지원 조건으로 하며 재시작 후 모든 인증/잠금 상태가 초기화된다.
- 유효 이메일 소유자는 누구나 진입 가능하다는 임시 정책을 둔다. 조직 제한이 필요하면 구현 전 정책 변경이 필요하다.
- OTP 발송은 Frontend 설정 복구에 의존하지 않고 기존 `EMAIL_*` Backend 환경 설정을 사용한다.
- 실제 client IP가 trusted proxy 경로로 검증되기 전에는 Backend에서 forwarded header 기반 IP rate limit을 적용하지 않는다.

## Review Focus

- 만료 경계(정확히 60초)와 이미 사용한 코드 재사용: 서버는 만료/재사용을 거부한다. Task 1의 TTL 및 원자 소비 테스트로 검증한다.
- 다섯 번째 오답과 잠금 만료 경계(정확히 5분): 5회째부터 막고 5분 경과 후 재개한다. Task 1의 실패 제한 테스트로 검증한다.
- 코드 재발송으로 실패 횟수를 초기화하려는 입력: 누적 실패를 유지한다. Task 1의 교체/잠금 테스트로 검증한다.
- 세션 만료 경계와 확인 endpoint 조회만으로 idle 시간이 연장되는 문제: 정확히 2시간에 만료하고 상태 조회는 시간을 연장하지 않는다. Task 3의 session API 테스트로 검증한다.
- 화면 이동/사용자 액션/녹음/처리 중 유휴 오판: 브라우저 활동은 만료 시각을 갱신한다. Task 4의 idle timer 테스트로 검증한다.
- Backend 업무 API의 인증 검사 회귀: 인증 cookie가 없어도 기존 회의/녹음 API 동작 계약은 바뀌지 않는다. Task 5에서 직접 호출로 검증한다.
- 같은 이메일의 동시 기기 로그인과 한 기기 로그아웃: 세션 UUID는 각각 달라야 하고 다른 기기의 세션은 계속 유효하다. Task 3의 session store 테스트로 검증한다.
- 대량 이메일 발송과 이메일 존재 여부 추측: 이메일별 쿨다운, trusted-proxy 확인 시 IP 제한, 계정 존재 여부와 무관한 응답을 사용한다. Task 2의 제한/응답 테스트로 검증한다.

---

### Task 1: OTP 상태와 검증 도메인

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/auth/OtpChallengeStore.java`
- Create: `backend/src/main/java/com/meetingautomation/auth/InMemoryOtpChallengeStore.java`
- Create: `backend/src/main/java/com/meetingautomation/auth/OtpAuthenticationService.java`
- Create: `backend/src/main/java/com/meetingautomation/auth/OtpCodeHasher.java`
- Test: `backend/src/test/java/com/meetingautomation/auth/OtpAuthenticationServiceTests.java`
- Test: `backend/src/test/java/com/meetingautomation/auth/InMemoryOtpChallengeStoreTests.java`

**Interfaces:**
- Consumes: injected `Clock`, injected OTP HMAC secret, injected `SecureRandom`/code generator.
- Produces: `OtpAuthenticationService.prepareIssue(email) -> PendingOtp`; `activate(PendingOtp) -> Instant expiresAt`; `cancel(PendingOtp) -> void`; `verify(email, code) -> VerificationResult`; store operations atomically reserve email send limits, activate/replace, fail, consume, and check lockout by normalized email.

- [x] **Step 1: Write failing service/store tests** for expiry at the exact 60-second boundary, valid one-time verification, wrong-code counting, issue/verify rejection after the fifth failure, unlock at the exact 5-minute boundary, resend retaining failures, and email normalization.
- [x] **Step 2: Run focused tests and confirm they fail** with missing auth types/behavior.
- [x] **Step 3: Implement the in-memory store and service** with per-email atomic operations, `Clock`, `SecureRandom`, and HMAC-SHA-256 verifier compared in constant time. A prepared challenge is unusable until mail delivery succeeds; set its 60-second expiry at activation. Delete/replace expired challenge state on access; never store the raw code.
- [x] **Step 4: Run focused backend tests** with `cd backend && ./gradlew test --tests 'com.meetingautomation.auth.*Tests'`; expect all focused auth tests to pass.

### Task 2: OTP email and public authentication API

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/auth/OtpEmailSender.java`
- Create: `backend/src/main/java/com/meetingautomation/api/auth/AuthController.java`
- Create: `backend/src/main/java/com/meetingautomation/api/auth/OtpRequest.java`
- Create: `backend/src/main/java/com/meetingautomation/api/auth/OtpVerificationRequest.java`
- Modify: `backend/src/main/java/com/meetingautomation/email/gmail/GmailEmailAdapter.java` (only if needed to expose generic transactional mail send)
- Modify: `backend/src/main/java/com/meetingautomation/email/EmailProviderConfiguration.java`
- Modify: `backend/.env.example`
- Test: `backend/src/test/java/com/meetingautomation/api/auth/AuthControllerTests.java`

**Interfaces:**
- Consumes: Task 1 `OtpAuthenticationService`; existing server Gmail OAuth settings and Gmail API adapter.
- Produces: `POST /api/v1/auth/otp-requests` and `POST /api/v1/auth/otp-verifications`. Request endpoint sends mail then activates challenge only after delivery succeeds. Verification returns the verified email identity to Task 3; the controller must not expose the session token in JSON.

- [x] **Step 1: Write failing API/email tests** for valid email request, invalid address, provider failure (no active challenge), 60-second per-email resend limit, HTML plus plain-text MIME parts, escaped OTP interpolation, indistinguishable responses for addresses with no account registry, valid and invalid code responses, and no OTP/email/provider body in logs or errors.
- [x] **Step 2: Run focused API tests and confirm they fail** before endpoint/sender implementation.
- [x] **Step 3: Implement the sender and controller** using a dedicated Korean HTML OTP email with a plain-text alternative, safely escaped six-digit code, and existing `EMAIL_*` server environment credentials, independent of Frontend-restored email settings. Enforce the 60-second per-email cooldown. Do not infer client IP from untrusted forwarded headers; add a gateway/IP limit only after the Vercel-to-GCE trusted-proxy source is independently verified. Return `expiresInSeconds`/`retryAfterSeconds` only where safe.
- [x] **Step 4: Run focused backend tests** with `cd backend && ./gradlew test --tests 'com.meetingautomation.api.auth.*Tests'`; expect delivery success/failure and throttling contracts to pass.

### Task 3: Server session idle state and auth-only heartbeat

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/auth/AuthenticatedSessionStore.java`
- Create: `backend/src/main/java/com/meetingautomation/auth/InMemoryAuthenticatedSessionStore.java`
- Modify: `backend/src/main/java/com/meetingautomation/api/auth/AuthController.java`
- Test: `backend/src/test/java/com/meetingautomation/auth/AuthenticatedSessionStoreTests.java`
- Test: `backend/src/test/java/com/meetingautomation/api/auth/AuthSessionActivityApiTests.java`

**Interfaces:**
- Consumes: Task 2 successful verification identity and random session token.
- Produces: `AuthenticatedSessionStore.create(email, now) -> SessionCredentials(sessionId: UUID, rawCookieToken: String)`; `findByCookieToken(rawCookieToken, now) -> Optional<AuthenticatedSession>`; `recordActivity(rawCookieToken, now) -> ActivityResult`; `revokeByCookieToken(rawCookieToken) -> boolean`. Each successful OTP creates a distinct UUID `sessionId`; records are indexed per session/token, never per email. `GET /api/v1/auth/session` reports current session state and `remainingIdleSeconds` without updating activity. `POST /api/v1/auth/session/activity` updates `lastActivityAt` and cookie Max-Age only for a non-expired session; `POST /api/v1/auth/logout` revokes only the current session. Session expires exactly 7200 seconds after `lastActivityAt`. Cookie attributes are `HttpOnly`, `Secure` from `AUTH_COOKIE_SECURE`, `SameSite=Strict`, `Path` from `AUTH_COOKIE_PATH` (auth routes only). No filter/interceptor checks any business API.

- [x] **Step 1: Write failing tests** for distinct UUIDs on two successful logins for the same email, both sessions valid concurrently, logout revoking only current session, UUID differing from bearer cookie token, exact 2-hour idle expiry, activity refreshing only the current session, GET status not refreshing activity, old session rejection after store restart, and expired sessions not being revived by late heartbeats.
- [x] **Step 2: Run focused tests and confirm the idle-session contract fails.**
- [x] **Step 3: Implement one UUID session record per successful login** with `lastActivityAt`; keep records indexed by session/token, never email. Store a SHA-256 verifier of a separate 256-bit random bearer token. Expose status and activity methods only through `/api/v1/auth/**`; heartbeat updates only the matching session. Do not add a filter, interceptor, or auth check to business API routes.
- [x] **Step 4: Configure `OTP_HMAC_SECRET`, `AUTH_COOKIE_SECURE`, and environment-specific `AUTH_COOKIE_PATH`; set session cookie Max-Age to 7200 and refresh it only when the activity endpoint accepts a heartbeat. Verify the production cookie path includes `/api/backend/api/v1/auth` and excludes business API paths.**
- [x] **Step 5: Run focused auth session tests** with `cd backend && ./gradlew test --tests 'com.meetingautomation.auth.*Tests' --tests 'com.meetingautomation.api.auth.*Tests'`; expect idle boundaries, parallel sessions, logout isolation, and status/heartbeat behavior to pass.

### Task 4: Frontend email/code entry and route guard

**Files:**
- Create: `frontend/src/authApi.ts`
- Create: `frontend/src/authApi.test.ts`
- Create: `frontend/src/EmailOtpEntry.tsx`
- Create: `frontend/src/EmailOtpEntry.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Modify: `frontend/src/apiFetch.ts` (only if cookie credentials or 401 handling needs central support)
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: Task 3 auth endpoints and `ma_session` HttpOnly cookie; browser requests use `credentials: 'include'`.
- Produces: unauthenticated visits render email/OTP entry; successful verification/session check returns to the requested internal route; logout returns to entry screen.

- [x] **Step 1: Write failing UI/API tests** for email validation, six numeric digits, paste/mobile numeric input, OTP countdown, wrong-code/lock errors, initial unauthenticated root and deep link, post-login return path, refresh session restoration, exact 2-hour idle logout, resetting idle on pointer/keyboard/form actions and route navigation, active recording/processing heartbeats, background-tab return after expiry, and logout.
- [x] **Step 2: Run focused frontend tests and confirm they fail** before UI and routing behavior exists.
- [x] **Step 3: Implement `authApi.ts`** with typed request/verify/session/logout calls using credentialed fetch and safe API error mapping.
- [x] **Step 4: Implement and style `EmailOtpEntry`** to match existing responsive shell; expose expiry, resend, and lock countdown accessibly without revealing server internals.
- [x] **Step 5: Add an app-level auth gate and sliding idle controller.** Start from the server-reported `remainingIdleSeconds`; reset the browser timer on real interaction and route changes; coalesce real user activity into an auth-only heartbeat after the interaction settles. While recording or an explicitly active processing workflow is underway, send a heartbeat every 60 seconds even without pointer/keyboard input. Do not send periodic heartbeats for a passive idle page. On local timeout, expired server response, or tab focus after 2 hours idle, hide app routes, attempt current-session logout, and return to the email screen. Mount existing settings restoration and app initialization only after authentication.
- [x] **Step 6: Run focused frontend tests** with `cd frontend && npm test -- src/authApi.test.ts src/EmailOtpEntry.test.tsx src/App.test.tsx`; expect route gating, idle reset, expiry, recording, and background-tab cases to pass.

### Task 5: Regression and acceptance verification

**Files:**
- Modify: `backend/src/test/java/com/meetingautomation/MeetingAutomationApplicationTests.java` only if application startup wiring needs integrated proof.
- Modify: `frontend/src/App.test.tsx`, `frontend/src/AppMeetingHistory.test.tsx`, `frontend/src/DocumentSetupWizard.test.tsx`, `frontend/src/DocumentSwitch.test.tsx`, and `frontend/src/SettingsPage.test.tsx` for authenticated app fixtures.

- [x] **Step 1: Run all backend tests** with `cd backend && ./gradlew test`; the full backend suite passes (747 tests, 1 skipped).
- [ ] **Step 2: Run frontend test suite and build** with `cd frontend && npm test && npm run build`; production build and focused auth tests pass. The full frontend suite remains red; compare final count against its baseline of 16 failures and 13 async errors.
- [ ] **Step 3: Verify the browser flow against the deployed production app**: request OTP, reject expired/wrong/replayed codes, accept a valid code, verify cookie-based reload, validate 2-hour idle expiry and active recording keepalive, and confirm existing home/meeting routes work after authentication. Also call a representative meeting/recording business API without an auth cookie and confirm the request is not rejected by a new auth check. Capture the entry screen at desktop and mobile widths and check keyboard/paste and countdown behavior.
- [ ] **Step 4: Record deployment prerequisites**: Gmail API send configuration and sender verification, HMAC secret, Secure cookie flag/path, HTTPS behavior, and single Backend instance. Deploy to `master` after local verification, then confirm the Vercel and GCE workflows before running the real browser/email acceptance flow.

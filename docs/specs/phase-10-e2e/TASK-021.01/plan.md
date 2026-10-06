# Mock 기반 핵심 E2E 구현 계획

## 의존성과 실행 순서

- PRD cursor task: TASK-021.01; PRD broad predecessor range TASK-001.05~TASK-020.04
- Recording interface: TASK-005.02/#20, 005.03/#21, 005.04/#22, 005.06/#24, 005.08/#26
- Processing interface: TASK-006.04/#30, 006.05/#31, 006.06/#32
- Review/template interface: TASK-008.01/#37, 008.02/#38, 008.03/#39, 009.02/#41
- Mobile screen handoff: TASK-020.01~.04, final QA Issue #76

Frontend/Backend 기능 소유 task들이 완료되어 browser routes, endpoints, provider ports, Gradle/Vite launch configuration이 존재해야 한다. 이 범위가 실행 가능해지기 전에 suite를 완료로 주장하지 않는다. 먼저 결정적 fake provider/backend profile을 만드는 BE test seam, 그 다음 두 서버 통합 runner, 마지막 실제 route의 성공/실패 시나리오를 UI를 통해 작성한다.

## 실행 구조

| 위치 | 변경 책임 |
|---|---|
| `frontend/playwright.e2e.config.ts` | Playwright Chromium project, `baseURL`, worker=1 deterministic setup, report/artifact policy, FE+BE `webServer` array |
| `frontend/tests/e2e/core-meeting.spec.ts` | Meeting-to-Review success, mapping/edit/regenerate invariant, processing failure scenarios |
| `frontend/tests/e2e/fixtures/` | synthetic UI participant/template/content; controlled MediaRecorder/MediaStream/browser APIs |
| `frontend/package.json` | `npm run test:e2e`, stable Playwright dependency/lockfile |
| `backend/src/test/` | `e2e` Spring profile test adapters, deterministic AudioStorage/Transcription/Diarization/Minutes ports, fixture reset/strict call behavior |
| `backend/src/main/resources/application-e2e.yml` or equivalent test-only config | port/origin/storage/provider behavior; no real credential, endpoint or production settings |
| `docs/evidence/TASK-021.01.md` | run command, scenario, sanitized assertions/counts, build/browser/commit |

현재 backend에는 `.gitkeep`만 있고 frontend는 Vite/React scaffold 단계다. 실제 업무 route/backend modules 구현과 toolchain prerequisites를 완료한 뒤 위 경로를 기존 repo convention에 맞춰 생성한다. 여기서 endpoint, business API, database, provider architecture를 새로 바꾸지 않는다.

## Playwright server/route 구성

- Playwright webServer array로 Backend와 Frontend를 동시에 시작한다.
- Backend entry는 repository Gradle Wrapper `bootRun`이며 test profile을 환경변수 `SPRING_PROFILES_ACTIVE=e2e`로 켜고 `SERVER_PORT=8080`으로 고정한다. Ready URL은 `http://127.0.0.1:8080/actuator/health`다.
- Frontend entry는 `npm run dev -- --host 127.0.0.1 --port 4173 --strictPort`, `VITE_API_BASE_URL=http://127.0.0.1:8080`; page `baseURL`은 `http://127.0.0.1:4173`이다.
- Browser test origin만 Backend e2e profile CORS에 허용한다. production CORS/security config는 수정하지 않는다.
- 고정 local port가 이미 점유 중이면 runner는 실패해 기존 process에 붙지 않는다. `reuseExistingServer=false`로 stale/production-like server 오연결을 막는다. CI는 필요한 Playwright browser 설치 단계 후 같은 test command를 실행한다.
- Chromium 1 project, workers 1로 시작해 in-memory Backend 공유/fixture 경합을 막는다. Parallelism은 isolated server/context 구현과 검증이 후속으로 들어오기 전 켜지 않는다.
- Windows에서는 config가 `gradlew.bat`를, POSIX에서는 `./gradlew`를 선택한다. 실행 경로는 `process.cwd()`가 아니라 config 위치로 고정한다.

Playwright는 config에서 다수 `webServer`를 띄우는 구성을 공식 지원한다. ([Web server docs](https://playwright.dev/docs/test-webserver#multiple-web-servers))

## Backend mock profile 설계

- `e2e` profile 전용 adapter: deterministic assembled Audio storage, STT response, normalized diarization response, Template에 반응하는 StructuredMinutes response.
- E2E profile은 외부 network provider credentials가 필요 없고 real SDK/client bean을 주입하지 않게 한다. Test profile bean guard는 production profile에서 활성화되지 않아야 한다.
- 정상 happy-path Session의 STT/diarization은 한 번만 호출 가능하도록 strict output guard를 둔다. API-013 재생성 중 다시 호출하면 다른 sentinel/error를 반환해 transcript/speaker invariant 테스트가 실패한다.
- retry Session은 첫 STT 호출만 retryable fixture로 실패하고 다음 명시 API-020 호출은 안정적인 성공 결과를 반환한다. Audio assembly adapter는 한 Session에서 재호출되면 테스트 실패로 표시한다. 이는 재시도가 완료된 stage를 건너뛰는지 확인한다.
- Minutes fake는 initial processing 및 한 번의 Template regeneration을 지원하고 input transcript/mapping/template를 검사한 deterministic minutes를 만든다. Failure scenario에선 선택된 STT adapter만 정해진 safe error로 실패하고 downstream calls는 금지한다.
- Test configuration은 Audio storage/local data를 격리하고 cleanup endpoint를 추가하지 않는다. 기존 Spring test/context lifecycle 및 test-only fake를 통해 상태를 reset한다.
- Browser-recording fake는 permission stream stub, 명시 MIME, 확정 byte chunks 및 deterministic `dataavailable`/`stop` 순서를 준다. API-007 client는 실제 HTTP binary body와 hash/length headers를 보내고 test에서는 실제 Blob을 저장하지 않는다.

## 시나리오 구현 순서

1. Gradle Backend `e2e` profile smoke: `/actuator/health` up, external adapter call disabled, API-006 fixture create and teardown.
2. Backend pipeline contract tests: fake port sequence/audio aggregate, API-010 REVIEW snapshot, stage failure gate.
3. Playwright test runner가 `test:e2e`에서 backend/frontend를 함께 띄우고 readiness, teardown, artifacts privacy를 검증.
4. Browser happy path: API-006 creation → API-007 deterministic chunks/ACK → API-008 complete list → API-009 exactly once → API-010 REVIEW poll → API-011/012/013 user flows.
5. API-013 assertions: confirmed user action, If-Match/idempotency semantics per contract, new Template minutes; transcript and mapped speakers unchanged; STT/diarization strict fake remains uncalled again.
6. Browser retry path: STT fake one-shot failure → API-010 failed stage/audio expiry/action state → user click API-020 exactly once → already-completed assembly skipped → STT onward succeeds to REVIEW; failure path never publishes attendee delivery.
7. Repeat suite on clean app context; run frontend/backend build and existing unit/regression commands; record `docs/evidence/TASK-021.01.md`.

## CI 및 증거 경계

- Existing FE commands `npm run test`, `npm run lint`, `npm run build`; BE `./gradlew clean build`. E2E command added by this task: `npm run test:e2e` in frontend.
- Playwright output path isolated under existing test report/ignored artifact convention. Commit only sanitized human-authored Evidence, not trace/browser context recording that may include Request/response bodies.
- Do not record Audio bytes, actual transcript, Secret, token, private object URL, participant PII, raw provider body or email delivery. Synthetic screen string may be represented by fixture ID/count, not copied into release Evidence unless safe test constant.
- E2E profile must fail closed if production provider credential/base URL is set or app is configured for non-test mode; never use a live account to make tests pass.

## 관련 기준

- [PRD API-006~014](../../../product/PRD.md)
- [TASK-005.08 end handoff](../../phase-03-recording/TASK-005.08/spec.md)
- [TASK-006.04 processing orchestration](../../phase-04-processing/TASK-006.04/spec.md)
- [TASK-006.05 Minutes pipeline](../../phase-04-processing/TASK-006.05/spec.md)
- [TASK-008.03 Minutes save](../../phase-05-review/TASK-008.03/spec.md)
- [TASK-009.02 Template regeneration](../../phase-05-review/TASK-009.02/spec.md)

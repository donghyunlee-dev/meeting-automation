# 📄 문서 연결 설계 게시와 개발 재개 검증

> 2026-10-10 KST · 범위: DOCUMENTATION_ONLY · PRD v1.9.0
> TASK-022.01의 제품 구현 완료 기록이 아니다. TASK-022.01~06 개발 상태는 TODO다. 향후 구현 검증은 별도 기록하며 이 설계 게시 기록을 보존한다.

## 🔗 게시 증거

- 설계 PR: [#102](https://github.com/donghyunlee-dev/meeting-automation/pull/102)
- 검증·리뷰·QA 대상 head: `2c7e9ab5bb4ceb469f2738e4a3113c58e2822db7`
- master 병합 commit: `5dab4d086adc6b386051ddc983add9ac5c270474`
- 문서 리뷰: [https://github.com/donghyunlee-dev/meeting-automation/pull/102#pullrequestreview-5477079538](https://github.com/donghyunlee-dev/meeting-automation/pull/102#pullrequestreview-5477079538) · 순환 QA 의존성 해결, 추가 blocking 없음
- 독립 구조 QA: [https://github.com/donghyunlee-dev/meeting-automation/pull/102#issuecomment-6092665008](https://github.com/donghyunlee-dev/meeting-automation/pull/102#issuecomment-6092665008) · 문서 범위 PASS

## ✅ 리더 검증

`node .resume-validate.mjs`(임시 문서 검증기), `git diff origin/master --check`를 해당 head에서 수행했다. 문서 88개 링크/anchor, 신규 네 문서 패키지 6개/파일 24개, 수용 기준 38개와 tasks/test 매핑, 기존 DONE 기록 16개 보존을 확인했다. 최신 PRD의 cursor/우선순위와 GitHub 선행 관계를 checkpoint보다 먼저 적용하는 재개 계약을 확인했다. GitHub Wrapper CI 두 건은 SUCCESS였다.

## 🧭 새 세션의 재개 위치

master PRD의 다음 개발 커서는 TASK-022.01 / Issue #96이다. #96~#101이 먼저이며 기존 TASK-004.04 / PR #95는 #101 이후 새 계약으로 재검증한다. 과거의 수동 Provider 설정 대기는 현재 선행 조건이 아니다. TASK-022.06은 설정/참석자 실통합과 문서 복사/read-back을 검증하며, 미구현 Session/History는 계약 fixture로 검증한다. 실제 후속 API 연동은 TASK-004.04/010.02/014.01/014.02/021.03의 필수 항목이다.

## 📋 적용하지 않은 검증

제품 구현 unit/API/브라우저/실제 Provider/Render 영속성 QA는 이 문서 PR에 N/A다. TASK-022 구현 후 해당 작업의 수용 기준에 따라 수행해야 하며 이 기록으로 PASS를 대신하지 않는다. 원래 PR #95 head `2e8d3273d29a99cfa1a34c5a71309bcf1362b877`와 미완료 QA checkpoint는 suspended 기록으로 보존한다.

## 🧪 TASK-022.01 제품 구현 검증 진행 기록

> 구현 PR #103 · 검증 head `129431905b1f219fc25b27bdb1be37e6b8dae8ce` · 2026-10-10 KST

- Backend 개발 검증: `backend/`에서 `./gradlew test` 및 `./gradlew clean build` 성공, 155 tests, 0 failures/errors. PR CI도 동일 head에서 Backend tests/build와 Gradle Wrapper 검증 성공.
- API contract QA: QA agent가 `./gradlew test --tests com.meetingautomation.DocumentSetupApiTests` 실행, 4 tests 성공. 빈 설정, draft 저장/멱등성, revision 충돌, Origin 검증, secret 응답·저장 보호, CORS를 확인했다.
- Provider read-only 사전 검증: 사용자가 제공한 Notion Internal connection으로 `/v1/users/me`가 HTTP 200 및 bot을 반환했다. 연결에 공유된 `회의록` 페이지를 `/v1/search`에서 찾았고 해당 페이지의 `/v1/pages/{id}` 조회도 HTTP 200, 활성 page로 확인했다. 이 두 GET 검증에서 페이지 본문을 읽거나 페이지를 변경하지 않았다. 토큰·페이지 식별자는 증거에 기록하지 않는다.
- 미완료: 앱의 `POST /api/v1/document-setup/drafts/{draftId}/test`를 실제 실행해 TESTED 상태와 암호화 snapshot 복원을 확인하는 live HTTP QA는 완료되지 않았다. 정확한 JAR 시작, PTY 시작, Gradle `bootRun` 경로가 Windows Codex 실행기에서 JDK HttpClient loopback 초기화 오류로 실패했다. MockMvc 검증과 직접 Provider GET 검증은 실행 중인 Spring 프로세스 결과를 대신하지 않는다.
- 추가 실행 경로 확인: `bootRun --no-daemon` 및 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`도 같은 `Unable to establish loopback connection`으로 실패했다. 설치된 Ubuntu WSL에는 Java가 없고 Docker daemon도 실행 중이지 않아 사용할 수 있는 별도 로컬 Java 런타임이 확인되지 않았다.
- 브라우저 직접 확인: Vite 로컬 화면을 Playwright로 열어 `Meeting Automation` 제목, `새 회의 시작` 링크, `설정` 링크를 각각 1개 확인했고 브라우저 console error는 없었다. `/settings/participants`로 이동하면 참석자 API 요청이 실패했다는 UI 경고가 표시됐다. 이는 TASK-022.01의 문서 Provider API acceptance를 검증하지 않으며, Spring API가 실행되지 않은 상태를 보여준다. 이 작업은 백엔드 전용이므로 문서 연결 설정 UI 브라우저 흐름은 현재 범위에 없다.
- 호환 런타임 재확인: Gradle 캐시에서 Adoptium Java 21을 찾았지만 제품 PRD DEC-004와 현재 `backend/build.gradle`은 Java 25를 고정한다. JDK 21 `bootRun --no-daemon`도 `Unable to establish loopback connection`으로 실패했고, 기존 Java 25 빌드 JAR 직접 실행은 classfile 69 때문에 JDK 21에서 거부됐다. IntelliJ 번들 JBR은 Java 17이라 사용할 수 없다. Java 21로 제품 기준을 바꾸기 전에는 Java 21 결과를 제품 QA 증거로 취급하지 않는다.
- 원인 분리: JDK 25와 JDK 21 양쪽에서 최소 `HttpServer`/`HttpClient` localhost 테스트가 `WEPollSelectorImpl`/`WindowsSelectorImpl` 생성 중 `SocketException: Invalid argument: connect`로 실패했다. JDK 25의 classic `WindowsSelectorProvider` 지정도 해결하지 못했다. 따라서 앱 설정 문제가 아닌 현재 실행기의 Java loopback socket 초기화 제약으로 분류한다. Node/Vite의 로컬 UI는 승인된 로컬 실행으로 접근됐지만 Spring live HTTP QA를 대신하지 않는다.
- Render 영속 디스크/재배포 확인은 실제 배포 환경 단계에서 수행한다. 로컬 TASK-022.01 검증의 사전 설정이나 운영 배포를 시작하는 조건으로 취급하지 않는다.

위 기록 시점에는 live HTTP QA 단계에서 BLOCKED였다. 아래 Windows 재검증으로 기동 장애는 해소됐다. Provider 연결 테스트와 snapshot 재시작 복원 QA는 아직 완료되지 않았으므로 merge 또는 PRD DONE으로 처리하지 않는다.

## 💻 Windows Java 재검증과 기동 장애 해소

> 2026-10-11 KST · 동일 head `129431905b1f219fc25b27bdb1be37e6b8dae8ce`

- Windows Oracle JDK 25.0.3와 Gradle Wrapper로 재검증했다. 기본 실행은 사용자 Gradle 캐시 접근 거절, 승인된 실행과 `--no-daemon --stacktrace`는 Java `PipeImpl` → `UnixDomainSockets.connect0`의 `Invalid argument: connect`로 실패했다. 기존 JAR 직접 실행도 같은 원인으로 종료됐다.
- 설치된 JDK 소스를 확인해 내부 Unix-domain socket 임시 경로를 지정하는 `jdk.net.unixdomain.tmpdir` 옵션을 찾았다. `backend/build/tmp`를 지정하자 소스 수정 없이 문제가 해소됐다. 기본 TEMP 경로와 내부 소켓 경로 처리의 관련성은 확인했지만, Windows 또는 Codex 실행기 전체가 loopback을 허용하지 않는다는 이전 결론은 철회한다. 경로 길이만이 유일한 원인이라고 확정하지 않는다.
- `backend/`에서 `JAVA_HOME`을 설치된 JDK 25로, `JAVA_TOOL_OPTIONS`를 `-Djdk.net.unixdomain.tmpdir=<backend의 절대 경로>/build/tmp`로 지정한 뒤 `.\gradlew.bat clean build --no-daemon --console=plain` 실행: exit 0, BUILD SUCCESSFUL, 155 tests / 0 failures / 0 errors.
- 새 빌드 JAR를 같은 JVM 옵션으로 실행했다. `DOCUMENT_SETTINGS_DIR`는 Git 저장소 밖의 사용자 LocalAppData에 있는 작업별 테스트 디렉터리로 지정하고, 로컬에서 생성한 32-byte Base64 암호화 키를 환경변수로 전달했다. 키 값은 기록하지 않는다.
- 실행 확인: Windows `java.exe` PID 1824, port 8080 LISTEN, Spring `Started MeetingAutomationApplication` 로그. `GET /actuator/health`: HTTP 200, `status=UP`. `GET /api/v1/app-config`: HTTP 200, `setup.status=UNCONFIGURED`, `connectionVersion=0`. 초기 시도에서 저장소 내부 경로를 지정했을 때의 `STORAGE_UNAVAILABLE`은 저장 위치를 수정한 후 해소됐다.
- 첫 실행 세션 20897은 완료됐다. 그 다음 Origin 설정을 적용한 실행과 live QA 및 snapshot 복원은 아래 기록에 별도로 남긴다. WSL과 Render는 이번 빌드/기동에 사용하지 않았다.

## 🌐 실제 앱 API와 Provider read-only QA

> 2026-10-11 KST · 정확한 제품 head `129431905b1f219fc25b27bdb1be37e6b8dae8ce`

- 첫 live QA 기동에는 `ALLOWED_ORIGINS`가 빠져 있어 `http://localhost:5173` mutation 요청이 의도대로 403됐다. 저장 변경이 발생하지 않음을 확인하고 로컬 QA 서비스에만 해당 origin을 지정해 재시작했다. 이것은 제품 코드 변경이 아니다.
- QA agent의 synthetic 요청에서 SETUP-EMPTY, draft 저장과 멱등 replay, stale revision 412, 같은 키 다른 payload 409, 비허용 Origin 403, 잘못된 입력 400을 확인했다. 저장 후 `CONFIGURING`, version 1 및 NOTION draft revision 1을 조회했다.
- 리더가 같은 head의 실제 앱 연결 테스트 API를 사용해 test-only Notion 연결로 설정된 `회의록` 페이지를 검증했다. 민감한 API 키는 로그·문서·응답에 남기지 않는다. draft 저장은 HTTP 201, revision 2였다. `POST /api/v1/document-setup/drafts/{draftId}/test`는 `authenticated=true`, `parentAccessible=true`, `writeCapability=UNVERIFIED`, version 3을 반환했다. 구현은 읽기 전용 `/v1/users/me` 및 `/v1/pages/{id}` GET을 호출한다. `writeCapability`는 확인하지 않았고 Notion 쓰기 요청은 실행하지 않았다.
- live test 뒤 설정 GET 응답에는 `token`/`credentials` 필드와 토큰 접두어가 없었다. 사용자 LocalAppData의 `settings.enc`는 `schemaVersion`, `nonce`, `ciphertext` envelope를 유지하고 암호문에 토큰 접두어와 credential property 이름이 나타나지 않았다. 평문 credential 값을 출력하거나 증거에 저장하지 않았다.
- 동일 JAR, 동일 암호화 키와 디렉터리로 다시 시작한 뒤 health `UP`, version 3, NOTION draft revision 2, state `TESTED`를 복원했다. QA agent가 같은 idempotency key로 저장된 테스트 결과를 replay해 HTTP 200, `authenticated=true`, `parentAccessible=true`, `writeCapability=UNVERIFIED`, version 3 및 secret 비노출을 확인했다. replay는 기존 응답을 반환해 Provider 호출을 반복하지 않았다.
- Backend-only TASK-022.01에서 브라우저 플로우는 N/A다. Render persistent disk/redeploy 확인은 로컬 개발의 acceptance 조건이 아니며 실제 배포 작업 단계에 둔다.

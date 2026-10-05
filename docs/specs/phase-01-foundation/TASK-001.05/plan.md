# FE/BE 독립 빌드 및 환경 샘플 구현 계획

## 목표

두 프로젝트의 빌드 경계를 보존하고 로컬 환경 샘플과 제한된 Secret 경계 검사를 제공한다.

- GitHub Issue: [#5](https://github.com/donghyunlee-dev/meeting-automation/issues/5)
- 선행 Issue: #2, #3, #4 완료 후 구현 착수
- PRD 기준: `PRD-MA-001` v1.6.0, `2026-10-05`

## 관련 원본

- [PRD](../../../product/PRD.md), §3.1~3.3, §20, §23.1, TASK-001.05
- [Architecture](../../../product/architecture.md), Frontend/Backend 책임 및 Secret 경계
- [Frontend Setup](../../../setup/frontend-setup.md), `VITE_API_BASE_URL`, `.env.local` ignore, `.env.example`
- [Backend Setup](../../../setup/backend-setup.md), 설정 키, Provider Secret, dotenv 비자동 로딩
- 선행 계약: [TASK-001.02](../TASK-001.02/spec.md), [TASK-001.03](../TASK-001.03/spec.md), [TASK-001.04](../TASK-001.04/spec.md)

## 변경 대상과 소유 경계

| 경로 | 소유 및 변경 |
|---|---|
| `frontend/.env.example` | FE 공개 설정 샘플; `VITE_API_BASE_URL`만 제공 |
| `backend/.env.example` | BE 로컬 설정 참고 샘플; 실제 credential 값은 비움 |
| `.gitignore` | 개인 환경 파일이 ignore되는지 확인하고 필요한 최소 패턴만 추가 |
| `scripts/verify-secret-boundary.mjs` | 두 환경 샘플의 변수명/credential 값 경계를 검사하는 Node.js 22 스크립트 |
| `scripts/verify-secret-boundary.test.mjs` | 정상 및 위반 fixture에 대한 Node.js 내장 테스트 |
| `frontend/package.json` | 기존 명령을 보존하며 검사기 실행 진입점이 필요할 때만 스크립트 추가 |
| `docs/evidence/TASK-001.05.md` | 명령, 종료 결과, 샘플/ignore 검사 증거 기록 |

Backend 빌드 스크립트나 Frontend 앱 소스는 변경하지 않는다. 두 독립 빌드는 기존 Wrapper/package 명령을 각각 실행해 입증한다.

## 구현 순서와 소유권

1. **FE/BE — 선행 기반과 명령 확인:** #2~#4 완료 여부, lockfile, Gradle Wrapper, 기존 ignore 및 빌드 산출물 경로를 확인한다. 결과: 네이티브 빌드 명령과 환경 파일 경로가 고정된다.
2. **FE/BE — 실패 테스트 작성:** 테스트가 실행 가능한 최소 검사기 진입점과 `inspectEnvSamples` stub을 두고, `scripts/verify-secret-boundary.test.mjs`에 정상 입력, 값이 채워진 credential 입력, FE 환경 샘플의 Backend credential 입력을 둔다. `node --test scripts/verify-secret-boundary.test.mjs` 결과: 테스트는 로드되고 아직 검증하지 않는 stub에 대한 계약 assertion이 실패한다.
3. **FE/BE — 환경 샘플 추가:** Frontend에는 공개 API URL만, Backend에는 설정 가이드의 비밀 아닌 키와 빈 credential 키를 작성한다. 결과: 각 팀이 키 이름과 안전한 로컬 기본값을 확인할 수 있다.
4. **공통 — 최소 검사기 구현:** Node.js 내장 API만 사용하는 `scripts/verify-secret-boundary.mjs`를 추가한다. 샘플 필수 키, Frontend 허용 변수, Backend credential 변수 값이 비었는지 점검한다. credential 키는 `NOTION_TOKEN`, `CONFLUENCE_AUTH_TOKEN`, `OPENAI_API_KEY`, `SLACK_MEETING_WEBHOOK_URL`, `SLACK_ADMIN_WEBHOOK_URL` 및 `EMAIL_PROVIDER` 외 `EMAIL_*`로 한정한다. 결과: 정상 fixture는 통과하고 위반 fixture는 파일/키만 표시하며 실패한다.
5. **FE/BE — 독립 빌드 확인:** Frontend 명령을 `frontend/`에서, Backend 명령을 `backend/`에서 별도 실행한다. 결과: 한 쪽 산출물이나 설치가 없어도 각 프로젝트가 독립 통과한다.
6. **공통 — 회귀 및 증거:** 검사기, 양쪽 전체 빌드와 `.gitignore` 상태를 확인해 `docs/evidence/TASK-001.05.md`에 비민감 결과를 기록한다.

환경 샘플은 인터페이스를 먼저 정하는 작은 산출물이며 FE와 BE가 각자 소유 파일을 추가한다. 검사 테스트를 먼저 두고 샘플·검사기를 추가한 뒤 빌드를 병렬로 검증한다. 업무 API 통합은 이 TASK의 목적이 아니다.

## 의존성

- `TASK-001.02` (#2): Node/npm, Frontend lockfile, `test`/`lint`/`build` 명령.
- `TASK-001.03` (#3): Backend Gradle Wrapper 및 `clean build` 명령.
- `TASK-001.04` (#4): Backend 오류/Health 기반과 관련된 표준 구성이 완료된 상태.
- Frontend는 Node.js 22 이상 22.x, Backend는 JDK 25와 Wrapper를 각각 사용한다.
- `.env.example`은 Spring Boot 자동 로딩을 전제하지 않으며 개발자가 IDE/runtime 환경 변수로 적용한다.

## 검증 접근

- Frontend 전체 확인: `npm ci`, `npm run test`, `npm run lint`, `npm run build`를 `frontend/`에서 수행한다.
- Backend 전체 확인: `./gradlew clean build`를 `backend/`에서 수행한다. Windows PowerShell에서는 `./gradlew.bat clean build`를 사용한다.
- 경계 확인: `node --test scripts/verify-secret-boundary.test.mjs`와 `node scripts/verify-secret-boundary.mjs`; 두 샘플 파일 및 위반 fixture 종료 코드를 확인한다.
- Ignore 확인: `git check-ignore frontend/.env.local backend/.env.local`과 `.env.example`의 추적 가능 여부를 확인한다.
- 증거에는 환경 변수의 실제 값, 토큰, Secret을 복사하지 않고 명령, 버전 및 결과 코드만 기록한다.

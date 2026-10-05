# FE/BE 독립 빌드 및 환경 샘플

## 작업 식별 정보

- 작업: `TASK-001.05`
- 상위 작업 묶음: `TASK-001 Monorepo / Technology Baseline`
- 단계: `Phase 1 — 프로젝트 기반 / 공통 구조`
- PRD 기준: `PRD-MA-001`, 버전 `1.6.0`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-017`, `NFR-004`, `NFR-008`, `NFR-009`
- 영역: `FE`, `BE`
- 선행 작업: `TASK-001.02` Frontend 도구 체계 초기화 (#2), `TASK-001.03` Backend 도구 체계 초기화 (#3), `TASK-001.04` 공통 오류 응답 및 Health 기반 (#4)
- GitHub Issue: [#5](https://github.com/donghyunlee-dev/meeting-automation/issues/5)

## 결과

Frontend와 Backend가 각자 소유한 디렉터리에서 독립적으로 의존성을 설치하고 빌드할 수 있음을 검증한다. 개발자가 로컬 환경을 설정할 수 있는 환경 샘플을 제공하며, 샘플에 실제 Secret이 들어가지 않도록 자동 검사한다.

## 범위

- Frontend에서 `npm ci`, `npm run test`, `npm run lint`, `npm run build`를 `frontend/`에서 실행한다. Frontend 빌드는 Backend 또는 루트 공통 빌드 성공에 의존하지 않는다.
- Backend에서 `./gradlew clean build`를 `backend/`에서 실행한다. Backend 빌드는 Frontend 설치 또는 빌드에 의존하지 않는다. Windows에서도 `gradlew.bat`를 사용할 수 있어야 한다.
- `frontend/.env.example`을 제공하고 `VITE_API_BASE_URL=http://localhost:8080`을 로컬 개발 기본값으로 문서화한다. Vite 환경에는 공개 설정만 둔다.
- `backend/.env.example`을 제공한다. 비밀이 아닌 Backend 설정은 설정 가이드의 로컬 예시를 사용하고, Provider credential 키는 빈 값으로 표기한다. 이 파일은 설정 참고용이며 Spring Boot가 dotenv 파일을 자동으로 읽는다고 가정하지 않는다.
- 루트의 `scripts/verify-secret-boundary.mjs` 검사기를 추가한다. 검사 대상은 두 환경 샘플이다. Backend credential 키(`NOTION_TOKEN`, `CONFLUENCE_AUTH_TOKEN`, `OPENAI_API_KEY`, `SLACK_MEETING_WEBHOOK_URL`, `SLACK_ADMIN_WEBHOOK_URL`, `EMAIL_PROVIDER` 외 `EMAIL_*`) 값은 비어 있어야 한다. Frontend 환경 샘플에는 `VITE_API_BASE_URL` 외 환경 변수를 허용하지 않는다. 번들/API/로그 전체의 보안 검사는 후속 `TASK-017.01` 범위다.
- `frontend/.env.local`, `frontend/.env.*.local`, `backend/.env.local`, `backend/.env.*.local` 등 개인 환경 파일이 Git에 추적되지 않으며 ignore 규칙에 걸리는지 확인한다.

## 명시적 제외 범위

- Frontend 업무 기능, Backend API·도메인 기능 및 Provider 연결을 추가하지 않는다.
- 배포·CI 파이프라인, 실제 운영 환경값, 사용자별 Secret 저장 또는 Secret 관리 서비스를 추가하지 않는다.
- Backend에 dotenv loader를 추가하거나 `.env.example`의 자동 로딩을 구현하지 않는다.
- TASK-001.02~001.04가 제공하는 toolchain, 오류 응답 및 Health 계약을 다시 설계하지 않는다.
- Secret 검사기를 범용 저장소 전체 secret scanner로 확장하지 않는다.

## 완료 기준

- `frontend/`에서 `npm ci`, `npm run test`, `npm run lint`, `npm run build`가 성공한다.
- `backend/`에서 `./gradlew clean build`가 성공한다. 각 프로젝트의 명령은 다른 프로젝트 디렉터리나 도구를 호출하지 않는다.
- `frontend/.env.example`은 `VITE_API_BASE_URL=http://localhost:8080`을 포함하고 그 밖의 환경 변수를 포함하지 않는다.
- `backend/.env.example`은 설정 가이드의 비밀 아닌 로컬 설정 키를 제공하며, 비밀 credential의 실제 값은 포함하지 않는다.
- 검사기는 두 환경 샘플을 검사하고, credential 키에 값이 있거나 허용하지 않은 Frontend 환경 변수가 있으면 0이 아닌 종료 코드로 끝난다.
- 검사기의 정상 fixture는 통과하고, Secret 값이 채워진 샘플 및 Backend 변수가 추가된 Frontend 샘플 fixture는 각각 실패한다.
- 개인 환경 파일 패턴이 Git ignore 규칙에 의해 제외되고, `.env.example`은 추적 가능한 상태다.
- 검사 명령, 각 독립 빌드 결과 및 비민감 증거를 `docs/evidence/TASK-001.05.md`에 기록한다.

## 결정 및 전제

- PRD §3.1~3.3, §20, §23.1과 `DEC-017` 기준을 따른다. FE는 Node.js 22/npm/Vite, BE는 Java 25/Spring Boot/Gradle Wrapper를 소유한다.
- Frontend 샘플의 API 주소는 [Frontend Setup](../../../setup/frontend-setup.md)의 `VITE_API_BASE_URL` 예시를 따른다.
- Backend 샘플의 키와 로컬 기본값은 [Backend Setup](../../../setup/backend-setup.md)의 비밀 아닌 설정 예시를 따른다. Secret 키 예시는 값 없이 둔다.
- 실제 Provider credential은 Backend runtime 설정으로만 주입한다. `VITE_` 변수는 브라우저 번들에 공개될 수 있으므로 credential을 담지 않는다.
- 선행 Issue #2, #3, #4는 모두 열려 있다. 설계 문서 및 Issue 등록은 진행하되 구현 착수는 세 선행 작업이 완료된 후다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: #2가 Frontend 명령을, #3이 Backend Wrapper와 빌드를, #4가 안전한 Health 기반을 제공한다.
- 후속 작업: `TASK-002.01` 등 Backend 기능 작업은 Backend 단독 빌드와 Secret 주입 경계를 사용한다. `TASK-017.01`의 보안 회귀 작업은 더 넓은 런타임/API/로그 경계를 소유하며 이 작업의 검사기를 대체하지 않는다.

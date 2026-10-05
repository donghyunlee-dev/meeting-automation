# 저장소 기본 구조와 제외 규칙

## 작업 식별 정보

- 작업: `TASK-001.01`
- 상위 작업 묶음: `TASK-001 Monorepo / Technology Baseline`
- 단계: `Phase 1 — Project Foundation / Shared Structure`
- PRD 기준: `PRD-MA-001`, 버전 `1.3.0`, 작성일 `2026-10-05`
- 관련 요구사항: `DEC-001`~`DEC-020`, `NFR-004`, `NFR-005`, `NFR-008`, `NFR-009`
- GitHub Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)

## 결과

Frontend와 Backend의 도구 체계를 구성하기 전에 저장소 최상위 구조와 공통 문서 진입점을 마련한다. Frontend, Backend, 문서 영역을 분리하고, 생성되는 빌드 파일과 로컬 환경 파일이 Git에 포함되지 않도록 하되 안전한 예제 환경 파일은 추적 가능하게 둔다.

## 범위

- 아래 생성 파일과 환경 파일 규칙을 포함한 루트 `.gitignore`를 추가한다.
- 제품 소개, Frontend/Backend/docs의 책임 경계, PRD 및 설정 가이드 링크를 담은 루트 `README.md`를 추가한다.
- 아직 파일이 없는 `frontend/`, `backend/`, `docs/ADR/`, `docs/evidence/`를 추적할 표식 파일을 추가한다.
- 기존 `docs/` 내 제품 및 설정 문서는 추적 가능한 원본으로 보존한다.

### 제외 규칙

| 경로 패턴 | 이유 |
|---|---|
| `**/node_modules/` | Node 패키지는 lockfile로 재현 가능하며 커밋하지 않는다. |
| `/frontend/dist/` | Vite 배포 산출물이다. |
| `/frontend/.vite/` | Vite 로컬 캐시다. |
| `/backend/.gradle/` | Gradle 프로젝트 캐시다. |
| `/backend/build/` | Gradle 빌드 산출물이다. |
| `**/.env` 및 `**/.env.*` | 로컬 환경 설정 또는 비밀 값이 포함될 수 있다. |
| `!**/.env.example` 및 `!**/.env.*.example` | 비어 있거나 문서화된 예제 파일은 추적 가능해야 한다. |

환경 예외 규칙은 환경 파일 제외 규칙 뒤에 둬서 `.env.example`과 `.env.development.example`을 Git에 추가할 수 있게 한다. 검증 증거는 산출물이므로 `docs/evidence/`를 제외하지 않는다.

## 명시적 제외 범위

- React, Vite, TypeScript, Spring Boot 또는 Gradle을 초기화하지 않는다. 해당 작업은 `TASK-001.02`와 `TASK-001.03`의 범위다.
- 패키지 매니페스트, 빌드 스크립트, 소스 코드, 의존성, CI, 배포 설정을 추가하지 않는다.
- README, 예제 파일 또는 추적되는 파일에 실제 환경 값이나 비밀 값을 넣지 않는다.
- 기존 PRD, 설정 가이드, 제품 문서를 이동·변경·재작성하지 않는다.

## 완료 기준

- `README.md`, `.gitignore`, 추적용 디렉터리 표식으로 `frontend/`, `backend/`, `docs/` 영역이 저장소 최상위에서 구분된다.
- 저장소 트리에 `docs/ADR/`, `docs/evidence/`가 있으며 검증 증거를 Git에서 추적할 수 있다.
- `git check-ignore`가 제외 규칙 표의 생성 경로를 모두 제외 대상으로 판정한다.
- `git check-ignore`가 `.env.example`, `.env.development.example`, `docs/evidence/verification.md`를 제외 대상으로 판정하지 않는다.
- 지나치게 넓은 문서 제외 규칙으로 기존 추적/미추적 제품 및 설정 Markdown 파일을 숨기지 않는다.
- README의 `docs/product/PRD.md`, `docs/setup/frontend-setup.md`, `docs/setup/backend-setup.md` 링크가 실제 파일을 가리킨다.
- 앱 도구 체계나 실제 환경 값은 이 작업에서 추가하지 않는다.

## 결정 및 전제

- PRD 3.1절에 따라 저장소 루트가 Monorepo 루트다.
- PRD와 설정 가이드에 명시된 제외 요구사항을 따른다. 이 작업에서 지정한 FE/BE 도구의 산출물만 제외해 그 밖의 파일을 숨기지 않는다.
- 후속 FE/BE 도구 설정 작업에서 실제 파일이 들어오면 디렉터리 표식은 제거할 수 있다.

## 인접 작업 계약

- 선행 작업: 없음.
- 후속 작업: `TASK-001.02`는 `frontend/`에 Frontend 도구 체계를 만들고, `TASK-001.03`은 `backend/`에 Backend 도구 체계를 만든다.
- 이 작업은 루트 구조, 공통 문서 진입점, 제외 정책만 담당하며 두 도구 체계를 미리 만들지 않는다.

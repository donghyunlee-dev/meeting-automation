# Frontend 도구 체계 초기화

## 작업 식별 정보

- 작업: `TASK-001.02`
- 상위 작업 묶음: `TASK-001 Monorepo / Technology Baseline`
- 단계: `Phase 1 — 프로젝트 기반 / 공통 구조`
- PRD 기준: `PRD-MA-001`, 버전 `1.3.0`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-003`, `NFR-008`
- 영역: `FE`
- 선행 작업: `TASK-001.01` 저장소 기본 구조 및 ignore 구성
- GitHub Issue: [#2](https://github.com/donghyunlee-dev/meeting-automation/issues/2)

## 결과

`frontend/`에 Node.js 22 LTS와 npm을 사용하는 React 19, TypeScript 5.x, Vite 7.x 기반의 독립 실행 가능한 웹 앱 도구 체계를 만든다. 기본 화면 렌더링을 검증하는 Vitest 테스트와 `dev`, `build`, `lint`, `test` npm 스크립트를 제공한다.

## 범위

- Vite React TypeScript 앱을 `frontend/`에 초기화한다.
- Node.js 22 기준을 저장소에서 확인할 수 있도록 버전 기준 파일을 구성한다.
- React, TypeScript, Vite 및 PRD가 정한 ESLint/Vitest 개발 도구를 설정한다.
- 최소 앱 진입점과 기본 렌더 테스트를 추가한다. 기본 앱은 `Meeting Automation` 제목을 가진 `h1` 하나를 표시해 실행 여부를 확인한다.
- `npm run dev`, `npm run build`, `npm run lint`, `npm run test` 명령을 제공한다.
- 재현 가능한 설치를 위해 `package-lock.json`을 커밋 대상으로 생성한다.

## 명시적 제외 범위

- 화면·제품 기능, 라우팅, API 연동, 인증, UI 디자인 시스템을 만들지 않는다.
- Backend, 배포, CI, 운영 환경 및 환경 샘플 설정을 추가하지 않는다. 독립 빌드 및 환경 샘플은 `TASK-001.05` 범위다.
- V1의 기준 버전인 React 19, TypeScript 5.x, Vite 7.x를 임의로 다른 Major로 변경하지 않는다.
- Vite 환경변수에 Secret, OpenAI 또는 외부 Provider credential을 넣지 않는다.

## 완료 기준

- `frontend/`에서 `npm ci`가 성공하고 잠금 파일로 의존성을 재현할 수 있다.
- `npm run test`는 대기 없이 종료하는 `vitest run`을 실행한다. `App`의 `Meeting Automation` 제목을 검증하는 테스트가 구현 전 제목 부재로 실패하고 구현 후 통과한다.
- `npm run build`가 성공한다.
- `npm run lint`가 성공한다.
- `npm run dev`가 Vite 개발 서버를 실행하며 기본 앱을 표시한다.
- Node 런타임 기준이 Node.js 22로 명시되어 있다. Vite 7의 실행 조건을 충족하는 22.12 이상을 사용하고 `package.json`의 `engines.node`는 `>=22.12.0 <23`으로 선언한다.
- 애플리케이션 업무 API가 Node.js에서 구현되지 않으며 이 작업에서 Frontend Secret이 추가되지 않는다.

## 결정 및 전제

- PRD 3.1~3.2절의 Monorepo 및 Frontend 기준을 따른다: Node.js 22 LTS, npm, React 19, TypeScript 5.x, Vite 7.x, ESLint, Vitest.
- 세부 패키지 버전은 기준 Major를 지키면서 설치 시점에 호환되는 버전을 잠금 파일에 고정한다.
- 환경 변수는 Vite 클라이언트 bundle에 포함될 수 있으므로 공개 가능한 설정만 허용한다.
- 선행 작업 Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)은 `CLOSED`이며 PR #63이 `master`에 병합되었다. 선행 조건이 충족되어 이 작업의 구현을 진행한다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: `TASK-001.01` 저장소 루트, `frontend/` 디렉터리 및 ignore 정책.
- 후속 작업: `TASK-001.05`가 Frontend와 Backend의 독립 빌드 및 환경 샘플을 통합 검증한다.
- `TASK-001.03` Backend 도구 초기화와 서로의 파일을 수정하지 않고 독립적으로 진행할 수 있다.

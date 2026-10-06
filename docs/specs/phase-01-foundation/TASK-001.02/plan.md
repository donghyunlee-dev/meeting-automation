# Frontend 도구 체계 초기화 계획

## 목표

`frontend/`에서 React 웹 앱을 설치·실행·검증할 수 있는 최소 개발 기반을 만든다. 기본 렌더 테스트와 build/lint 명령으로 scaffold가 정상인지 확인한다.

- GitHub Issue: [#2](https://github.com/donghyunlee-dev/meeting-automation/issues/2)
- 선행 작업: `TASK-001.01`, [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1) 완료 (PR #63 merged to `master`)
- 후속 작업: `TASK-001.05`

## 관련 원본

- [PRD](../../../product/PRD.md), 3.1~3.2절, 23.1절 및 `TASK-001.02`
- [Frontend 설정](../../../setup/frontend-setup.md), 환경 개요, 의존성 설치와 실행, 완료 기준
- [Architecture](../../../product/architecture.md), Frontend 책임과 독립 빌드

## 소유 경계

| 경로 | 책임 |
|---|---|
| `frontend/package.json` 및 `package-lock.json` | 프로젝트 의존성, 재현 설치, `dev`/`build`/`lint`/`test` 명령 |
| `frontend/vite.config.ts` 및 `frontend/tsconfig*.json` | Vite 및 TypeScript 빌드 설정 |
| `frontend/eslint.config.js` | TypeScript/React 코드 정적 검사 |
| `frontend/src/main.tsx`, `frontend/src/App.tsx` | `createRoot` 앱 진입점 및 기본 제목 |
| `frontend/src/App.test.tsx` | Vitest와 `@testing-library/react`의 기본 렌더 검증 |
| `frontend/vitest.config.ts` | React 변환, `jsdom` 테스트 환경, 테스트 수집 설정 |
| `frontend/index.html` | Vite 앱 진입 HTML |
| `frontend/.nvmrc` | 값 `22`로 Node.js 22 런타임 기준 명시 |

`backend/`, 루트 README/.gitignore, `docs/product/`, API 호출 계층, 제품 화면과 배포 파일은 이 작업에서 수정하지 않는다. Vite scaffold가 불필요한 샘플 화면이나 자산을 만들면 제거한다.

## 구현 순서와 소유권

1. **FE — 테스트 기반 준비:** #1 완료 후 Node 22.12 이상에서 Vite React TypeScript 설정과 ESLint/Vitest 실행 기반을 만든다. `App`은 제목이 없는 최소 컴포넌트로 두고 테스트 수집이 가능한 상태를 만든다. 설정 부재나 import 오류는 요구사항의 실패 증거로 인정하지 않는다.
2. **FE — 실패 확인:** `App.test.tsx`에서 `App`을 렌더하고 `screen.getByRole('heading', { level: 1, name: 'Meeting Automation' })`로 확인한다. 테스트가 제목 부재로 실패하는지 기록한다.
3. **FE — 최소 구현:** `App`에 제목을 추가하고 `main.tsx`에서 렌더한다. 같은 테스트를 다시 실행해 통과시키고 scaffold 샘플 자산을 정리한다.
4. **FE — 통과 검증:** `npm ci`, `npm run test`, `npm run lint`, `npm run build`를 실행하고 개발 서버에서 화면 표시를 확인한다.
5. **FE — 증거:** 실행 명령, Node/npm 버전, 테스트/build/lint 결과를 `docs/evidence/TASK-001.02.md`에 남긴다.

UI 전에 도구 체계를 초기화하는 이유는 제품 화면 작업(`TASK-004.01` 등)이 React 렌더 및 컴포넌트 테스트 환경에 의존하기 때문이다. 이 작업은 API 데이터가 필요 없는 scaffold이므로 Backend 선행 작업이 없다.

## 의존성

- 시작 전 `TASK-001.01` 구조와 ignore 규칙이 저장소에 존재해야 한다.
- PRD 기준선: Node.js 22 LTS, npm, React 19, TypeScript 5.x, Vite 7.x, ESLint, Vitest.
- `TASK-001.05`에서 Backend와의 독립 빌드를 함께 확인한다.

## 검증 접근

이 작업에서 `package.json`에 `dev: vite`, `build: tsc -b && vite build`, `lint: eslint .`, `test: vitest run` 스크립트를 새로 제공한다. 런타임 의존성은 `react`, `react-dom`이고 테스트 기반에 `vitest`, `@testing-library/react`, `jsdom`을 추가한다. React/TypeScript 타입과 Vite React 플러그인도 개발 의존성으로 둔다. 플러그인은 Vite 7 호환 범위를 확인하고 잠금 파일에 실제 설치 버전을 기록한다.

자동 검증은 Node.js 22 환경에서 `npm ci`, `npm run test`, `npm run lint`, `npm run build` 순으로 수행한다. 개발 서버 확인은 `npm run dev`를 실행하고 Vite가 안내하는 로컬 주소의 화면을 확인한다. 실제 제품 동작/API 검증은 이 작업의 완료 조건이 아니다.

Node 최소 버전의 근거는 [Vite 7 공식 가이드](https://v7.vite.dev/guide/)의 호환성 기준이다. PRD의 Vite 7 기준을 지키며 최신 scaffold가 다른 Major를 생성하면 그대로 채택하지 않는다.

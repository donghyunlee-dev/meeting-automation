# Frontend 도구 체계 초기화 테스트 계획

## 테스트 목표

React/TypeScript/Vite 개발 도구가 Node.js 22에서 설치·실행·빌드되고, 기본 앱 렌더가 Vitest로 검증되는지 확인한다. 이 작업은 제품 API나 Backend 테스트를 포함하지 않는다.

- GitHub Issue: [#2](https://github.com/donghyunlee-dev/meeting-automation/issues/2)
- 테스트 명령은 이 작업에서 `plan.md`에 따라 새로 제공한다.

## 자동 테스트

저장소 루트에서 `Set-Location frontend`를 실행한 뒤 npm 명령을 실행한다.

### 기본 앱 렌더 테스트

1. `plan.md`의 테스트 실행 기반과 제목 없는 최소 `App`을 준비한다.
2. `App.test.tsx`에서 `App`을 렌더하고 제목 `Meeting Automation`을 가진 level 1 heading을 검증한다.
3. 제목 구현 전에 `npm run test -- src/App.test.tsx`를 실행한다. 기대 결과: Vitest가 테스트를 수집하고 제목 부재로 실패한다. 설정/import 오류는 실패 증거로 인정하지 않는다.
4. 제목 구현 후 같은 명령을 다시 실행한다.
5. 기대 결과: 테스트가 통과한다.

### Node 및 npm 도구 체계

```powershell
node --version
npm --version
npm ci
npm run
```

기대 결과: Node가 22.12 이상 22.x이며 설치가 성공한다. 스크립트 목록에 `dev`, `build`, `lint`, `test`가 표시되고 `.nvmrc` 값이 `22`, `engines.node`가 `>=22.12.0 <23`이다. 초기 scaffold에서 lockfile이 없는 동안만 `npm install`을 사용하고, 산출된 lockfile을 다음 확인부터 기준으로 한다.

### 정적 검사와 빌드

```powershell
npm run test
npm run lint
npm run build
```

기대 결과: 기본 렌더 테스트, ESLint 검사, Vite production build가 모두 종료 코드 0으로 끝난다.

## 수동 QA

```powershell
npm run dev
```

- Vite가 안내한 로컬 주소를 브라우저에서 연다.
- 기본 앱이 오류 화면 없이 `Meeting Automation` 제목을 표시하는지 확인한다.
- `frontend/` 코드, `.env*` 파일 및 브라우저 bundle에 Secret 또는 Provider credential이 없는지 변경 파일 기준으로 확인한다.
- 이 작업에서 API 호출, 화면 흐름, Backend 기능이 추가되지 않았는지 변경 목록을 확인한다.

## 증거

최종 결과를 `docs/evidence/TASK-001.02.md`에 기록한다. Node/npm 버전, 설치 명령, 테스트·lint·build 종료 결과, 개발 서버 수동 확인을 포함한다. 인증 정보, 환경 값 및 비밀 값은 기록하지 않는다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| 재현 가능한 설치 | Node 및 npm 도구 체계: `npm ci` 성공, lockfile 존재 |
| 기본 렌더의 실패 후 통과 | 기본 앱 렌더 테스트: 같은 대상 테스트의 실패 이유와 통과 결과 |
| build/lint 성공 | 정적 검사와 빌드: 각 종료 코드와 `dist/index.html` 생성 확인 |
| 개발 서버 표시 | 수동 QA: 로컬 URL, 제목 표시 확인, 화면 캡처 |
| Node 22 기준 | Node 및 npm 도구 체계: 실제 버전, `.nvmrc`, `engines.node` 확인 |
| 업무 API 및 Secret 경계 | 수동 QA: 변경 파일 목록과 비민감 점검 결과 |

## 릴리스 전용 검사

이 작업에는 배포 또는 실제 모바일 QA가 없다. Android/iOS 및 회의실 검증은 PRD Phase 8, 배포 및 전체 수용 검증은 후속 작업에서 수행한다.

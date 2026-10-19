# 🖥️ Frontend 개발 환경 설정

이 가이드는 Meeting Automation 모노레포의 `frontend/` React 웹을 로컬에서 실행하기 위한 절차를 설명한다. 제품 기준은 [PRD](../product/PRD.md), 화면/요구사항은 [Architecture](../product/architecture.md)와 PRD를 따른다.

> **기준 환경:** Node.js 24, npm, React 19, TypeScript 5.x, Vite 7.x, VS Code
> **주 개발 OS:** Windows 10/11 + PowerShell

## 환경 개요

```text
meeting-automation/
├─ frontend/       # 이 문서에서 설정하는 React 앱
├─ backend/        # Spring Boot API
└─ docs/
```

Frontend는 업무 API 서버가 아니다. OpenAI, Notion/Confluence, Email, Slack의 Secret은 Backend에서만 관리한다. 오디오 조각도 Backend API에 직접 전송한다. 브라우저에 노출되는 `VITE_` 변수에는 Backend 공개 URL 외 Secret을 넣지 않는다.

## 사전 준비

- Git 저장소를 로컬에 clone한다.
- Node.js 24를 설치하고 프로젝트의 `.nvmrc`에 기록된 버전을 선택한다.
- IDE 설치 후 이 문서의 VS Code 설정을 적용한다.

### Node.js 설치

Windows에서는 [Node.js 공식 다운로드](https://nodejs.org/en/download)에서 24.x Windows Installer를 받아 설치한다. 설치 마법사에서 npm 설치와 PATH 등록을 유지한다. 이미 Node 버전 관리 도구를 쓰는 경우 저장소의 `.nvmrc` 버전을 선택한다.

새 PowerShell 창을 열어 PATH를 다시 읽은 다음 확인한다.

```powershell
node --version
npm --version
where.exe node
```

`node --version`이 `v24`로 시작하는지 확인한다. 다른 버전이 먼저 출력되면 `where.exe node`에 나온 경로 순서와 IDE 터미널의 PATH를 확인한다.

macOS/Linux에서는 [Node.js 공식 다운로드](https://nodejs.org/en/download/package-manager) 페이지의 OS별 설치 방법을 따른다. Node 버전 관리 도구를 쓰는 팀원은 저장소 `.nvmrc`가 제공될 경우 이를 사용한다.

## 의존성 설치와 실행

저장소 루트에서 시작한다.

```powershell
Set-Location frontend
```

프로젝트에 `package-lock.json`이 있으면 잠금된 의존성을 설치한다. `.npmrc`의 strict engine 검사로 Node 24 범위를 벗어나면 설치가 실패한다.

```powershell
npm ci
```

lockfile이 아직 만들어지지 않은 초기 scaffold 상태에서만 다음 명령을 사용한다.

```powershell
npm install
```

개발 서버를 실행한다.

```powershell
npm run dev
```

터미널에 출력되는 Local URL을 브라우저에서 연다. Vite 기본 포트는 `5173`이다. 다른 서비스가 해당 포트를 사용 중이면 Vite가 출력한 실제 URL을 사용한다. `npm run dev`는 화면 개발 서버만 실행한다. 실제 회의 흐름은 Backend를 별도로 실행하고 `VITE_API_BASE_URL`로 연결한다. 오디오 업로드·처리 계약은 [Backend 소유 Audio 설계](../superpowers/specs/2026-10-16-backend-owned-audio-design.md)를 따른다.

프로젝트 스크립트와 사용 가능한 명령을 확인하려면 다음을 실행한다.

```powershell
npm run
```

TASK-001에서 `dev`, `build`, `lint`, `test` 스크립트를 제공한다. 스크립트가 준비되면 각각 다음처럼 실행한다.

```powershell
npm run build
npm run lint
npm run test
```

## 환경변수

Vite는 `VITE_` 접두사가 붙은 값을 브라우저 bundle에 포함한다. 이 값은 비밀로 취급할 수 없다.

`frontend/.env.local` 예시:

```dotenv
VITE_API_BASE_URL=http://localhost:8080
```

환경변수를 추가/변경한 뒤에는 Vite 개발 서버를 재시작한다. 실제 배포 URL을 로컬 파일에 기록하지 말고 개발/배포 환경을 분리한다. `.env.local`은 Git에 올리지 않고, Secret을 요구하는 설정을 여기에 두지 않는다.

프로젝트의 `.gitignore`에 아래 패턴이 있는지 확인한다.

```gitignore
.env.local
.env.*.local
```

`frontend/.env.example`이 제공되면 복사한 뒤 값만 로컬용으로 채운다. 예시 파일에는 Secret을 넣지 않는다.

## Backend 연결

- Frontend API 기본 주소는 `VITE_API_BASE_URL`로 설정한다.
- Backend 로컬 실행 주소는 Backend 가이드의 설정을 기준으로 한다. PRD의 예시에서는 `8080` 포트를 사용한다.
- 브라우저가 API에 접근할 수 있도록 Backend의 허용 Origin에 Frontend 개발 주소(`http://localhost:5173`)를 설정한다.
- CORS 오류가 발생하면 API 주소, 포트, scheme(http/https), Backend `ALLOWED_ORIGINS`를 함께 확인한다. Frontend에서 Provider Secret을 사용해 우회하지 않는다.

## Vercel 배포

Vercel 프로젝트는 monorepo의 `frontend/` 디렉터리를 Root Directory로 사용한다. Framework Preset은 Vite, Node.js는 `24.x`, Install Command는 `npm ci`, Build Command는 `npm run build`, Output Directory는 `dist`로 설정한다. Production과 Preview의 `VITE_API_BASE_URL`에는 Render API의 HTTPS 주소를 지정한다.

오디오 저장에는 Vercel 프로젝트에 연결된 private Blob store를 사용한다. `frontend/api/` Function 환경에는 `BLOB_READ_WRITE_TOKEN`, `RENDER_AUDIO_STORAGE_SECRET`, `RENDER_AUDIO_GRANT_SECRET`만 Secret으로 설정한다. 이 값은 `VITE_` 접두사를 붙이지 않는다. Render 서비스에는 같은 두 `RENDER_AUDIO_*_SECRET` 값과 `VERCEL_BLOB_CONTROL_URL=https://<project-domain>/api/audio-storage`를 설정한다. 브라우저는 Render API에서 단기 업로드 grant를 받은 뒤 private Blob으로 직접 청크를 올리고, Render가 Blob 내용을 확인한 다음 업로드를 승인한다.

저장소가 Vercel 프로젝트의 Git source에 연결되어 있지 않으면 `master` 푸시만으로 배포가 시작되지 않는다. 연결을 복구하거나 프로젝트가 `frontend/`를 root로 사용하는지 확인한 뒤 프로덕션 배포를 실행한다. CLI에서 배포할 때는 커밋 작성자 이메일이 프로젝트의 배포 권한이 있는 Vercel 계정과 일치해야 한다. 배포 후 홈페이지 응답, Render API 연결, private Blob 업로드와 확인 응답을 각각 검증한다.

## VS Code 설정

VS Code에서 모노레포 루트 `meeting-automation/`을 연다. 확장 프로그램은 다음을 권장한다.

- [ESLint](https://marketplace.visualstudio.com/items?itemName=dbaeumer.vscode-eslint)
- [Prettier - Code formatter](https://marketplace.visualstudio.com/items?itemName=esbenp.prettier-vscode) — 저장 시 포맷을 프로젝트가 설정한 경우 사용
- [TypeScript and JavaScript Language Features](https://code.visualstudio.com/docs/languages/typescript) — VS Code 기본 제공, 별도 설치 불필요

설정 위치 우선순위는 저장소 `.vscode/settings.json` 및 권장 확장 → 개인 VS Code 설정이다. 저장소 설정 파일이 없으면 개인 설정에서 아래 옵션을 사용할 수 있다.

```json
{
  "editor.formatOnSave": true,
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": "explicit"
  },
  "typescript.tsdk": "frontend/node_modules/typescript/lib"
}
```

팀 저장소가 별도 포맷터나 ESLint 설정을 확정하면 개인 설정 대신 저장소 규칙을 따른다. 확장 프로그램 설치만으로 ESLint/Prettier 설정 파일을 임의 생성하지 않는다.

### 디버깅

- UI 디버깅은 브라우저 개발자 도구를 기본으로 사용한다.
- Network 탭에서 API URL, HTTP status, CORS 오류를 확인한다.
- Console이나 Network에서 Authorization header, Secret, Audio/Transcript 본문을 복사해 공유하지 않는다.
- Vite sourcemap과 브라우저 debugger를 사용할 수 있지만, 실제 Provider credential을 Frontend에서 시험하지 않는다.

## 자주 발생하는 문제

| 증상 | 확인 방법 |
|---|---|
| `node` 또는 `npm`을 찾지 못함 | 설치 후 새 PowerShell을 열고 `where.exe node` 확인 |
| Node 버전이 24가 아님 | 버전 관리 도구/Windows PATH 우선순위 확인 |
| `npm ci`가 lockfile 오류로 실패 | `package-lock.json`이 있는지 확인하고, lockfile을 임의 삭제하지 말고 의존성 변경 PR 확인 |
| 개발 서버가 API에 연결되지 않음 | `VITE_API_BASE_URL`, Backend 상태, Backend `ALLOWED_ORIGINS` 확인 |
| `.env.local` 변경이 반영되지 않음 | Vite 서버 재시작 및 변수명의 `VITE_` 접두사 확인 |
| VS Code와 터미널의 Node 버전이 다름 | VS Code 전체를 재시작한 뒤 통합 터미널에서 `node --version` 확인 |

## 준비 완료 확인

- `node --version`이 `v24`로 시작하고 `.nvmrc` 값이 `24`다.
- `npm ci`가 성공한다. 초기 scaffold에서 lockfile이 없을 때만 `npm install`을 사용한다.
- `npm run dev`로 Frontend가 열린다.
- `VITE_API_BASE_URL`로 Backend에 연결할 수 있다.
- `npm run build`, `npm run lint`, `npm run test` 명령이 프로젝트에 정의되어 있다.
- Frontend 환경설정과 bundle에 Provider Secret이 없다.

## 참고 문서

- [Node.js Downloads](https://nodejs.org/en/download)
- [Vite Guide](https://vite.dev/guide/)
- [React Learn](https://react.dev/learn)
- [VS Code TypeScript](https://code.visualstudio.com/docs/languages/typescript)

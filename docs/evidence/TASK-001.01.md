# 검증 증거

## 🧭 작업 정보

- 작업: `TASK-001.01` 저장소 기본 구조와 제외 규칙
- Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)
- 초기 기준: `d718167f4df00a84c073b688d141a43358cab9d9`
- 최신 `origin/master` 재정렬 기준: `aecf1a36f0d84178e75f0b1196238192bc8e98f9`
- 작업 브랜치: `codex/issue-1-repo-foundation`
- 실행 환경: Windows PowerShell, Git

## ✅ 검증 결과

- 구현 전 구조 검사: 예상한 누락 경로(`.gitignore`, FE/BE 및 ADR/Evidence 표식)를 찾아 종료 코드 1로 실패했다.
- 구조·제외 규칙·README 링크·후속 도구 경계 검사: 초기 커밋과 최신 기준 재정렬 후 모두 통과했다.
- 개발 추적표: PRD의 leaf TASK 78개 행을 확인했고, `TASK-001.01`은 `IN_PROGRESS / DEVELOPMENT`로 표시했다. Issue #64 `TASK-017.04` 링크를 포함하고 기존 설계 표와 `TASK-017.04` 설계 커서는 유지했다.
- 리뷰 피드백에 따라 workflow 필수 tracker 예외를 TASK 설계 문서와 Issue #1에 명시했다. 제품 요구사항과 기존 설계 데이터는 변경하지 않았다.
- `git diff --check`: 통과했다.
- `git status --short --ignored`: 변경된 작업 파일만 표시했고 기존 제품/설정 문서는 제외되지 않았다.
- FE/BE 매니페스트와 빌드 도구는 추가하지 않았다.

## 🔎 제외 규칙 확인

`git check-ignore --no-index --verbose`에서 다음 경로가 각각 예상 패턴에 매칭됐다.

| 경로 | 매칭 규칙 |
|---|---|
| `frontend/node_modules/probe/package.json` | `**/node_modules/` |
| `frontend/dist/index.html` | `/frontend/dist/` |
| `frontend/.vite/deps/probe.json` | `/frontend/.vite/` |
| `backend/.gradle/caches/probe.bin` | `/backend/.gradle/` |
| `backend/build/classes/probe.class` | `/backend/build/` |
| `.env.local`, `frontend/.env.development.local`, `backend/.env.local` | `**/.env.*` |

`.env`가 `**/.env`에 매칭되고, `.env.example`, `.env.development.example`, Frontend/Backend 환경 예제와 `docs/evidence/verification.md`는 무시되지 않음을 확인했다.

## 🧪 실행한 검사

- 저장소 구조 확인: `Test-Path`로 필수 파일·표식 경로를 검사했다.
- 제외 경로 확인: `git check-ignore --no-index --quiet <path>`를 생성 산출물/로컬 환경 경로에 적용했다.
- 추적 가능 경로 확인: 같은 명령에서 환경 예제와 Evidence 경로의 제외 여부가 모두 false임을 확인했다.
- README 링크 확인: 세 문서 경로가 실제로 존재하고 README에 기록되어 있음을 확인했다.
- 작업 경계 확인: `frontend/package.json`, Backend Gradle 빌드 파일이 없음을 확인했다.
- 변경 형식 확인: `git diff --check`를 실행했다.

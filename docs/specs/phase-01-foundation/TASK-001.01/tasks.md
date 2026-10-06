# 저장소 기본 구조와 제외 규칙 작업 목록

## 작업 식별 정보

- 작업: `TASK-001.01`
- Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)
- 선행 조건: 없음

## 테스트 우선 구현 단계

### 실패하는 저장소 검사 정의

- [x] `.gitignore`, `README.md`, `frontend/`, `backend/`, `docs/ADR/`, `docs/evidence/`가 있는지 확인한다. 초기 기대 결과는 하나 이상의 경로가 없는 것이다.
- [x] `git check-ignore --no-index --quiet frontend/node_modules/probe/package.json`을 실행한다. 초기에는 제외 규칙이 없으므로 종료 코드가 0이 아니어야 한다.
- [x] `frontend/dist/index.html`, `frontend/.vite/deps/probe.json`, `backend/.gradle/caches/probe.bin`, `backend/build/classes/probe.class`, `.env.local`, `frontend/.env.development.local`, `backend/.env.local`에도 같은 제외 검사를 한다. 초기에는 이 경로가 규칙에 포함되지 않아야 한다.
- [x] `.env.example`, `.env.development.example`, `docs/evidence/verification.md`를 `git check-ignore --no-index --quiet`으로 확인한다. 규칙을 추가한 뒤에도 제외되지 않아야 한다.
- [x] 이 작업 산출물에 앱 패키지/빌드 매니페스트가 포함되지 않는지 확인한다. `frontend/package.json`, `backend/build.gradle`, `backend/settings.gradle`을 만들지 않는다.
- [x] 개발 workflow에서 요구하는 PRD 개발 tracker를 추가하고 기존 설계 표와 설계 커서를 보존한다.

### 최소 저장소 기반 구현

- [x] `spec.md`에 정의한 제외 패턴과 환경 예제 허용 규칙만 추가한다.
- [x] 기존 PRD 및 FE/BE 설정 문서 링크가 있는 간결한 README를 추가한다.
- [x] Frontend, Backend, ADR, 증거 루트에 필요한 경우에만 `.gitkeep`을 추가한다.

### 통과 확인 및 증거 기록

- [x] 모든 경로 및 제외 검사를 다시 실행한다. `spec.md`의 완료 기준을 모두 만족해야 한다.
- [x] 제외 대상 예제마다 `git check-ignore --no-index --verbose <path>`를 실행해 일치한 패턴을 기록한다.
- [x] 환경 예제와 `docs/evidence/` 파일이 제외되지 않는지 확인한다.
- [x] `git status --short --ignored`를 확인해 기존 원본 Markdown이 보이고 비밀 값이 추가되지 않았는지 점검한다.
- [x] 정확한 명령 결과를 `docs/evidence/TASK-001.01.md`에 기록한다.

## 중단 조건

- 기존 로컬 파일에 비밀 값이 있고 새 규칙 때문에 추가될 수 있으면 내용을 출력하지 않는다. 파일 경로를 보고하고 내용 또는 이력을 변경하기 전에 결정을 요청한다.
- 요청된 제외 패턴이 PRD 원본, 설정 문서 또는 증거를 제외하면 검증 완료로 표시하기 전에 멈추고 충돌을 해결한다.

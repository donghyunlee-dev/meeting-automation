# FE/BE 독립 빌드 및 환경 샘플 테스트 계획

## 테스트 목표

Frontend와 Backend가 서로의 빌드에 의존하지 않고, 안전한 환경 샘플 경계를 유지하는지 검증한다.

- GitHub Issue: [#5](https://github.com/donghyunlee-dev/meeting-automation/issues/5)
- 선행 조건: TASK-001.02~001.04 구현 완료
- 자동 검사기: `node scripts/verify-secret-boundary.mjs`
- 검사기 단위 테스트: `node --test scripts/verify-secret-boundary.test.mjs`

## 자동 테스트

### 환경 샘플 계약

- 설정: `frontend/.env.example`, `backend/.env.example`을 검사기에 입력한다.
- 기대 결과: Frontend 파일에는 `VITE_API_BASE_URL=http://localhost:8080`만 존재한다. Backend 파일은 Backend 설정 가이드의 비밀 아닌 로컬 키를 제공하며 Provider credential 값은 비어 있다.
- 음성 대조: 테스트 fixture에서 credential 키에 실제 값 형태를 넣으면 실패한다.
- 증거: 각 fixture 결과와 종료 코드. 실제 환경값은 출력하지 않는다.

### 환경 샘플 credential 경계

- 설정: Frontend/Backend 환경 샘플을 검사한다. Backend credential 키는 `NOTION_TOKEN`, `CONFLUENCE_AUTH_TOKEN`, `OPENAI_API_KEY`, `SLACK_MEETING_WEBHOOK_URL`, `SLACK_ADMIN_WEBHOOK_URL`과 `EMAIL_PROVIDER` 외 `EMAIL_*`다.
- 기대 결과: Backend credential 값은 비어 있고 Frontend 샘플에는 `VITE_API_BASE_URL`만 존재한다.
- 음성 대조: credential 값을 채운 Backend 샘플과 금지 변수를 추가한 FE 샘플 fixture가 각각 실패한다.
- 명령: `node scripts/verify-secret-boundary.mjs`
- fixture 명령: `node --test scripts/verify-secret-boundary.test.mjs`
- 증거: 통과/실패 assertion과 파일 경로만 기록하고 매칭된 값을 출력하지 않는다.

### 개인 환경 파일 ignore

- 절차: `git check-ignore frontend/.env.local backend/.env.local` 및 `.env.example`의 git 추적 상태를 확인한다.
- 기대 결과: 개인 파일 경로는 ignore 처리되고 환경 샘플은 추적 가능하다.
- 증거: 명령 결과와 적용된 ignore 패턴.

### 독립 Frontend 빌드

- 실행 위치: `frontend/`
- 명령:

```powershell
npm ci
npm run test
npm run lint
npm run build
```

- 기대 결과: 각 명령이 종료 코드 0으로 끝난다. Backend 설치/빌드 명령을 호출하지 않고 `frontend/dist/`를 생성한다.
- 증거: Node/npm 버전, 명령, 종료 코드.

### 독립 Backend 빌드

- 실행 위치: `backend/`
- 명령:

```powershell
./gradlew clean build
```

Windows PowerShell에서는 `./gradlew.bat clean build`를 사용한다.

- 기대 결과: 종료 코드 0. Frontend 의존성 설치나 빌드 없이 Backend 산출물을 생성한다.
- 증거: Java/Gradle 버전, 명령, 종료 코드.

## 수동 QA

- Frontend 설정 샘플을 읽고 개발자가 `VITE_API_BASE_URL`을 로컬 Backend 주소로 조정할 수 있는지 확인한다.
- Backend 설정 샘플이 참고용이며 Spring Boot dotenv 자동 로딩을 의미하지 않는지 확인한다.
- 검사기 출력과 샘플 점검 결과에 실제 credential 값이 기록되지 않았는지 확인한다.
- `docs/evidence/TASK-001.05.md`에 비민감 결과를 기록한다.

## 릴리스 전용 검사

운영 Secret 저장소, 배포 환경변수, 배포 산출물, 로그 또는 실제 Provider 연결 검증은 이 TASK의 수용 검사 범위가 아니다. 해당 운영 경계는 `TASK-017.01` 및 배포 작업에서 확인한다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| 환경 샘플이 존재하고 계약을 충족 | 환경 샘플 계약 검사 |
| 실제 credential을 샘플에 두지 않음 | credential 값 fixture 실패 및 Secret 경계 검사 |
| Frontend 환경 샘플에 Backend Secret이 없음 | 환경 샘플 경계 검사 |
| 개인 환경 파일 제외 | `git check-ignore` 및 `.env.example` 추적 확인 |
| FE 독립 빌드 | Frontend 전체 명령 실행 |
| BE 독립 빌드 | Backend `clean build` 실행 |

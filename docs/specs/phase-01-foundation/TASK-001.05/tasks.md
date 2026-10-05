# FE/BE 독립 빌드 및 환경 샘플 작업 목록

## 작업 식별 정보

- 작업: `TASK-001.05`
- 선행 조건: Issue #2, #3, #4 완료
- GitHub Issue: [#5](https://github.com/donghyunlee-dev/meeting-automation/issues/5)

## 테스트 우선 구현 단계

### 기반 확인

- [ ] 선행 Issue #2~#4가 완료되고 Frontend lockfile/npm 명령, Backend Wrapper/빌드 명령이 존재하는지 확인한다. 결과: 독립 빌드 기준을 확정한다.
- [ ] `.gitignore`, Frontend build 출력 경로 및 Backend 설정 가이드를 확인한다. 결과: 개인 환경 파일과 검사 대상을 정한다.

### 실패 테스트 작성

- [ ] 테스트가 import할 최소 `scripts/verify-secret-boundary.mjs` 진입점과 `inspectEnvSamples` stub을 둔다. 결과: 테스트가 모듈 오류 없이 실행된다.
- [ ] `scripts/verify-secret-boundary.test.mjs`에 환경 샘플 검사 테스트를 추가한다. 정상 샘플은 통과하고, credential 키에 실제 값이 들어간 샘플은 실패한다.
- [ ] Frontend 환경 샘플이 `VITE_API_BASE_URL` 외의 변수를 포함한 fixture는 실패하도록 테스트한다.
- [ ] `node --test scripts/verify-secret-boundary.test.mjs`로 fixture를 실행한다. 결과: 검사기 미구현으로 기대한 assertion 실패가 확인되며 구문/모듈 오류는 유효한 red가 아니다.

### 최소 구현

- [ ] `frontend/.env.example`에 `VITE_API_BASE_URL=http://localhost:8080`만 둔다. 결과: Frontend에 공개 URL 설정만 문서화된다.
- [ ] `backend/.env.example`에 설정 가이드의 비밀 아닌 로컬 키와 빈 Provider credential 키를 둔다. 결과: 실제 credential이 없는 설정 참고 파일이 제공된다.
- [ ] 개인 환경 파일 ignore 규칙을 확인하고 부족한 패턴만 보완한다. 결과: FE/BE `.env.local`은 무시되고 예시 파일은 추적 가능하다.
- [ ] `scripts/verify-secret-boundary.mjs`를 Node 내장 API로 구현한다. 결과: 샘플의 변수명과 Backend credential 키의 값이 비어 있는지 검사한다.
- [ ] 검사기와 fixture 테스트를 재실행한다. 결과: 정상은 통과하고 각 위반은 실패한다.

### 독립 빌드와 증거

- [ ] Frontend에서 `npm ci`, `npm run test`, `npm run lint`, `npm run build`를 실행한다. 결과: 각 명령의 종료 코드가 0이다.
- [ ] Backend에서 `./gradlew clean build`를 실행한다. 결과: 종료 코드가 0이다.
- [ ] 두 환경 샘플을 검사한다. 결과: Backend credential 값이 없고 Frontend 샘플에는 공개 API 주소만 있다.
- [ ] 결과와 비민감 증거를 `docs/evidence/TASK-001.05.md`에 기록한다.

## 중단 조건

- [ ] 선행 빌드 명령이나 출력 경로가 문서 계약과 다르면 임의로 toolchain을 바꾸지 않고 선행 구현과의 차이를 기록한다.
- [ ] 설정 가이드에 정의되지 않은 새 runtime 키가 필요하면 키 이름과 사용처를 이슈에서 결정한 뒤 샘플에 추가한다.

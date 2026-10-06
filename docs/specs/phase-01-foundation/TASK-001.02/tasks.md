# Frontend 도구 체계 초기화 작업 목록

## 작업 식별 정보

- 작업: `TASK-001.02`
- 선행 조건: `TASK-001.01`, [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1) 완료
- GitHub Issue: [#2](https://github.com/donghyunlee-dev/meeting-automation/issues/2)

## 테스트 우선 구현 단계

### 테스트 실행 기반 준비

- [x] #1 완료를 확인한 뒤 Node.js 22.12 이상과 npm 버전을 기록한다. (#1 완료 확인)
- [x] `plan.md`의 파일과 실행 기반을 준비하고 제목 없는 최소 `App`을 둔다. 결과: Vitest가 `App`을 import하고 렌더할 수 있다.
- [x] `dev`, `build`, `lint`, `test` 스크립트를 등록하고 Node 버전 기준과 잠금 파일을 만든다. 결과: `npm run`에 네 스크립트가 보인다.

### 실패 테스트 작성

- [x] 실행 기반 준비 후 `App.test.tsx`에서 제목 `Meeting Automation`을 가진 `h1`을 검증한다.
- [x] `npm run test -- src/App.test.tsx`를 실행한다. 결과: 테스트가 수집되고 제목 부재 때문에 실패한다.
- [x] import, 설정, 의존성 오류가 있으면 먼저 실행 기반을 보완하고 다시 실패 이유를 확인한다. 설정 오류 없이 제목 assertion에서 실패했음을 확인했다.

### 최소 도구 체계 및 구현

- [x] 실패 증거 확보 후 `App`에 제목을 추가하고 `main.tsx`에서 렌더한다. 결과: 같은 대상 테스트가 통과한다.
- [x] 대상 테스트 통과 후 scaffold 샘플을 정리한다. 결과: 기본 앱 제목만 표시되며 불필요한 샘플 참조가 없다.

### 통과 확인 및 증거 기록

- [x] `npm ci`로 잠금 파일 기반 설치를 확인한다.
- [x] `npm run test`와 `npm run lint`가 통과한다.
- [x] `npm run build`가 성공하고 빌드 산출물이 생성된다.
- [x] `npm run dev` 실행 후 브라우저에서 기본 앱이 표시되는지 확인한다.
- [x] Frontend 코드와 환경 설정에 Secret이 없는지 확인한다.
- [x] 명령, 버전, 결과 및 수동 확인을 `docs/evidence/TASK-001.02.md`에 기록한다.

## 중단 조건

- PRD 기준 버전 조합이 설치 또는 빌드 단계에서 호환되지 않으면 Major 버전을 임의 변경하지 말고 실패 로그의 비민감 부분과 선택지를 기록해 결정을 요청한다.
- Node.js 22 환경을 사용할 수 없어 검증이 불가능하면 다른 Node 버전의 결과를 통과로 기록하지 않는다.
- Frontend용으로 Secret을 요구하는 설계가 발견되면 해당 값을 추가하지 않고 Backend 책임 경계와 충돌을 기록한다.

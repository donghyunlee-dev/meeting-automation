# PRD 개발 스프린트 운영 기준

이 문서는 PRD 구현 세션에서 반복 확인을 줄이고, 사용자가 정한 시간 안에 완료 가능한 범위를 합의하기 위한 공통 실행 기준이다. PRD 개발 스킬, 현재 `AGENTS.md`, task SDD가 우선하며 이 문서는 그 기준을 빠르게 적용하기 위한 체크리스트다.

## 목표와 범위

- 시작 시 사용자가 기대하는 결과를 구분한다: 코드 구현, 사용자가 직접 시험할 수 있는 흐름, 실 Provider 검증, 실기기/현장 검증.
- 사용자가 실환경 검증을 맡겠다고 하면 이번 개발 목표에서 실계정 Provider 전달, 실기기/회의실 QA를 제외한다. 변경 코드의 focused 자동 테스트, exact-head review, 필수 CI는 계속 merge gate로 둔다.
- 실환경 QA 제외는 사용자의 수동 검증을 미루는 뜻이며, PRD의 코드 구현·화면 흐름·오류 처리·보안 요구나 개발 커서를 제외하는 뜻이 아니다. 정해진 시간 안에 전체 PRD 개발이 목표라면 작업 목록을 순서와 선행 관계에 따라 예산에 맞춰 배분하고, 미완료 기능은 `DONE`으로 표시하지 않는다.
- PRD tracker에서 현재 커서와 선행 관계를 확인하고, 미완료 작업을 기능 구현·사용자 흐름·운영/보안·실환경 QA로 분류한다. 선행 관계를 건너뛰거나 작업 상태를 `DONE`으로 오표기하지 않는다.
- 시작 전에 요청이 전체 PRD인지 특정 deliverable인지 확정하고, 전체 PRD 요청이면 모든 남은 TODO의 선행 관계와 구현·focused 검증·review/CI·merge·Issue·evidence 시간을 먼저 합산한다. 예상 총량이 시간 예산을 넘으면 이를 초기에 알리고 시간 내 진행 순서와 예상 완료 개수를 제시하되, 사용자의 전체 목표를 임의로 축소하거나 미완료 항목을 `DONE`으로 표시하지 않는다.
- 실행 중에는 정한 시간 상자마다 계획/실제/잔여 시간을 비교하고, 파일 탐색·버전 확인·CI/review polling은 변경점이나 대기 상태가 바뀔 때만 반복한다. 시간 예산 종료 전까지 순차 의존성을 지키며 계속 진행하고, 끝날 때 완료·미완료 커서와 재개 지점을 기록한다.

## 프로젝트 고정 기준

- Backend는 Java 25와 Gradle Wrapper를 사용한다. Windows 개발 검증은 `backend`에서 `./gradlew.bat test --tests <fully.qualified.TestClass>`로 변경 코드 및 직접 영향받는 테스트만 실행한다. 전체 Backend suite, `clean build`, 무관한 테스트는 개발자 검증에서 실행하지 않는다.
- Frontend는 `frontend`에서 변경 영역의 Vitest 파일, ESLint 대상 파일, `npm run build`를 실행한다. 명령과 테스트 수는 PR 본문 및 SDD 결과와 일치시킨다.
- task 완료에는 정확한 PR head의 테스트·review·필수 CI, merge, Issue 정리, evidence, PRD 갱신이 필요하다. 코드 push로 head가 바뀌면 이전 테스트/review 증거는 다시 확인할 때까지 무효다.
- 완료 문서를 commit한 뒤 `workflow.mjs gate-finalize`를 `master` push 전에 실행한다. Gate는 `origin/master..HEAD`의 변경 파일을 확인하므로 push 후에는 차이가 없어 통과하지 않는다. Gate 통과 후 push하고 원격에 PRD/evidence가 있는지 확인한다.
- Backend task 완료를 위해 로컬 서비스를 띄우거나 별도 acceptance/QA 담당자를 실행하지 않는다. 실 Provider/기기 테스트는 사용자에게 인계할 때 명확히 제외 범위를 적는다.

Backend 검증 전 `java -version`과 `./gradlew.bat --version`의 JVM이 모두 Java 25인지 확인한다. Windows 프로세스의 `JAVA_HOME`이 오래된 JDK(예: 17)를 가리키면 해당 터미널 프로세스만 설치된 JDK 25로 맞춘 뒤 Wrapper 버전을 재확인한다. 시스템 전체 환경을 불필요하게 변경하지 않는다.

Frontend 검증 전 `frontend/package.json`의 `engines.node`와 `node --version`을 대조한다. 현재 범위는 Node `>=22.12.0 <23`이다. 기본 실행기가 범위 밖이면 해당 명령 프로세스에서만 지원 버전을 선택하고, 설치·전환이 불가능하면 검증 결과에 버전 차이를 명시한다. 테스트와 build가 우연히 실행된 것만으로 지원 버전 준수라고 간주하지 않는다.

## 3시간 작업 창 운영

| 경과 | 작업 |
|---|---|
| 0–15분 | PRD/GitHub/checkpoint 대조, 선행 관계 확인, 목표 사용자 흐름과 제외 QA 명시, 작업량 산정 |
| 15–125분 | 가장 중요한 실행 가능한 기능을 구현한다. 테스트를 먼저 추가하고, PR 준비 즉시 review와 CI를 시작한다. |
| 125–160분 | 변경된 동작만 재검증하고 exact-head review/필수 CI를 마친다. 결과가 불충분하면 범위를 줄이며 완료로 표시하지 않는다. |
| 160–180분 | 통과한 PR 병합, Issue 정리, evidence/PRD 갱신과 원격 반영 확인을 한다. 20분 buffer를 사용해 새 범위를 추가하지 않는다. |

- 15분마다 예상 완료시각과 실제 단계를 비교한다. 20분 넘게 밀리면 남은 작업을 재산정하고, 범위를 좁히거나 다음 시간 창으로 넘긴다.
- 독립적인 상태 읽기와 파일 조회는 한 번에 묶는다. CI/review를 시작한 뒤에는 상태 변경 또는 시간이 지난 시점에 한 번 확인하고, 같은 상태를 반복 조회하지 않는다.
- 가능한 병렬화는 현재 PRD task 내부의 독립 Frontend/Backend 작업, 구현과 동시에 진행되는 read-only 코드/요구사항 확인, PR 생성 후 CI와 review다. 종속 PRD task를 임의로 병렬화하거나 별도 worktree를 만들지 않는다.
- 진행 표에는 `TASK`, `estimate`, `actual`, `remaining`, `branch/head`, `tests`, `review`, `CI`, `merge`, `Issue/PRD/evidence`를 기록한다. 시간이 다 되면 실제 통과한 게이트까지만 보고한다.

## 재사용할 회귀 지식

- 비동기 결과와 version guard는 세션/요청 식별자에 묶는다. 다른 session의 높은 version이 새 session의 낮은 version을 막지 않게 하고, 이전 session의 응답이 새 화면을 덮지 않도록 상태와 pending key를 분리/초기화한다. session A(version 40) → B(version 1) 전환과 B의 API 요청 대상을 회귀 테스트한다.
- SDD/PR 설명의 테스트 수와 실행 결과가 달라지면 PR 본문, evidence, PRD tracker를 같이 고친다. exact-head 해시를 모든 검증 기록에 명시한다.
- 저장소의 `backend-validation.yml`은 `backend/**` 변경에만 실행된다. Frontend-only PR에서는 표시된 wrapper check가 현재 PR에 적용되는 필수 CI인지 저장소 설정과 함께 확인하고 Backend 검증이 실행된 것처럼 기록하지 않는다.
- 같은 인증 계정이 PR 작성자이기도 하면 GitHub가 formal review를 거부할 수 있다. 이 경우 exact-head 독립 리뷰의 결과와 제한을 PR 대화 및 evidence에 명시하고, formal approval이 있었다고 표현하지 않는다.
- PowerShell에서 예상 실패 build의 출력을 native-command pipeline으로 검사하면 긴 Rollup 진단의 줄바꿈/서식 때문에 규칙명이 잘린 것처럼 보일 수 있다. 실패를 검증할 때는 프로세스의 원시 stdout/stderr와 exit code를 함께 캡처하고, 보고할 때만 canary 값을 마스킹한다. UI에 잘린 텍스트만 보고 코드를 반복 수정하지 않는다.

## Frontend regression note

- React StrictMode can replay effects in development. Share only in-flight GETs for the same URL/key, then remove the entry on resolve/reject so retries and revisits can fetch again. `limit=5` and default-100 need distinct keys; include a StrictMode replay regression test.

# 🧪 App Config 및 Template API 검증 기록

## 📌 완료 정보

| 항목 | 확인 결과 |
|---|---|
| TASK / Issue | TASK-004.02 / [Issue #15](https://github.com/donghyunlee-dev/meeting-automation/issues/15) |
| PR | [PR #93](https://github.com/donghyunlee-dev/meeting-automation/pull/93) |
| 테스트·리뷰·QA head | `d43543c4bca27db96c00a4e1930fbff16ca7ef86` |
| master 병합 commit | `c2b06eb82bfaa155f1025fd47d16097fe6d7143a` |
| 병합 시각 | 2026-10-09 22:01 KST |
| 다음 개발 대상 | TASK-004.03 |

## 🛠️ 구현 내용

`GET /api/v1/app-config`는 공개 가능한 다섯 설정 영역을 반환하고 credential 값을 응답 DTO에 포함하지 않는다. `GET /api/v1/templates`는 classpath의 `default.md`, `project.md` metadata를 계약 순서로 반환한다. 필요한 resource가 없거나 읽히지 않으면 부분 목록 대신 공통 안전 오류 응답을 반환한다. 설정 secret, Template 성공 응답, resource 실패 동작을 검증하는 테스트와 backend CI workflow를 추가했다.

## ✅ 자동 검증

PR exact head에서 GitHub Actions Backend Validation run [#7](https://github.com/donghyunlee-dev/meeting-automation/actions/runs/37932076079)이 성공했다. Java 25를 사용했고 아래 두 단계가 모두 PASS했다.

```text
./gradlew test
./gradlew clean build
```

테스트 XML과 실행 가능한 backend JAR를 같은 workflow run의 artifact로 보존했다. artifact ID `11616596962`는 exact head `d43543c4bca27db96c00a4e1930fbff16ca7ef86`에서 생성됐고, archive SHA-256은 `aec8bec01dabb54e29bdc080e95ea756f61763428099d5424afb74650955c22f`다. 로컬 `backend` 디렉터리에서도 `TEMP`/`TMP`를 `backend/build/jvmtmp`로 지정한 `gradlew.bat test`가 성공했다. 동일 설정으로 재시도한 로컬 `clean build`는 실행 중 1분 이상 출력이 없어 중단했으며, 해당 명령의 exact-head 성공 근거는 CI run #7이다.

## 🌐 실제 백엔드 HTTP QA

QA는 mock 응답이나 route interception 없이 CI가 exact head에서 만든 JAR를 실행하고 실제 HTTP 응답을 확인했다. 먼저 기본 설정으로 `127.0.0.1:18081`에 기동해 health와 미설정 provider 응답을 확인했다. 이어 QA sentinel 환경 변수로 `127.0.0.1:18082`에 별도 기동했다.

| 확인 | 결과 |
|---|---|
| `GET /actuator/health` | PASS — HTTP 200, `UP` |
| 기본 설정 `GET /api/v1/app-config` | PASS — 다섯 영역 반환, document provider `null`, `configured=false` |
| 기본 설정 `GET /api/v1/templates` | PASS — `default` 다음 `project`, 기대한 한국어 이름과 version `1.0.0` |
| secret sentinel 설정의 app-config 응답 | PASS — document provider 설정 및 email 활성 상태가 반영되고, 전달한 Notion/Gmail secret sentinel은 응답에 없음 |
| secret sentinel 설정의 templates 응답 | PASS — 두 고정 Template ID 반환 |
| 서버 종료 | PASS — 두 QA 서버 모두 정상 종료 |
| 별도 QA agent의 localhost 접근 | 제한 — 해당 agent 실행 namespace가 loopback socket을 거부하고 Chrome도 `ERR_BLOCKED_BY_CLIENT`를 반환. mock으로 대체하지 않았으며 위 leader의 exact-head JAR HTTP 검증으로 판정함 |

## 🔍 Backend 기동 문제 진단

기본 Codex Windows 실행 환경에서는 Gradle 또는 Spring 시작 중 Java NIO Windows selector 초기화가 실패했다. 대표 오류는 `java.io.IOException: Unable to establish loopback connection`이며, stack trace는 `sun.nio.ch.WEPollSelectorImpl`의 selector pipe 초기화 경로를 가리켰다. 이 경로는 Windows에서 selector 알림용 pipe를 구성한다. 같은 repository와 JDK에서 `TEMP` 및 `TMP`를 workspace 안의 `backend/build/jvmtmp`로 바꾸자 테스트와 실제 Spring Boot JAR 기동이 성공했다. 따라서 이 실행에서 확인한 원인은 애플리케이션 backend 설정이나 사용자의 Windows 설정이 아니라 기본 Codex executor의 임시 디렉터리/loopback 실행 경계다. 사용자 PC 자체의 원인이라고 단정하지 않는다.

재현 가능한 PowerShell 실행 예시는 다음과 같다.

```powershell
$env:TEMP = "$PWD\build\jvmtmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
..\gradlew.bat test
```

JAR를 실행할 때도 같은 터미널에서 환경 변수를 지정하면 된다. 이 조정은 해당 프로세스의 임시 경로만 바꾸며 source, JDK, Windows 설정을 변경하지 않는다.

## 🧾 리뷰와 병합

최종 리뷰 [#5470206707](https://github.com/donghyunlee-dev/meeting-automation/pull/93#pullrequestreview-5470206707)은 exact head에서 PASS했다. 이전 P1 지적 두 개는 수정되고 해결됐으며 열린 리뷰 스레드는 없다. PR은 `master` 대상으로 exact head를 고정해 병합했고, 실제 merge SHA는 `c2b06eb82bfaa155f1025fd47d16097fe6d7143a`다. Issue #15는 완료 이유로 닫고 `status:in-progress` label을 제거했다.

PRD 개발 현황을 DONE으로 갱신했으며 다음 커서를 TASK-004.03으로 이동했다.

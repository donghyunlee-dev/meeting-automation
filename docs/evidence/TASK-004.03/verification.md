# 🧪 New Meeting 입력 UI 검증 기록

## 📌 완료 정보

| 항목 | 확인 결과 |
|---|---|
| TASK / Issue | TASK-004.03 / [Issue #16](https://github.com/donghyunlee-dev/meeting-automation/issues/16) |
| PR | [PR #94](https://github.com/donghyunlee-dev/meeting-automation/pull/94) |
| 테스트·리뷰·브라우저 QA head | `b043f1aabda9928d2ebb87581ab639de41c9f6c4` |
| 리뷰 | PASS — [review #5471609271](https://github.com/donghyunlee-dev/meeting-automation/pull/94#pullrequestreview-5471609271); 이전 P2 수정 확인, 열린 리뷰 스레드 없음 |
| 병합 commit | `cea7902fb10cb0ea44c3c845cd9636ad0ebb2f0c` |
| 완료 시각 | 2026-10-10 00:04 KST |
| 다음 개발 대상 | TASK-004.04 |

## 🛠️ 구현 범위

`/meetings/new`에서 제목, Template, 기존 roster 참석자를 입력·선택하고 필수 입력을 검증한다. API-001 company timezone, API-002 Template, API-003 Participant를 각각 읽으며 로딩·오류·빈 상태와 재시도를 처리한다. 입력값과 선택값은 조회 실패/재시도 중 보존된다. 유효 데이터는 `{title, templateId, participantIds, timezone}` callback으로 전달한다. 이 화면은 API-006 Session 생성 요청을 보내지 않는다.

## ✅ 자동 검증

exact PR head에서 아래 명령이 통과했다.

```text
npm run test   — 22 passed
npm run lint   — passed
npm run build  — passed, 108 modules transformed
```

실행 Node는 v24.19.0이며 package engine 조건은 `>=22.12.0 <23`이다. 이 검증 환경에서 지정된 Node 22 계열과 다르다는 사실을 기록하며 package 조건을 바꾸지 않았다. participant 재조회 중 제거/선택을 잠그는 동작은 회귀 테스트로 확인했다. 유효 payload의 trim, 선택된 ID, timezone과 GET 세 건만 호출되고 API-006 POST가 없음을 fixture 기반 테스트에서 확인했다.

## 🌐 실제 백엔드 및 브라우저 QA

Chrome에서 exact-head production build를 360×740 viewport로 열었다. 임시 same-origin Vite proxy는 실제 TASK-004.02 backend JAR로 전달했으며 응답을 모의하지 않았다.

| 확인 | 결과 |
|---|---|
| 실제 `GET /actuator/health` | PASS — HTTP 200, `UP` |
| 실제 `GET /api/v1/app-config` | PASS — HTTP 200, company timezone `Asia/Seoul`; document provider `null`, `configured=false` |
| 실제 `GET /api/v1/templates` | PASS — HTTP 200, `default.md`와 `project.md`; 화면에서 `default.md` 기본 선택 |
| 실제 `GET /api/v1/participants` | HTTP 422 `DOCUMENT_STRUCTURE_NOT_FOUND`; 계약상 필수 Participants 문서 구조가 없을 때 반환되는 오류이며 응답 본문을 확인함 |
| API-003 오류 UI | PASS — 안전한 오류/재시도 상태를 표시하고 Session 제출을 비활성화함. 참가자를 생성하거나 쓰지 않음 |
| 화면 너비·하단 CTA | PASS — 360px에서 가로 overflow 없음 (`scrollWidth=clientWidth=345`), CTA는 하단 navigation 위에 위치 |
| 조작 대상 크기 | PASS — 측정한 input/select/retry/CTA/navigation target이 모두 44px 이상 |
| 키보드 조작·draft | PASS — 제목→Template→재시도 버튼 순으로 Tab 이동, Space로 재시도, 제목 draft 보존 |
| 참가자 정상 선택 흐름 | fixture 기반 컴포넌트 테스트 PASS; 이번 실제 환경에는 provider/Participants 구조가 없어 실 roster 선택은 하지 않음 |

실제 Participant roster를 브라우저에서 보려면 backend에 유효한 Document Provider와 해당 root, 필수 `Participants` child 구조 및 유효한 provider credential이 설정되어야 한다. 이번 요청은 해당 외부 계정을 설정하지 않았으며, API의 실제 422를 성공 응답이나 모의 roster로 바꾸지 않았다.

## 🔍 백엔드 기동 실패 진단 및 재시도

이전 exact-JAR 진단에서 기본 Codex Windows 실행 경로의 Java NIO Windows selector 초기화가 `java.io.IOException: Unable to establish loopback connection`으로 실패했다. stack trace는 `sun.nio.ch.WEPollSelectorImpl`의 selector pipe 초기화 경로였다. `TEMP`와 `TMP`를 workspace의 `backend/build/jvmtmp`로 지정하면 Gradle 테스트와 Spring Boot JAR 기동이 가능하다는 것을 TASK-004.02 검증에서 확인했다.

이번에도 동일 JAR를 해당 임시 경로로 재기동했다. 기본 sandbox에서는 `127.0.0.1:18083`에 대한 PowerShell HTTP 요청이 Windows socket 접근 거부로 실패했다. 승인된 로컬 실행 경로에서 같은 JAR를 띄우자 Spring Boot/Tomcat이 포트 18083에서 실제로 시작했고 health/config/template/participant 요청이 응답했다. 따라서 관찰된 차이는 실행 격리의 loopback 권한 경계이며, 이 증거만으로 사용자의 개인 PC나 프로젝트의 backend 설정이 고장 났다고 판단하지 않는다.

이 저장소에서 수동 실행 시 backend 디렉터리에서 다음처럼 임시 경로를 지정할 수 있다.

```powershell
$env:TEMP = "$PWD\build\jvmtmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
..\gradlew.bat test
```

## 🧾 GitHub 정리

PR #94는 exact head `b043f1aabda9928d2ebb87581ab639de41c9f6c4`에서 `master`를 대상으로 병합했고 실제 merge commit은 `cea7902fb10cb0ea44c3c845cd9636ad0ebb2f0c`다. 리뷰는 exact head에서 PASS했고 이전 P2 thread는 해결됐다. Issue #16은 완료 이유로 닫았고 `status:in-progress` label을 제거했다. PR QA 설명은 실제 422의 정확한 코드와 API 문서 계약에 맞게 정정했다.

PRD 개발 표를 DONE으로 갱신했고 다음 개발 커서를 TASK-004.04로 이동했다.

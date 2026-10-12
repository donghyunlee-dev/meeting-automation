# 🔎 문서 서비스 간 표준 자료 export와 import 검증 계획

> TASK-022.04 · PRD v1.9.0
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 🧪 자동 검증 결과

| 사례 | 준비·입력 | 기대 결과 | 상태 및 실행 증거 |
|---|---|---|---|
| TRANSFER-ROUNDTRIP | Notion↔Confluence synthetic fixture 및 service port fixture | title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일 | **PASS** — Task 1 codec 22 tests (`task-1-review-coverage.log`); Task 2 Provider/API 관련 109 tests (`task-2-review-focused.log`); Task 3 service 17 tests (`task-3-review-fix-focused.log`). |
| TRANSFER-PARTICIPANTS | 동명이인·같은 email·새 target IDs | source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용 | **PASS** — adapter/API 109 tests (`task-2-review-focused.log`); stale participant-map preflight regression 포함 service 17 tests (`task-3-review-fix-test.log`). |
| TRANSFER-FAILURE-DOC | 정상 문서와 실패 종료 문서 | 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음 | **PASS** — codec/adapter/service focused suites (`task-1-review-coverage.log`, `task-2-review-focused.log`, `task-3-review-fix-focused.log`). |
| TRANSFER-PAGINATION | 다중 페이지 및 100개 목록 경계 | manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음 | **PASS** — provider pagination 및 opaque cursor 사례가 포함된 adapter/API focused suite, 109 tests (`task-2-review-focused.log`). |
| TRANSFER-CONFLICT | 중복 외부 Session ID·깨진 참조·unknown schema·다른 target digest | preflight/충돌 오류; 덮어쓰기·조용한 누락 없음 | **PASS** — service conflict/preflight 사례와 stale participant-map 무기록 회귀 (`task-3-review-fix-test.log`; 17 focused tests in `task-3-review-fix-focused.log`). |
| TRANSFER-IDEMPOTENCY | 생성 응답 유실 후 marker 조회 | 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음 | **PASS** — service exact reuse 및 ambiguous outcome 사례, 17 focused tests (`task-3-review-fix-focused.log`). |

Fixture는 synthetic 문서/참석자를 사용하고 Provider HTTP 요청은 adapter/API 테스트에서 stub 처리한다. 실제 Provider 계정이나 자격 증명 사용은 보고되지 않았다. 실패 테스트와 중간 RED/GREEN 기록은 각 task report 및 `.superpowers/sdd/plan/task-*-*.log`에 보관한다.

### 현재 구현 브랜치의 최종 자동 검증

아래 명령은 Windows에서 backend 디렉터리에서 실행했다. Java는 프로세스에만 설정한 cached Temurin 25를 사용했다.

```powershell
$env:JAVA_HOME='C:\Users\karid\AppData\Local\Temp\meeting-automation-temurin25\jdk-25.0.4.1+1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test --tests com.meetingautomation.document.transfer.DocumentTransferServiceTests
.\gradlew.bat test
.\gradlew.bat clean build
```

| 명령 | 결과 | 로그 |
|---|---|---|
| `test --tests com.meetingautomation.document.transfer.DocumentTransferServiceTests` | PASS, 17 tests | `task-3-review-fix-focused.log` |
| `test` | PASS, 277 tests, 0 failures | `task-3-review-fix-full.log` |
| `clean build` | PASS, BUILD SUCCESSFUL, 8 tasks executed | `task-3-review-fix-build.log` |

각 task별 추가 검증은 다음과 같다: Task 1 `TransferCodecTests` 22 tests 통과 (`task-1-review-coverage.log`); Task 2 adapter/provider/API focused suite 109 tests, full suite 260 tests 및 `clean build` 통과 (`task-2-review-focused.log`, `task-2-review-full.log`, `task-2-review-clean-build.log`). 이 결과는 서로 다른 구현 시점의 검증이며 위의 Task 3 로그가 이후 전체 백엔드 검증 결과다. JVM class-sharing 경고와 기존 deprecated API 요약이 출력됐지만 해당 명령은 성공했다.

## 💻 반복 가능한 명령

Windows에서는 backend 디렉터리의 Gradle Wrapper를 사용한다. macOS/Linux에서는 backend에서 `./gradlew test`, `./gradlew clean build`를 실행한다.

## 👀 수동 Provider QA — 미완료

- [ ] 두 Provider 테스트 공간에 표준 synthetic 회의록과 roster를 준비한다.
- [ ] 양방향 복사 후 target read-back으로 내용을 확인한다.
- [ ] source 첨부/댓글/이력을 보존했다고 주장하지 않는다.

실제 Provider QA 결과는 아직 없으며, 자동 fixture 테스트만으로 실계정 동작을 확인했다고 보지 않는다.

## 📋 증거와 합격 조건 — 리더 확인 대기

- [ ] 정확한 PR head SHA와 test/review/QA 결과를 확인한 후 `docs/evidence/TASK-022.04/verification.md`를 작성한다.
- [ ] 적용 QA, merge SHA, GitHub Issue cleanup 및 PRD DONE 기록을 확인한다.

Evidence에는 각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약 및 검증 불가 조건만 기록한다. 필수 사례 미충족은 DONE이 아니며 Provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

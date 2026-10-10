# 🔎 자료 이전과 활성 문서 서비스 전환 검증 계획

> TASK-022.05 · PRD v1.9.0
> GitHub Issue: [#100](https://github.com/donghyunlee-dev/meeting-automation/issues/100)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| SWITCH-COPY | 완전한 source/draft, COPY_ALL | 복사·참조·digest 검증 후 한 번만 활성화, 원본 삭제와 Email/Slack/STT 호출 0건 |
| SWITCH-EMPTY | START_EMPTY 명시 확인 | 원본 보존과 새 목록/빈 roster 안내, 준비된 대상만 활성화 |
| SWITCH-BUSY | CREATED/RECORDING/PROCESSING/REVIEW Session 및 pending write | 409 busy, 동시 Session 생성과 switch 중 정확히 하나만 lock 획득 |
| SWITCH-FAIL-RESTART | N번째 복사 실패·재시작·응답 유실 | source active 유지, INTERRUPTED 표시, 수동 재개 시 완료 item 재사용 |
| SWITCH-SOURCE-EDIT | preflight 뒤 원본 직접 수정/추가/삭제 | 전환 완료 거절 및 재검증 요구; 변경 전 active 유지 |
| SWITCH-CANCEL-VERSION | 취소·동시 두 전환·stale config version | 안전 지점 취소, 부분 target 보존, 412/409 충돌, 새 active cache와 ID 재조회 |
| SWITCH-CLEANUP | 완료/취소와 30일 journal 만료 | 불필요 credential 제거, 본문 spool 없음, 비민감 summary/map만 보존 후 정리 |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

backend에서 Windows `.\gradlew.bat test`, `.\gradlew.bat clean build`; macOS/Linux `./gradlew test`, `./gradlew clean build`를 실행한다.

## 👀 수동 QA

로컬 단일 Backend를 중단·재시작해 실패 이전을 수동 재개하고 두 브라우저의 전환/회의 시작 경쟁을 재현한다. 앱 밖 원본 편집을 감지하고 원본 링크가 계속 열리는지 확인한다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.05/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

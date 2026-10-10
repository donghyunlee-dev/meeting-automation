# 🔎 문서 서비스 간 표준 자료 export와 import 검증 계획

> TASK-022.04 · PRD v1.9.0
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| TRANSFER-ROUNDTRIP | Notion→Confluence 및 반대 표준 fixture | title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일 |
| TRANSFER-PARTICIPANTS | 동명이인·같은 email·새 target IDs | source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용 |
| TRANSFER-FAILURE-DOC | 정상 문서와 실패 종료 문서 | 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음 |
| TRANSFER-PAGINATION | 다중 페이지 및 100개 목록 경계 | manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음 |
| TRANSFER-CONFLICT | 중복 외부 Session ID·깨진 참조·unknown schema·다른 target digest | preflight/충돌 오류; 덮어쓰기·조용한 누락 없음 |
| TRANSFER-IDEMPOTENCY | 생성 응답 유실 후 marker 조회 | 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음 |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

backend에서 Windows `.\gradlew.bat test`, `.\gradlew.bat clean build`; macOS/Linux `./gradlew test`, `./gradlew clean build`를 실행한다.

## 👀 수동 QA

두 Provider 테스트 공간에 표준 synthetic 회의록과 roster를 준비해 양방향 복사·원문 read-back을 확인한다. source 첨부/댓글/이력을 보존했다고 주장하지 않는다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.04/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

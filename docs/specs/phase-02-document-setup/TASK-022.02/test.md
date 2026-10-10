# 🔎 Notion과 Confluence 기본 페이지 초기화 검증 계획

> TASK-022.02 · PRD v1.9.0
> GitHub Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| BOOT-NOTION | 인증된 부모와 빈 child hierarchy | Page로 루트·직속 두 child 생성, Database/Data Source 호출 0건 |
| BOOT-CONFLUENCE | space와 부모 있음/없음 fixture | spaceId 및 parentId에 맞는 루트·두 child, space 권한 검증 |
| BOOT-REUSE | 명시 reuseExistingRootId, 완전/부분 구조 | 완전 구조 재사용; 부분 누락만 생성; 기존 문서와 root 이름 변경/삭제 없음 |
| BOOT-DUPLICATE | 같은 이름 루트/child 둘 이상 또는 잘못된 type | 구조 충돌로 실패; 임의 선택·새 중복 생성 없음 |
| BOOT-RECOVERY | 외부 생성 성공 후 응답 유실·프로세스 중단 | journal/marker 확인 후 같은 page 재사용; 불명확하면 RECONCILIATION_REQUIRED |
| BOOT-ACTIVATION | 첫 연결·운영 중 준비·권한 거절·테스트 만료 | 첫 연결만 완료 뒤 READY; 기존 active 보존; 실패는 활성화하지 않음 |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

backend에서 Windows `.\gradlew.bat test`, `.\gradlew.bat clean build`; macOS/Linux `./gradlew test`, `./gradlew clean build`를 실행한다.

## 👀 수동 QA

Notion과 Confluence 테스트 공간에서 최초 생성·기존 루트 재사용·연속 완료 클릭·권한 거절을 각각 확인한다. credential 또는 workspace 부재는 실제 연동 QA BLOCKED다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.02/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

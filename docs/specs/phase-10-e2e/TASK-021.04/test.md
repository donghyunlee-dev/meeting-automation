# Release Acceptance 검증 계획

## 자동 Evidence audit

- 신규 명령: `npm run audit:release-evidence` (저장소 기존 script 위치와 parser convention에 맞게 구현 시 등록).
- 입력: PRD Section 24 canonical item ID 목록, leaf TASK/development tracker snapshot, `docs/evidence/` 경로 및 Evidence header metadata.
- 출력: DoD row count, 연결 Evidence count, 누락/중복/untracked 경로 및 TASK ID, schema errors, `REVIEW_REQUIRED` commit mismatch 수. Evidence 본문·민감값은 출력하지 않는다.
- 자동 검사는 각 evidence 경로 존재, TASK ID와 파일명 일치, DoD 20개 행 exact coverage, 상태 어휘, `PASS` aggregate gate를 확인한다. 존재를 품질의 증거로 간주하거나 수동 검토를 자동 PASS하지 않는다.

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| AUDIT-COMPLETE | 각 DoD 행에 fixture PASS evidence 연결 | 모든 ID가 딱 한 번 있고 Evidence file exists; aggregate PASS 후보 생성, reviewer sign-off는 미완 | row/count summary |
| AUDIT-MISSING | 필수 Evidence 경로 하나 제거 | 해당 DoD 및 evidence inventory 누락을 출력, aggregate `NOT_ACCEPTED` | missing ID/path count |
| AUDIT-DUPLICATE | 같은 DoD ID를 두 번 등록 | 중복 ID를 검출해 audit 실패; 다른 DoD가 같은 원본 Evidence를 참조하는 것은 허용 | duplicate ID list |
| AUDIT-UNTRACKED | tracker/Issue에 없는 task Evidence를 추가 | orphan artifact로 표시하고 completeness audit 실패 | TASK ID/count |
| AUDIT-STALE-COMMIT | Evidence commit을 candidate ancestor가 아니게 설정 | `REVIEW_REQUIRED`; 단순 경로 존재로 PASS하지 않음 | candidate/evidence commit relation |
| AUDIT-PRD-DRIFT | PRD Section 24 항목을 fixture에서 추가/삭제 | canonical DoD coverage mismatch로 audit 실패 | expected/actual row count |
| AUDIT-SENSITIVE-OUTPUT | synthetic canary를 금지 metadata/log output에 설정 | sanitizer 검사 실패, audit 출력에는 값이 아닌 rule ID/count만 존재 | safe rule summary |
| AUDIT-AGGREGATE-GATE | 한 항목을 FAIL/BLOCKED/NOT_RUN로 입력 | aggregate는 `NOT_ACCEPTED`; N/A/빈 판정 거부 | aggregate value |

## 수동 QA 및 release-only 확인

- QA reviewer가 각 source Evidence의 method, environment, commit, 결과, known limitation, privacy cleanup을 원본과 대조한다. 승인 signature/name과 검토 시각을 집계 문서에 남긴다.
- 실기기 결과는 TASK-018.04의 검증 범위와 TASK-020.04의 실제 기기/OS/browser/mode가 연결되는지 확인한다. 미실행 조합은 지원됨으로 확대하지 않는다.
- 실제 회의실 quality evidence는 고정 3인/5인 기준/fixture/version 및 TASK-019.03 regression과 연결성을 검토한다. 합성/실제 fixture를 혼동하지 않는다.
- provider delivery가 release acceptance상 필요한 경우 non-production, 사전 승인 test mailbox/channel만 사용하고 receipt 확인을 최소화한다. real attendee/customer에게 보내지 않는다. 승인/환경이 없으면 BLOCKED로 둔다.
- Secret, fail Audio, Transcript/Minutes 및 attendee delivery suppression은 TASK-017.01/.04 및 TASK-021.03 Evidence와 함께 확인한다. 실패 녹음은 download/discard/24h cleanup contract 및 no attendee send를 유지한다.
- Final review는 모든 DoD 행, 전체 TASK Evidence inventory, 미해결 Issues, prohibited dependency scan, DONE 재구현 대조와 cleanup 결과가 서로 일치하는지 검사한다.

## 합격 판정

- Aggregate `PASS`: DoD 20개 모두 `PASS`; 모든 Evidence 원격 경로/commit/reviewer가 확인됨; DoD를 막는 미해결 항목이나 필수 release-only 미실행 없음; 모든 TASK Evidence가 inventoried; privacy/cleanup 통과.
- Aggregate `NOT_ACCEPTED`: DoD 중 하나라도 `FAIL`, `BLOCKED`, `NOT_RUN`, 누락 또는 stale unresolved; release blocker는 follow-up Issue와 재검증 조건이 연결됨.
- PRD/Issue/status가 `DONE` 또는 closed여도 위 증거 검토 없이는 aggregate PASS로 대체할 수 없다.
- Evidence 결과 파일 `docs/evidence/TASK-021.04.md`에는 candidate SHA, 명령/절차, reviewer/time, DoD ID 판정과 evidence path/run ID, safe aggregates, issue link, cleanup만 기록한다. 민감 원문/스크린샷/네트워크 덤프는 금지한다.

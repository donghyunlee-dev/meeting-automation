# Release Acceptance 구현 계획

> 📌 v1.9.0 변경 계약: PRD v1.9.0의 최초 위저드, 양방향 이전, source 보존, durable 설정/journal Release DoD를 추가한다. 기존 v1.8.1 DoD 개수 고정을 기준으로 누락하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 선행 조건 및 책임

TASK-021.01~.03 E2E Evidence와 TASK-018.04, TASK-019.03, TASK-020.04 현장/품질/화면 Evidence를 수집한다. 그 이전 구현 Task의 Evidence도 PRD Section 24 항목에 연결해야 한다. QA가 matrix, provenance, gap 및 aggregate 판정을 소유한다. feature 구현이 없는 문서/검토 작업이므로 새 product UI/API를 만들지 않는다.

## 변경 대상

| 위치 | 책임 |
|---|---|
| `docs/evidence/TASK-021.04.md` | release candidate, reviewers, DoD 20행, evidence provenance, per-item/aggregate 판정, gaps, cleanup |
| `docs/specs/phase-10-e2e/TASK-021.04/spec.md` | acceptance rules, Evidence mapping, scope |
| `docs/specs/phase-10-e2e/TASK-021.04/plan.md` | QA workflow 및 artifact 변경 정의 |
| `docs/specs/phase-10-e2e/TASK-021.04/tasks.md` | 구현 가능한 evidence inventory/review/closure 단계 |
| `docs/specs/phase-10-e2e/TASK-021.04/test.md` | automated audit, manual/release-only 검증, 증거 형식 |

Evidence는 기존 검증 담당 Task가 작성한다. TASK-021.04는 독립 재검토 및 index 결과만 기록하고 원문 Evidence를 수정하지 않는다. 누락/실패는 해당 구현 Issue에 근거와 상태를 연결하며 변경 구현은 새 승인된 TASK로 분리한다.

## 검토 순서

- PRD Section 24에서 요구 항목 20개를 기계적으로 추출해 checklist 행 수/문구와 고정된 mapping을 비교한다. PRD 외 요구를 추가하지 않는다.
- Release Candidate commit/tag와 clean checkout, build/version, configuration fingerprint, deployed environment/test tenant 식별자를 기록한다. Secret 값이나 전체 환경 dump는 저장하지 않는다.
- 각 Evidence의 경로, commit, run ID, 기기/브라우저/provider mock/fixture, 명령 또는 현장 절차, 결과, sanitizer/cleanup, reviewer를 검증한다.
- PASS 후보는 Release Candidate 변경 범위와 원 Evidence commit 사이 영향 차이를 확인한다. 관련 FE/BE/API/Provider 변경이 있다면 해당 DoD를 재실행하거나 BLOCKED로 낮춘다.
- 실기기, 실제 회의실 fixture, 이메일/Slack 전송은 별도 release-only checklist에서 non-production 및 사전 승인된 test endpoint만 확인한다. 실제 attendee 주소/채널을 쓰지 않는다.
- 구현 tracker의 DONE TASK 목록과 Evidence inventory를 비교하고, Issue close만으로 Evidence 존재를 추론하지 않는다. DONE 재구현 검사는 PR/commit 범위를 근거로 한다.
- 미충족 행마다 원인, 영향, owner, 후속 Issue, 차단 해소 조건을 기록한다. all-PASS일 때만 acceptance aggregate를 PASS로 쓴다.

## 자동화 산출물

- `frontend/` 또는 repository root의 evidence audit script/test command `npm run audit:release-evidence`를 추가한다. 위치는 기존 script convention을 조사해 구현 시 정하고 문서화한다.
- Audit은 PRD DoD 20개 row ID, Evidence 경로 존재, 중복/누락, Evidence에 포함된 TASK ID의 tracker 매칭, 허용 status vocabulary, aggregate 규칙을 점검한다. Markdown 문맥·현장 품질 결과를 자동 PASS 판정하지 않는다.
- report는 누락 경로/TASK ID와 오류 수만 출력하며 파일 본문·Audio·secret을 출력하지 않는다. Evidence의 commit ID가 release baseline과 다르면 자동 FAIL 대신 reviewer 판단 대상으로 `REVIEW_REQUIRED`를 낸다.
- PRD 항목 수/ID 변경을 발견하면 script가 실패해 mapping 갱신을 요구한다. 변경 승인 전 기존 Release DoD를 생략하지 않는다.

## 외부 연동 안전

- 기본 자동 audit 및 E2E는 local/mock only다. Live email/Slack/provider 확인이 실제 acceptance에 필요하면 별도 명시된 release window, non-production tenant, 사전 허가된 소유 테스트 mailbox/channel, 삭제/cleanup 절차를 갖춘다.
- 실제 참석자/고객에게 전송하지 않고 real Audio/Transcript를 사용하지 않는다. 외부 send approval/실행은 이 문서 task가 대신 승인하지 않는다.
- Final Evidence는 `.md` 요약과 safe IDs/counts만 저장한다. 원본 민감 screenshot/network trace/report는 저장하지 않는다.

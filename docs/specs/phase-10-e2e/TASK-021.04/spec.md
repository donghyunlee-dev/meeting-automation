# Release Acceptance 및 Evidence 검토

> 📌 v1.9.0 변경 계약: PRD v1.9.0의 최초 위저드, 양방향 이전, source 보존, durable 설정/journal Release DoD를 추가한다. 기존 v1.8.1 DoD 개수 고정을 기준으로 누락하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 작업 식별 정보

- 작업: TASK-021.04
- 상위 작업: TASK-021 End-to-End Acceptance
- 단계: Phase 10 — End-to-End Acceptance
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06, Section 24
- 관련 기준: Release Definition of Done 전체, SCR/FR/API/EXT/DEC 전체
- 선행 작업: TASK-021.01 Issue #77; TASK-021.02 #78; TASK-021.03 #79; TASK-018.04 #68; TASK-019.03 #72; TASK-020.04 #76
- GitHub Issue: [#80](https://github.com/donghyunlee-dev/meeting-automation/issues/80)

## 결과

PRD Section 24의 각 Release Definition of Done 항목에 검증 가능한 `docs/evidence/` 기록을 연결하고, 원본 실행 근거·commit·환경·정리 상태와 관련 TASK/Issue를 QA가 검토한다. 이 작업의 결과는 항목별 판정과 미충족 목록을 포함한 `docs/evidence/TASK-021.04.md`이다. 모든 필수 항목이 검증된 경우에만 Release Acceptance를 PASS로 기록한다. Evidence 누락·오래된 build·미검증 기기 조합·미해결 차단 이슈는 PASS 처리하지 않는다.

## 판정 계약

- `PASS`: PRD 항목을 직접 검증한 원본 Evidence가 있고, 실행 대상 commit/build·환경·재현 절차·결과·sanitization/cleanup이 확인되며 현재 Release Candidate와의 관계가 설명된다.
- `FAIL`: 실행 가능한 검증에서 기대 조건이 위반됐다. 재현 근거와 후속 Issue를 연결한다.
- `BLOCKED`: 실기기, 통제된 계정/provider, 선행 Evidence 또는 환경이 없어 확인할 수 없다. 부족한 증거와 필요한 후속 조치를 명시한다.
- `NOT_RUN`: 검증이 아직 실행되지 않았다. 이유와 owner를 적으며 완료 또는 PASS로 계산하지 않는다.
- `N/A` 판정은 허용하지 않는다. PRD Section 24의 모든 checklist 항목은 Release 필수 조건이다. 범위 제거가 필요하면 별도 PRD 승인 변경이 먼저다.
- 구현 Issue가 `closed` 또는 개발 tracker가 `DONE`이어도 Release Evidence를 대신하지 않는다. 반대로 Evidence만으로 구현 Issue 상태를 임의 변경하지 않는다.
- Evidence는 immutable run snapshot으로 취급한다. 후속 commit이 기존 결과를 무효화하면 영향 항목을 재실행하거나 검토 결과를 BLOCKED로 낮춘다.
- QA는 pass/fail 집계와 증거 경로만 보고한다. Audio, Transcript, Secret, auth header, 개인 이메일/전화, 실제 회의 내용 및 public storage URL을 집계 문서에 복사하지 않는다.

## DoD 추적 범위

| PRD Section 24 항목 | 기본 Evidence 출처 |
|---|---|
| Android Chrome 30~60분 녹음 | `TASK-018.01.md`, `TASK-018.04.md` |
| iOS Safari 30~60분 녹음 | `TASK-018.02.md`, `TASK-018.04.md` |
| 실제 회의실 3명 diarization | `TASK-019.01.md`, `TASK-019.03.md` |
| 실제 회의실 5명 diarization | `TASK-019.02.md`, `TASK-019.03.md` |
| Speaker A/B/C 참가자 매핑 | TASK-007.01~.03 evidence 및 `TASK-021.01.md` |
| segment별 수동 화자 지정 UI 부재 | TASK-007.02/008.02 evidence 및 `TASK-020.03.md` |
| Template 변경 시 STT 미실행 | TASK-009.03 evidence 및 `TASK-021.01.md` |
| default.md/project.md 생성 | TASK-002.02 evidence와 저장소 fixture 기반 회귀 결과 |
| Notion Page hierarchy | TASK-002.02/Notion provider E2E evidence |
| Confluence Page hierarchy | TASK-002.03 및 `TASK-021.02.md` |
| Participants CRUD | TASK-003.01~.03 evidence |
| Meetings list/detail read-only | TASK-014.01~.04 및 `TASK-021.03.md` |
| Email 전달 | TASK-011.01/.02 및 `TASK-021.02.md`; 필요한 release-only 확인은 통제된 수신 계정 |
| Slack Notification | TASK-012.01/.02 및 `TASK-021.02.md`; 필요한 release-only 확인은 통제된 test channel |
| Admin Slack 4종 오류 | TASK-016.01/.02 evidence |
| frontend Secret 노출 0건 | TASK-017.01/.04 및 `TASK-021.03.md` |
| Audio 영구 보관 0건 | TASK-017.02/.04 및 `TASK-021.03.md` |
| DB/JPA/Redis/Queue 의존 0건 | `TASK-001.05` architecture/dependency evidence와 Release Candidate source/dependency scan |
| DONE TASK 재구현 없음 | PRD 개발 tracker와 Issues/PR/merge history 대조 기록 |
| 모든 TASK Evidence 저장 | 모든 구현 완료 leaf TASK의 Evidence 경로/commit completeness inventory |

Evidence가 현재 저장소에 없으면 추정해 PASS로 만들지 않고 `BLOCKED` 또는 `NOT_RUN`로 남긴다. 출처 mapping은 검토용 인덱스이며 각 항목의 실제 측정값과 Release Candidate 호환성을 직접 확인해야 한다.

## 범위와 제외

- 포함: DoD 완전성 matrix, 원본 Evidence 품질/commit 정합성 검토, 안전한 release-only 확인 목록, 미충족·결함·blocker Issue 연결, release 판정 문서.
- 제외: 제품 코드/설정 변경, 기존 Evidence 위조·덮어쓰기, 실제 고객/참석자 대상 발송, 승인되지 않은 실제 회의 Audio 수집, PRD DoD 생략 승인, 배포/릴리즈 실행.
- 이 task는 Release Acceptance 문서화를 위한 설계다. 후속 구현이 완료되기 전에는 PASS나 제품 출시 승인을 주장하지 않는다.

## 완료 기준

- Section 24 checklist 20개 항목이 각각 독립된 표 행으로 있고 Evidence 경로/commit/run, 판정, reviewer, issue 또는 명시적 결함 없음이 기록된다.
- 모든 TASK Evidence completeness 결과가 leaf TASK/Issue/merge commit과 대조되어 누락 수 및 경로가 분명하다. Evidence 없는 Task는 완료로 승격되지 않는다.
- PASS는 필수 DoD 모두 PASS일 때만 허용한다. 하나라도 FAIL/BLOCKED/NOT_RUN/Evidence 누락이면 aggregate 결과는 `NOT_ACCEPTED`다.
- Release-only 외부 검증은 non-production tenant와 사전에 승인된 소유 테스트 mailbox/channel만 사용한다. 실제 참석자에게 발송하지 않는다.
- `docs/evidence/TASK-021.04.md`는 검토자·commit·명령/절차·항목별 결과·미해결 Issue·민감자료 미포함·cleanup을 기록하며 원문 민감 payload를 포함하지 않는다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [핵심 E2E](../TASK-021.01/spec.md)
- [Document/Email/Slack E2E](../TASK-021.02/spec.md)
- [History/보안/Audio E2E](../TASK-021.03/spec.md)
- [PRD Release Definition of Done](../../../product/PRD.md#release-definition-of-done)

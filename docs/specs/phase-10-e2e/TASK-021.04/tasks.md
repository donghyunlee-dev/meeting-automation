# Release Acceptance 작업 목록

> 📌 v1.9.0 변경 계약: PRD v1.9.0의 최초 위저드, 양방향 이전, source 보존, durable 설정/journal Release DoD를 추가한다. 기존 v1.8.1 DoD 개수 고정을 기준으로 누락하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

- [ ] TASK-021.01~.03, TASK-018.04, TASK-019.03, TASK-020.04 Issue와 원격 Evidence 상태를 확인한다. 의존: #77~#79, #68/#72/#76. 결과: predecessor completeness 표가 만들어지고 미완료 의존이 보인다.
- [ ] PRD Section 24 항목과 task-local Evidence 경로를 20행 matrix로 연결한다. 의존: PRD v1.8.1. 결과: 각 DoD에 source task/evidence/review question이 있다.
- [ ] Release Candidate commit/build/config/environment provenance를 고정한다. 의존: candidate artifact. 결과: secret 없이 동일 candidate를 재현할 식별자를 남긴다.
- [ ] Evidence completeness audit 명령을 repository script convention에 추가한다. 의존: matrix schema. 결과: 누락 path/중복 TASK/untracked Evidence와 DoD 개수 mismatch를 safe summary로 검출한다.
- [ ] 기존 Evidence의 run/commit/environment/privacy/cleanup과 candidate 호환성을 항목별 review한다. 의존: audit inventory. 결과: 행별 PASS/FAIL/BLOCKED/NOT_RUN 및 reviewer rationale.
- [ ] 실기기/회의실/provider 외부 확인을 release-only 통제 checklist로 실행한다. 의존: 승인된 non-production tenant, 기기, test mailbox/channel. 결과: 실제 범위만 기록하고 attendee/customer send는 0.
- [ ] 개발 tracker DONE, closed Issue, merge history, 모든 Task Evidence inventory를 대조한다. 의존: repository Git/GitHub read access. 결과: DONE 재구현/미저장 evidence gap이 식별된다.
- [ ] 미충족 항목에 owner/후속 Issue/해소 조건을 연결하고 aggregate 판정을 기록한다. 의존: 전체 review. 결과: 필수 항목 하나라도 비PASS이면 `NOT_ACCEPTED`.
- [ ] `docs/evidence/TASK-021.04.md` 작성 후 sanitizer/링크/commit 정합성을 재검토한다. 의존: acceptance review. 결과: 비민감 final report와 review sign-off가 준비된다.

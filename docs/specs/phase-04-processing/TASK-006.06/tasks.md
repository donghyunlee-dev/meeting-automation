> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# API-010 조회 작업 항목

1. API-010/common envelope, Session snapshot/version, 006.04/006.05 field ownership을 확인한다. 완료 증거: query response source가 명확하다.
2. processing/review/failure/missing/read-only/concurrent mutation response tests를 먼저 작성해 실패를 확인한다.
3. read-only Session snapshot retrieval 및 없는 Session 404 mapping을 구현한다. 완료 증거: GET이 Session을 변경하지 않는다.
4. status-gated response mapper로 processing fields와 review fields를 구성한다. 완료 증거: partial Transcript/Minutes가 review 전 노출되지 않는다.
5. REVIEW `allowedActions`를 현재 상태에 따라 계산하고 Speaker/Transcript/Minutes version을 같은 snapshot으로 제공한다. 완료 증거: action/data consistency가 확인된다.
6. API-010 response/privacy/read-only 및 concurrency 경계를 확인한다. Developer/QA는 다음 focused selectors만 실행하며 Backend 전체 suite와 `clean build`를 반복하지 않는다. CI 설정은 변경하지 않는다. 완료 증거: response schema와 read-only 증거를 리뷰에 기록한다.

```powershell
& .\gradlew.bat test --tests com.meetingautomation.MeetingSessionStatusApiTests --tests com.meetingautomation.ProcessingStartApiTests --tests com.meetingautomation.api.processing.ProcessingJobRunnerTests
```

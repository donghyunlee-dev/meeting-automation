> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Minutes 편집 API 작업 항목

관련 Issue: [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37).

1. API-010 Session/Minutes snapshot, Session roster, If-Match/version repository boundary와 API-013 Template 변경 경계를 확인한다. 완료 증거: writable Minutes field와 immutable Template metadata를 PR에 기록한다.
2. valid/empty/malformed/unknown field/date/owner/template/state/version/no-op/concurrency/privacy tests를 먼저 작성한다. 완료 증거: 입력 실패 시 이전 Minutes가 유지되는 테스트가 구현 전 실패한다.
3. API-012 request DTO와 complete StructuredMinutes validation을 구현한다. 완료 증거: 누락/추가/null/type 오류 및 잘못된 참조가 저장 전에 거절된다.
4. Session 존재/Review/allowed action/If-Match를 검증한다. 완료 증거: 지정된 404/409/412 envelope가 반환되고 state/version 변경이 없다.
5. Minutes full replacement와 no-op 비교를 CAS Session mutation으로 저장한다. 완료 증거: 실질 변경만 version +1이며 동시 stale 요청은 412다.
6. `{data:{version,minutes}}` response와 API-010 read-after-write 경로를 연결한다. 완료 증거: 재조회가 같은 saved Minutes/version을 반환한다.
7. `& .\gradlew.bat test --tests com.meetingautomation.MeetingSessionMinutesApiTests`로 API-012/CAS focused tests와 privacy/concurrency 회귀를 확인한다. 개발자/QA 검증에서 전체 Backend suite와 `clean build`는 실행하지 않고 GitHub Actions CI 설정은 바꾸지 않는다. 완료 증거: 정확한 selector와 결과를 Issue에 남긴다.

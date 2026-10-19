> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Speaker mapping API 작업 항목

관련 Issue: [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34).

1. API-010 Session snapshot, `If-Match` mutation/version 정책, `DocumentProviderResolver`/`ParticipantListingProvider` roster path 및 공통 오류 매핑을 확인한다. 완료 증거: handler 의존 관계와 atomic mutation API를 기록한다.
2. 정상 전체 교체, null mapping, empty speakers, malformed/duplicate/unknown speaker, 잘못된 Participant, Session/state/version 오류, Provider 실패 및 race 테스트를 작성한다. 완료 증거: 검증 실패에서 mutation이 없는 테스트가 구현 전 실패한다.
3. API-011 request DTO와 body/header validation을 구현한다. 완료 증거: 없는/중복 Speaker, 누락 mapping, 비허용 Participant ID가 저장 경계 전에 거절된다.
4. roster 참조와 Participant 이름을 조회하고 provider 오류를 공통 envelope로 변환한다. 완료 증거: 전체 roster 정상 조회만 성공하며 오류 원문은 응답/로그에 없다.
5. Session version compare-and-set 아래 전체 Speaker mapping을 원자 저장한다. 완료 증거: 저장 한 번에 version이 1 증가하고 충돌/저장 실패는 이전 snapshot을 유지한다.
6. API-011 `{data}` response 및 API-010 조회와의 version/reference 일관성을 연결한다. 완료 증거: Speaker 단위 mapping이 해당 화자의 모든 Transcript segment에 resolve된다.
7. Backend root에서 test.md의 변경 관련 Gradle class selectors와 오류·보안 회귀 검증만 수행한다. 전체 Backend suite와 `clean build`는 Developer/QA 검증으로 반복하지 않으며 CI 설정은 변경하지 않는다. 완료 증거: 정확한 selector 결과와 API contract evidence를 Issue에 남긴다.

모든 구현 단계는 API-010 Review Session과 TASK-003.01 Participant roster 계약에 의존한다. Frontend UI 및 저장 연결은 각각 TASK-007.02와 TASK-007.03 범위다.

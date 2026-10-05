# Speaker mapping API 작업 항목

관련 Issue: [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34).

1. API-010 Session snapshot, `If-Match` mutation/version 정책, API-003 roster Port 및 공통 오류 매핑을 확인한다. 완료 증거: handler 의존 관계와 atomic mutation API를 기록한다.
2. 정상 전체 교체, null mapping, empty speakers, malformed/duplicate/unknown speaker, 잘못된 Participant, Session/state/version 오류, Provider 실패 및 race 테스트를 작성한다. 완료 증거: 검증 실패에서 mutation이 없는 테스트가 구현 전 실패한다.
3. API-011 request DTO와 body/header validation을 구현한다. 완료 증거: 없는/중복 Speaker, 누락 mapping, 비허용 Participant ID가 저장 경계 전에 거절된다.
4. roster 참조와 Participant 이름을 조회하고 provider 오류를 공통 envelope로 변환한다. 완료 증거: 전체 roster 정상 조회만 성공하며 오류 원문은 응답/로그에 없다.
5. Session version compare-and-set 아래 전체 Speaker mapping을 원자 저장한다. 완료 증거: 저장 한 번에 version이 1 증가하고 충돌/저장 실패는 이전 snapshot을 유지한다.
6. API-011 `{data}` response 및 API-010 조회와의 version/reference 일관성을 연결한다. 완료 증거: Speaker 단위 mapping이 해당 화자의 모든 Transcript segment에 resolve된다.
7. `./gradlew test`, `./gradlew clean build`와 오류·보안 회귀 검증을 수행한다. 완료 증거: 명령 결과와 API contract evidence를 Issue에 남긴다.

모든 구현 단계는 API-010 Review Session과 TASK-003.01 Participant roster 계약에 의존한다. Frontend UI 및 저장 연결은 각각 TASK-007.02와 TASK-007.03 범위다.

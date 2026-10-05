# API-010 조회 작업 항목

1. API-010/common envelope, Session snapshot/version, 006.04/006.05 field ownership을 확인한다. 완료 증거: query response source가 명확하다.
2. processing/review/failure/missing/read-only/concurrent mutation response tests를 먼저 작성해 실패를 확인한다.
3. read-only Session snapshot retrieval 및 없는 Session 404 mapping을 구현한다. 완료 증거: GET이 Session을 변경하지 않는다.
4. status-gated response mapper로 processing fields와 review fields를 구성한다. 완료 증거: partial Transcript/Minutes가 review 전 노출되지 않는다.
5. REVIEW `allowedActions`를 현재 상태에 따라 계산하고 Speaker/Transcript/Minutes version을 같은 snapshot으로 제공한다. 완료 증거: action/data consistency가 확인된다.
6. privacy/log boundary와 `./gradlew test`, `./gradlew clean build`를 실행한다. 완료 증거: response schema와 read-only 증거를 리뷰에 기록한다.

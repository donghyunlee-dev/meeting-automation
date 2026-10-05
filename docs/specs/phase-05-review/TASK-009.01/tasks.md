# Minutes 재생성 API 구현 작업

1. TASK-006.05/.06 및 TASK-008.01/.03의 generation port, Session snapshot/version mutation, Template catalog, API-010 DTO, idempotency 저장을 추적한다. 완료 결과: 구체 타입과 atomic 저장/복구 경계를 Issue에 기록한다.
2. domain/application/API 테스트를 먼저 추가한다. 실패 복구, 성공 교체, 잘못된 입력, 동시 version 변경, idempotency replay/conflict를 포함한다. 완료 결과: 아직 구현하지 않은 동작이 기대 결과와 함께 실패한다.
3. API-013 request/response DTO와 입력 검증을 추가한다. 완료 결과: `templateId`, `If-Match`, `Idempotency-Key`가 유효한 REVIEW Session에서만 202를 반환하고 다른 경로는 저장 변경이 없다.
4. immutable generation command와 작업 id를 만들고 현재 Transcript, mapping, 최소 roster reference, catalog Template만 전달한다. 완료 결과: Audio 경로 참조가 없고 동일 요청 replay가 같은 수락 결과를 갖는다.
5. Session에 regeneration backup과 PROCESSING/DRAFT_REGENERATION acceptance를 원자 저장한다. 완료 결과: processing 동안 partial Review data가 API-010에 노출되지 않는다.
6. 기존 `MinutesGenerationProvider` 결과를 StructuredMinutes와 Template/roster 참조 규칙으로 검증하고 성공 commit을 구현한다. 완료 결과: 완전한 결과와 provenance만 원자 저장되고 Session은 REVIEW/DRAFT_READY/100%가 된다.
7. 모든 재생성 단계 실패에서 backup Minutes/provenance restore, REVIEW 전이 및 safe `lastOperation`을 원자 처리한다. 완료 결과: API-010이 이전 편집본 전체와 복구된 action set을 반환하며 version은 증가한다.
8. API-010 optional `lastOperation` 성공/실패 serialization을 추가한다. 완료 결과: terminal outcome과 Review data가 같은 version의 snapshot이고 기존 API-010 consumers 호환 테스트가 통과한다.
9. 빈 Transcript, provider/auth/timeout/schema/storage failure, duplicate delivery, stale worker, concurrent request, failure redaction 테스트를 완성한다. 완료 결과: 적합한 예상 상태/응답과 provider invocation count가 검증된다.
10. `./gradlew test`와 `./gradlew clean build`, API 수동 QA를 실행하고 결과 및 원격 SDD 경로를 Issue #40에 기록한다. 완료 결과: 명령 결과와 acceptance evidence가 남는다.

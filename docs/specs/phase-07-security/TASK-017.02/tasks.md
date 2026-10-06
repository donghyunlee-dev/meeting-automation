# 구현 작업

모든 작업은 TASK-017.02 spec의 API/data contract와 Issue #61에 의존한다. Backend 테스트는 기능 구현 전에 작성하고, 설계 문서 작업 중에는 실행하지 않는다.

## 단계

1. API-010 failure metadata/action matrix와 private storage adapter 계약의 failing tests를 작성한다. 완료 증거: 실패 상태와 저장소 CRUD/expiry/listing 경계가 요구 동작과 다를 때 테스트가 실패한다.
2. `PrivateAudioStoragePort`와 설정된 private object storage adapter를 구현한다. 완료 증거: API-007 chunk write가 durable ACK를 내고 stream read, metadata, idempotent delete/list-expired가 filesystem 없이 동작한다.
3. Session processing failure 모델에 failed stage, safe error code, retryability, audio availability, UTC expiry, 성공 download marker를 추가한다. 완료 증거: 전이와 Session version snapshot이 새 필드를 일관되게 유지한다.
4. Audio success cleanup/failure retention/retry lease/retry failure TTL reset을 구현한다. 완료 증거: Review 성공 시 즉시 객체 삭제되고 retry 실패 시 terminal time+24h가 객체 expiry로 설정된다.
5. API-010 `allowedActions` 계산과 failure response serializer를 구현한다. 완료 증거: 서버 상태/TTL과 action 목록이 일치하고 raw error/object key/audio body가 응답에 없다.
6. API-020 retry use case/controller를 구현한다. 완료 증거: 같은 job의 완료 stage는 건너뛰고 실패 stage만 재실행하며, non-retryable·만료·동시 요청은 안전 오류로 거절된다.
7. API-021 authenticated object stream download를 구현한다. 완료 증거: 검증된 MIME과 attachment filename이 반환되고, public object URL/credential이나 만료/삭제 Audio는 노출되지 않는다.
8. API-022 failure finalize와 Meeting document writer를 구현한다. 완료 증거: `DOWNLOADED`/`DISCARDED` 선택 및 회의 metadata/safe failure 요약이 저장되고 Delivery provider 호출이 없다.
9. Session과 독립적인 durable object expiry scheduler를 구현한다. 완료 증거: 만료 직후 API access가 차단되고 Session restart 뒤에도 만료 객체가 삭제되며 반복 삭제/객체 없음이 멱등 처리된다.
10. API/controller/Document Port 연동 회귀 테스트와 evidence procedure를 갱신한다. 완료 증거: 기존 정상 Review/Publish 동작이 바뀌지 않고 모든 신규 acceptance criterion이 test case에 연결된다.

## 의존 관계

- 1단계는 API-010 현재 response contract에 의존한다.
- 2~5단계는 storage contract와 lifecycle model에 의존해 순서대로 수행한다.
- 6~8단계는 lifecycle model과 API-010 action contract에 의존한다.
- 9단계는 expiry metadata와 finalize use case가 완성된 뒤 진행한다.
- 10단계는 모든 BE use case가 통합된 뒤 수행한다.

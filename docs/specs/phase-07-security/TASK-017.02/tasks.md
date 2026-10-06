# 구현 작업

모든 작업은 TASK-017.02 spec의 API/data contract와 Issue #61에 의존한다. Backend 테스트는 기능 구현 전에 작성하고, 설계 문서 작업 중에는 실행하지 않는다.

## 단계

1. API-010 failure metadata와 상태별 action matrix를 검증하는 failing tests를 작성한다. 완료 증거: PROCESSING_FAILED retryable/non-retryable, 만료 전/후 응답 차이가 명확히 실패한다.
2. Session processing failure 모델에 failed stage, safe error code, retryability, audio availability, UTC expiry, 성공 download marker를 추가한다. 완료 증거: 전이와 Session version snapshot이 새 필드를 일관되게 유지한다.
3. Audio 성공 cleanup/실패 보존/재시도 중 보존/retry 실패 TTL 재설정을 구현한다. 완료 증거: review 성공 시 즉시 삭제되고 retry 실패 시 terminal time+24h가 expiry가 된다.
4. API-010 `allowedActions` 계산과 failure response serializer를 구현한다. 완료 증거: 서버 상태/TTL과 action 목록이 일치하고 raw error/file path/audio body가 응답에 없다.
5. API-020 retry use case/controller를 구현한다. 완료 증거: 같은 job의 완료 stage는 건너뛰고 실패 stage만 재실행하며, non-retryable·만료·동시 요청은 안전 오류로 거절된다.
6. API-021 stream download를 구현한다. 완료 증거: 검증된 MIME과 attachment filename이 반환되고, 만료/삭제/assembly 실패 Audio는 노출되지 않는다.
7. API-022 failure finalize와 Meeting document writer를 구현한다. 완료 증거: `DOWNLOADED`/`DISCARDED` 선택 및 회의 metadata/safe failure 요약이 저장되고 Delivery provider 호출이 없다.
8. TTL scheduler와 startup stale-file cleanup을 구현한다. 완료 증거: 만료 직후 API access가 차단되고 삭제 반복/파일 없음이 성공 처리되며 path escape/symlink는 거부된다.
9. API/controller/Document Port 연동 회귀 테스트와 evidence procedure를 갱신한다. 완료 증거: 기존 정상 Review/Publish 동작이 바뀌지 않고 모든 신규 acceptance criterion이 test case에 연결된다.

## 의존 관계

- 1단계는 API-010 현재 response contract에 의존한다.
- 2~4단계는 1단계를 통과한 뒤 순서대로 수행한다.
- 5~7단계는 lifecycle model과 API-010 action contract에 의존한다.
- 8단계는 expiry field와 failure finalize use case가 완성된 뒤 진행한다.
- 9단계는 모든 BE use case가 통합된 뒤 수행한다.

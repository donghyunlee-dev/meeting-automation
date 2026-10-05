# Minutes 재생성 API 검증 계획

## 자동화 테스트

| ID | 시나리오와 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| REG-API-01 | REVIEW, `REGENERATE_MINUTES`, 유효 Template, matching `If-Match`, 새 key | HTTP 202; status `PROCESSING`; stage `DRAFT_REGENERATION`; Session mutation 1회 | Controller/application contract test |
| REG-API-02 | 필수 header/body 누락 또는 잘못된 형식 | HTTP 400 `VALIDATION_FAILED`; version/Minutes 불변 | MVC validation test |
| REG-API-03 | 없는 sessionId | HTTP 404 `SESSION_NOT_FOUND`; provider 호출 0회 | API integration test |
| REG-API-04 | `If-Match` version stale | HTTP 412 `SESSION_VERSION_CONFLICT`; provider 호출/저장 변경 0회 | optimistic concurrency test |
| REG-API-05 | status가 REVIEW가 아니거나 regeneration action이 없음 | HTTP 409 `SESSION_STATE_CONFLICT`; snapshot 불변 | lifecycle authorization test |
| REG-API-06 | Template ID가 없거나 지원 catalog 외 값 | HTTP 400 `VALIDATION_FAILED`; provider 호출 0회 | Template catalog validation test |
| REG-API-07 | 같은 key와 같은 정규화 요청 replay | 최초 202 결과 재사용; generation command/provider 추가 호출 0회 | idempotency test |
| REG-API-08 | 같은 key에 다른 session/template/version 요청 | HTTP 409 `IDEMPOTENCY_KEY_CONFLICT`; 원 operation 불변 | idempotency conflict test |
| REG-API-09 | 정상 provider StructuredMinutes 결과 | 전체 Minutes와 Template provenance 원자 저장; REVIEW/DRAFT_READY/100%; version 증가 | use case + API-010 integration test |
| REG-API-10 | timeout, 429, 5xx, auth/config, malformed/schema-invalid 결과 | 이전 Minutes 필드 전체와 Template provenance 원자 복구; REVIEW; API-010 실패 metadata 및 수정/재생성 action 반환 | parameterized failure rollback test |
| REG-API-11 | output 저장 중 예외 | backup 전체 복구; 부분 새 Minutes 없음; REVIEW 복귀 | repository transaction test |
| REG-API-12 | 실패 직전 사용자 Minutes가 API-012로 저장되어 있음 | 실패 후 같은 모든 필드/값이 읽히고 Transcript/mapping 유지; version 증가 | API-012 → API-013 → API-010 round-trip test |
| REG-API-13 | 빈 Transcript, 유효 Template | provider 호출 0회; 선택 Template id/version과 빈 StructuredMinutes 저장; REVIEW 성공 metadata | empty input test |
| REG-API-14 | worker가 중복 완료/실패 전달 또는 더 최신 operation 뒤 stale 결과 도착 | 이전 operation이 latest Session을 덮지 않음; terminal mutation 한 번 | operation fencing/concurrency test |
| REG-API-15 | 실패 후 API-010 조회 | common envelope 유지, 같은 snapshot version의 이전 Minutes와 `lastOperation={type:MINUTES_REGENERATION,outcome:FAILED,requestedTemplateId,errorCode:MINUTES_REGENERATION_FAILED}` | serialization contract test |
| REG-API-16 | provider/validation/storage exception 발생 | response/log에 provider 원문, prompt, Transcript, Minutes, Secret 없음 | error redaction/log capture test |
| REG-API-17 | 성공 및 실패 요청의 dependency 호출 기록 | Audio assembly, TranscriptionProvider, Diarization adapter 각각 0회 | application port spy test |
| REG-API-18 | 기존 일반 pipeline failure `PROCESSING_FAILED` API-010 조회 | 기존 TASK-006.06 계약 유지; regeneration-specific `lastOperation` 미포함 | regression compatibility test |

실행 명령: `./gradlew test`, 전체 검증 `./gradlew clean build`.

## 수동 API QA

- Review Session의 현재 version과 전체 Minutes를 기록하고 Project Template 재생성 POST를 보낸다. 202와 DRAFT_REGENERATION을 확인하고 polling 중 API-010이 partial Review data를 비노출하는지 확인한다.
- 성공 완료 뒤 API-010 `REVIEW` snapshot에서 새 Template provenance/Minutes, version 증가, 성공 `lastOperation`을 확인한다.
- 두 번째 Session의 Minutes를 수정 저장한 뒤 provider 오류를 유도해 재생성한다. 종료 후 API-010에서 직전 저장 Minutes 전체, 기존 Template provenance, Transcript/mapping 유지, REVIEW status, 실패 safe code 및 `UPDATE_MINUTES`/`REGENERATE_MINUTES` actions를 확인한다.
- 서로 다른 Template로 같은 idempotency key를 재사용해 409가 나고 기존 operation 결과가 바뀌지 않는지 확인한다.
- log/HTTP response를 조사해 원본 회의 콘텐츠, provider detail, Secret이 포함되지 않았는지 확인한다.

## 릴리스 확인

- API-010 optional `lastOperation`은 공통 response fields를 변경하지 않고 미지원 optional field를 무시하는 소비자 호환성이 확인되어야 한다.
- 성공/실패 결과와 backup은 memory-only Session lifecycle과 동일한 복구 한계를 가진다. process restart 후 복구를 제공한다고 설명하지 않는다.
- 이 작업에서는 production 배포를 수행하지 않는다. 구현 Issue에 자동화 결과와 수동 QA 증거를 남긴다.

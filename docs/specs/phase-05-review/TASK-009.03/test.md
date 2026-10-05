# Minutes 재생성 STT/Diarization 회귀 검증 계획

## 자동화 통합 테스트

| ID | 실행 경로와 조건 | 기대 결과 | 증거 |
|---|---|---|---|
| REG-PIPE-01 | API-010 REVIEW fixture에 기존 Transcript/mapping/Minutes를 만들고 API-013 정상 POST 후 background worker 완료 대기 | HTTP 202 후 API-010 REVIEW/DRAFT_READY 새 Template Minutes; Minutes provider 1회, Audio assembly/STT/Diarization 각 0회 | Spring API + async worker integration test |
| REG-PIPE-02 | 같은 Session 재생성 전에 Transcript와 mapping baseline 저장 | 성공 terminal snapshot에서 segment/speaker/mapping 값이 그대로 유지 | repository/API-010 snapshot assertion |
| REG-PIPE-03 | Audio source, STT, Diarization spies의 interaction log 검사 | regeneration 경로 전체에서 해당 세 dependency 호출이 없고 audio read도 0회 | counting spy assertion |
| REG-PIPE-04 | `MinutesGenerationProvider`가 유효 structured result 대신 typed failure 반환 | worker 종료 후 API-010이 기존 Minutes/provenance를 복구해 REVIEW 반환; generation attempt 1회, pipeline dependency 각각 0회 | rollback end-to-end integration test |
| REG-PIPE-05 | REG-PIPE-04 실패 종료 뒤 Transcript/Speaker baseline 비교 | Transcript와 mapping 불변, Minutes 필드/provenance 보존, version 증가와 safe failure `lastOperation` | Session snapshot comparison |
| REG-PIPE-06 | 같은 Idempotency-Key/body를 API-013에 재전송 | 같은 수락 결과; async regeneration/generation provider 중복 없음; Audio/STT/Diarization 각 0회 | API idempotency integration test |
| REG-PIPE-07 | 같은 worker job을 두 번 dispatch/execute | Session terminal mutation 및 generation 시도가 한 번; stale duplicate가 snapshot을 덮지 않음 | job runner fencing test |
| REG-PIPE-08 | 동일 key에 다른 Template body를 제출 | 공통 conflict `IDEMPOTENCY_KEY_CONFLICT`; 새로운 작업 없음; 모든 pipeline dependency 0회 | API error/call-count test |
| REG-PIPE-09 | stale `If-Match` 또는 API-013 invalid Template | 적절한 API error; Session snapshot 불변; generation/Audio/STT/Diarization 모두 0회 | API validation integration test |
| REG-PIPE-10 | API-009 initial recording pipeline positive control에 동일 instrumentation 사용 | assembly → `TranscriptionProvider` → `DiarizationProvider` 순으로 각각 정확히 1회 호출; test wiring 정상 | pipeline integration control test |
| REG-PIPE-11 | integration test report 및 captured logs 검사 | 합성 fixture 외 Transcript/Audio bytes, Secret, provider raw request/response 없음; call count 외 request content 미출력 | log/report redaction assertion |
| REG-PIPE-12 | job completion signal timeout | polling/sleep 기반 우연 통과 없이 정해진 timeout에 현재 stage/session ID와 함께 명확히 실패 | executor timeout assertion |

실행 위치는 구현 checkout의 Backend root다. 사용 명령: `./gradlew test` 및 `./gradlew clean build`; 정확한 wrapper/module 경로와 선택적 task filter는 구현 시 확인하고 Issue에 기록한다.

## 수동/CI 환경 확인

- CI profile에서 실제 integration application context가 API controller, job runner, Session store 및 counting fake ports를 모두 등록하는지 확인한다.
- API-013 202 직후 test worker가 시작되고 completion signal 후 API-010에서 terminal Review snapshot을 읽는지 확인한다.
- success와 failure run의 port call count만 evidence에 남기고 Transcript, Minutes, Audio/provider payload 값은 복사하지 않는다.
- positive control은 별도 Session/fixture에서 실행해 regeneration count와 섞지 않는다.

## 릴리스 확인

- real STT, diarization 또는 Audio storage Provider를 호출하는 integration credential을 요구하지 않는다.
- test fake가 production bean replacement 외에는 동작/API contract를 바꾸지 않는다.
- `TASK-009.01` unit/API assertions 및 `TASK-009.02` browser test와 결과가 중복돼도 각각 unit, service/API, browser 경계를 대체하지 않는다.
- production 배포나 사용자 Audio 데이터 검증은 포함하지 않는다.

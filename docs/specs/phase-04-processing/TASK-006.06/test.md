# API-010 Processing/Review 조회 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-010-01 | PROCESSING 직후 API-009 snapshot | `{data}`의 Session/version/status와 `AUDIO_ASSEMBLY` stage/progress 일치 | Controller test |
| API-010-02 | Assembly/STT/Diarization 실행 중 | current processing stage/progress 반환, partial review arrays 비어 있음 | mapper integration test |
| API-010-03 | PROCESSING_FAILED at TRANSCRIPTION | 실패 status/stage/last completed progress, Audio 만료 metadata와 허용 action, 내부 오류 원문 없음 | failure response test; retry action details in TASK-017.02 |
| API-010-04 | REVIEW 완료, Speakers/Transcript/Minutes 저장 | 같은 version snapshot의 전체 review data 및 allowedActions | response contract test |
| API-010-05 | Review speaker mapping/Minutes update 후 조회 | 증가한 version과 갱신된 data/actions | application integration test |
| API-010-06 | API-011~014 action별 허용/불허 상태 | REVIEW allowedActions에 현재 유효 action만 포함 | action mapper test |
| API-010-07 | PROCESSING/REVIEW 이전 상태 또는 COMPLETED_WITH_WARNINGS terminal status | 허용 action 외에는 노출하지 않으며 partial content는 계속 숨김 | state gate test |
| API-010-08 | 없는 Session 또는 memory restart 이후 조회 | HTTP 404 `SESSION_NOT_FOUND` common error envelope | Session store/controller test |
| API-010-09 | 동일 Session 연속 GET | response fields 동일, version/state 저장 변화 없음 | read-only query test |
| API-010-10 | GET과 Session update가 동시 발생 | payload 내 version/fields가 같은 consistent snapshot | concurrency/snapshot test |
| API-010-11 | sentinel Transcript/Minutes/Secret/provider error in Session | API log/error response에 민감 sentinel 없음 | privacy assertion |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 통합/수동 QA

- API-009 뒤 각 processing stage와 실패 stage를 조회해 progress/status 응답이 UI에 유용한지 확인한다.
- Minutes 완료 전 Transcript/Minutes partial field가 보이지 않고 완료 후 Review 전체 payload가 반환되는지 확인한다.
- API-011/012 변경 후 version/action 갱신, Session 없음 404 및 반복 GET read-only를 확인한다.
- 일반 로그와 응답에서 Transcript 원문, Minutes, provider error 원문, Secret이 노출되지 않는지 확인한다.

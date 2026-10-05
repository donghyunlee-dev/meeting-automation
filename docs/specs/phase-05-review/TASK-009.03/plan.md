# Minutes 재생성 pipeline 회귀 검증 계획

## 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `DEC-011`, `FR-014`, `API-010`, `API-013`, `TASK-009.03`
- TASK-009.01 Issue [#40](https://github.com/donghyunlee-dev/meeting-automation/issues/40): API-013 command, 비동기 완료/rollback, `lastOperation`
- TASK-009.02 Issue [#41](https://github.com/donghyunlee-dev/meeting-automation/issues/41): Review UI의 API request/poll contract
- TASK-006.02 Issue [#28](https://github.com/donghyunlee-dev/meeting-automation/issues/28): Transcription provider port
- TASK-006.03 Issue [#29](https://github.com/donghyunlee-dev/meeting-automation/issues/29): diarization adapter/normalization
- TASK-006.04 Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30): Audio/STT/Diarization pipeline runner
- TASK-006.05 Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31): Minutes generation output

## 검증 경계

- API integration fixture: Spring web context + real API-013 controller/application/job registration/Session repository; 외부망 없는 fake `MinutesGenerationProvider`/Template catalog
- 계측 port: Audio assembly, Audio source read/storage fetch, `TranscriptionProvider`, `DiarizationProvider`, `MinutesGenerationProvider`
- 배경 worker 동기화: production job executor contract를 유지하며 test executor drain/latch 또는 worker completion signal로 terminal state를 기다림. timer sleep로 간헐적인 통과를 만들지 않음
- 관측 결과: REST status/body, call count/order, Session version/Transcript/Speaker/Minutes snapshot, redacted logs

## 구현 순서

1. API-013 route/application/job runner와 API-009 initial pipeline wiring, 실제 dependency injection module 및 test executor를 확인한다. 결과: test가 통과해야 하는 end-to-end composition root와 각 spy seam을 기록한다.
2. API integration fixture로 Session REVIEW baseline과 deterministic fake Minutes provider를 준비하고 pipeline ports를 instrument한다. 결과: fixtures는 오직 식별자/짧은 합성 text를 포함하며 raw data를 log하지 않는다.
3. API-013 202 뒤 worker terminal signal을 기다리는 success test를 먼저 만든다. 결과: Minutes generation은 예상 횟수, Audio assembly/STT/Diarization은 각각 0, API-010은 요청 Template의 새 Review snapshot을 보인다.
4. fake Minutes provider failure와 idempotency replay/duplicate delivery cases를 추가한다. 결과: failure rollback snapshot이 유지되고 operation당 generator 호출이 중복되지 않으며 STT/Diarization 0회다.
5. Transcript/Speaker unchanged, Session version/status, API-010 `lastOperation`, API adapter response와 operation log redaction을 assertion한다. 결과: task 00901 contract와 end-to-end 경계가 함께 검증된다.
6. 같은 test instrumentation을 initial API-009 pipeline positive control에 적용한다. 결과: 정상 initial processing에서 assembly → STT → Diarization 각 한 번 호출과 의도된 순서가 나타난다.
7. test module의 기존 Backend test/lint/build 검증을 실행한다. 결과: 재현 명령, executor timeout/result, call traces(횟수만)와 report를 Issue #42에 기록한다.

## 변경 후보

- Backend `src/test/...` API/application integration test package 및 test fixture/config
- Audio assembly/storage, `TranscriptionProvider`, `DiarizationProvider`, `MinutesGenerationProvider`용 counting spy/fake
- 비동기 job executor를 결정적으로 drain/wait하는 기존 test helper 재사용 또는 제한된 test-only helper
- 회귀 test가 요구하는 seam이 없어 추가가 필요하면 최소한의 test configuration seam만 추가; production routing/pipeline behavior는 바꾸지 않음

실제 module/package/wrapper 경로는 구현 checkout에서 저장소 구성을 확인한 뒤 확정한다. 이 Task의 성공은 통합 검증 코드와 반복 가능한 증거이지 production code path 변경이 아니다.

## 검증 명령

기존 Phase 4 Backend SDD가 정의한 명령은 backend root의 `./gradlew test`, 전체 확인의 `./gradlew clean build`다. 구현 checkout에서 실제 Gradle wrapper/module 위치와 task filter를 확인해 정확한 working directory/command를 Issue #42에 남긴다. 외부 API key/Audio file 없이 test fake만 쓴다.

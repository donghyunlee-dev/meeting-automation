# 구현 계획

## 선행 조건

- TASK-019.01 Issue #69 remote Evidence: 3인 fixture/scorecard/provider baseline과 세 run
- TASK-019.02 Issue #71 remote Evidence: 5인 fixture/scorecard/provider baseline과 paired setup deviation
- TASK-006.02 Issue #28: `TranscriptionProvider`, configuration-selected model, typed provider-neutral output
- TASK-006.03 Issue #29: deterministic standard speaker/segment normalization
- TASK-006.04 Issue #30: stage failure/safe error/log contract
- TASK-006.05 Issue #31: unchanged EXT-002 StructuredMinutes contract
- PRD v1.8.1, `EXT-001`, `EXT-002`, `DEC-008~010`

두 baseline Evidence나 config identity가 없으면 후보 tuning을 시작하지 않는다. 선행 측정값이 원격에서 사용 가능해야 변경 선택 기준을 적용할 수 있다.

## 변경 경계

| 소유 영역 | 범위 | 확인 결과 |
|---|---|---|
| Backend provider setting | 기존 `TRANSCRIPTION_MODEL`/지원 설정값 및 secret-free runtime config | candidate identity와 변경 범위가 고정됨 |
| `TranscriptionProvider` adapter | 기존 port, `ProviderTranscriptionResponse` 유지 | standard speakers/segments와 safe errors 비회귀 |
| Processing/normalization | 필요 결함에 한한 EXT-001 integration | TASK-006.03 normalized output이 유효 |
| EXT-002 Minutes | 설정/Adapter 변경 금지; 고정 contract/mock regression만 | `StructuredMinutes` schema/roster reference 유지 |
| QA fixture/scorecard | `.019.01/.02` frozen input/metric만 사용 | paired baseline와 후보 결과 재현 가능 |
| Logs/Evidence | safe metadata/aggregate only | Audio/Transcript/Secret/raw provider output 미노출 |

한 candidate는 one-factor change다. 모델, prompt, language hint, sampling/decoding configuration 중 어떤 값을 바꿨는지 정확히 기록하고 동시에 여러 dimension을 바꿔 causal interpretation을 흐리지 않는다. Provider port/API schema/secret source는 바꾸지 않는다.

## 변경 순서

- Task .019.01/.02 remote evidence에서 동일 fixture version/hash, 3-run data, scorecard/config baseline 및 setup deviation을 검증한다. 결과: candidate 실험을 시작할 재현 가능한 기준이 있다.
- 설정 한 변수를 바꾸는 최대 세 후보를 등록하고 각 후보가 기존 Provider/API 지원 목록 안에 있는지 확인한다. 결과: 후보 dimension/value/source와 예상 영향이 안전하게 고정된다.
- candidate 전에 fixture playback, fixed transcript/mapping contract, provider adapter standard output/error mapping, EXT-002 StructuredMinutes schema와 quality scorecard baseline regression test를 작성해 현 설정에서 통과시킨다.
- candidate가 code-path를 바꾸면 관련 failing regression test를 먼저 작성해 실패를 재현한다. 결과: 이전 호환성 문제를 테스트가 증명한다.
- 한 차원만 수정/config override하고 최소 구현으로 관련 failing test를 통과시킨다. 결과: config resolution 및 provider-neutral output 계약은 변경되지 않는다.
- 각 baseline/candidate를 같은 three repeats/3인+5인 fixture로 실행한다. 후보 사이 fixture, MIME, Backend commit, processing setting 외 변수를 바꾸지 않는다. 결과: run/session/config identity가 paired group별로 정리된다.
- CER/WER/DER/mapping과 variation을 같은 scorecard로 계산하고 fixture별 Pareto 규칙을 적용한다. 결과: adopt/reject/inconclusive, baseline 유지 결정 및 이유가 남는다.
- 채택 후보가 있으면 API-010 data, normalized speaker refs, EXT-002 downstream contract와 failure/log/privacy regression을 실행한다. 결과: 전후 API/data behavior가 같은 계약을 만족한다.
- 채택 후보만 Android/iOS support matrix에 해당 조합의 sample audio mode/provider output compatibility를 재검증한다. 결과: 실제 검증한 조합과 미실행 환경이 구별된다.
- 기존 project test/lint/build 명령을 Backend root package/Gradle task/CI에서 확인해 실행한다. 결과: command, version, commit, outputs를 Evidence에 남긴다.
- 결과와 한계, configuration rollback reference, QA Audio cleanup/no notification Evidence를 기록한다. 결과: Release 단계가 검증 가능한 commit/config로 추적할 수 있다.

## 테스트 우선 순서

- Pure config resolution 및 provider settings: current accepted config test → 후보 config test fail → 최소 config change → green regression.
- Provider response: fixed Audio/response fixture로 port output, label references, timestamps, no-speech, safe failure tests를 선행한다.
- EXT-002: 같은 fixed normalized Transcript/mapping에서 StructuredMinutes mock schema/reference/unsupported factual field regression을 유지한다. EXT-002 model/prompt config는 변경하지 않는다.
- Integration: pipeline `TRANSCRIPTION`→`DIARIZATION` 순서, REVIEW snapshot visibility, `PROCESSING_FAILED` safe behavior 및 no sensitive log 검증을 끝에 실행한다.

## 완료 정의

한 후보가 채택되면 비회귀 metric/계약과 두 fixture의 field-like run을 보인다. 후보가 없거나 결과가 불확실해 baseline을 유지할 때는 이를 명확한 결과로 기록하고 추가 후보 검토는 별도 Issue로 남긴다. 변경 이유, versioned config, failed tests, green regression, support limit 및 cleanup 기록이 없는 candidate는 release config로 승격하지 않는다.

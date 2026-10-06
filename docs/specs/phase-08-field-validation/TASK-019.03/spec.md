# STT·화자 설정 튜닝과 회귀 검증

## 목표

TASK-019.01/019.02의 고정 3인·5인 fixture에서 `EXT-001` transcription/diarization provider 설정 후보를 시험하고, text/diarization 품질 악화 없이 개선되는 설정만 채택한다. 고정 fixture에서 후보가 baseline을 지배하지 못하면 현재 설정을 유지하며 측정 결과와 한계를 남긴다.

PRD v1.8.1 (2026-10-06), `TASK-019.03`, `EXT-001`, `EXT-002`, `DEC-008~010`을 구체화한다. Issue [#72](https://github.com/donghyunlee-dev/meeting-automation/issues/72). 직접 선행은 TASK-019.01 Issue #69 및 TASK-019.02 Issue #71이다.

## 범위

- 3인/5인 fixture, CER/WER, diarization 구성요소/DER, mapping completion scorecard와 baseline config hash를 고정
- 기존 `TranscriptionProvider` port 안의 deployment `TRANSCRIPTION_MODEL`/기존 지원 설정 후보만 제한적으로 비교
- 각 후보는 한 번에 한 config dimension만 바꿔 동일 3인 및 5인 fixture에서 각각 같은 세 번의 독립 run 수행
- 후보와 baseline의 run별, median/range 및 fixture별 차이를 비교해 Pareto non-regression 기준 적용
- 선택된 설정의 model/config version, 변경 이유, scorecard, 회귀 결과 및 한계를 기록
- `EXT-002` Minutes provider/model/prompt는 튜닝하지 않는다. 고정 Transcript/Mapping으로 기존 Structured Minutes schema/generation integration이 비회귀인지 contract/mock test로 확인한다.
- 회귀 Evidence를 `docs/evidence/TASK-019.03.md`에 작성하고 synthetic Audio/object 정리 및 private failure recovery를 확인

## 비범위

- 5인/3인 fixture/ground-truth/scorecard 정의 변경
- 새 Provider port/Adapter/API/data field, model-specific domain contract 또는 새로운 AI platform 도입
- EXT-002 Minutes generation 품질/설정 튜닝
- 여러 품질 지표 사이 tradeoff를 제품 임계값이나 임의 가중 평균으로 해결
- 자동 실명/voiceprint, DEC-009/010을 바꾸는 speaker mapping 방식
- Minutes/Transcript/Audio 원문, Secret 또는 provider response를 Issue/Evidence에 기록

## 후보 채택 규칙

PRD는 absolute CER/WER/DER/mapping threshold와 지표 우선순위를 정하지 않았다. 이 작업은 한 metric만 개선하면서 다른 metric을 악화시키는 후보를 채택하지 않는다.

- 오류 지표: CER/WER/DER와 missed/false-alarm/speaker-confusion components는 낮을수록 좋다. Mapping completion은 높을수록 좋다.
- Median comparison은 동일 fixture와 세 반복의 결과를 baseline/후보별로 계산한다. 3인과 5인 결과는 각각 독립 비교하고 합쳐 평균을 내지 않는다.
- 한 후보가 양쪽 fixture 모두에서 CER/WER/DER aggregate를 baseline보다 높이지 않고 mapping completion을 낮추지 않아야 한다. 이 조건에서 CER 또는 DER가 적어도 한 fixture에서 낮아지고 나머지 주요 지표가 유지될 때만 non-regressive improvement로 채택한다.
- 한 metric이 좋아도 다른 지표나 다른 fixture가 악화되면 후보를 거부한다. 모든 후보가 거부되면 baseline 설정을 유지하는 것이 완료 결과다. 절대 숫자 cutoff나 지표 가중치로 tradeoff를 숨기지 않는다.
- 세 반복의 분산/range는 함께 공개한다. 변화가 run 변동보다 작거나 결론이 엇갈리면 “개선 확정”이 아니라 inconclusive로 표시하고 현 설정을 유지한다.
- 한 번의 실험에서 최대 세 개 후보 configuration을 평가한다. 이후 추가 탐색은 새 근거/추적 작업으로 등록한다.

## EXT-002 회귀 경계

EXT-002는 튜닝 대상이 아니지만 PRD Related IDs이므로, 고정된 `StructuredMinutes` fixture contract가 유지되는지 확인한다. EXT-001 설정 후보로 얻은 normalized Transcript와 고정 Participant/Speaker mappings을 동일한 non-production EXT-002 mock/contract boundary에 연결하고 schema validation, 유효 roster reference 및 Transcript 근거 없는 담당자/날짜 생성 금지를 검증한다. Minutes model/config 변경이나 실제 output 품질 최적화는 `.019.03`에 포함하지 않는다.

## 수용 기준

- 원격 Evidence에 3인/5인 고정 fixture hash, scorecard revision, baseline provider/model/config identity와 기존 지표가 있다.
- 최대 세 후보를 한 dimension씩 변경해 양 fixture에서 각각 세 반복 평가하고 baseline/후보의 metric medians/range를 남긴다.
- 채택 설정은 양 fixture에서 오류 metric이 baseline을 악화시키지 않고 mapping completion/EXT-002/API/data contract가 비회귀이며 적어도 한 CER/DER 개선을 보인다. 그렇지 않은 경우 baseline 유지 또는 inconclusive로 판정한다.
- 코드가 바뀌면 변경 전에 failing regression test를 추가하고 provider port, EXT-001 표준 output, EXT-002 StructuredMinutes 및 DEC-008~010 계약을 test-first/green regression으로 보존한다.
- provider 설정/model version 변경은 Backend configuration 경계에만 적용되며 새 model-specific schema/API 계약을 만들지 않는다.
- 결과에 fixture/config/commit/test run 연결과 알려진 한계가 있고, Issue/Evidence/log에는 Audio, Transcript, Secret, Provider raw output이 없다.
- success Audio가 정리되고 processing failure는 private object storage/retry/download/discard/24h cleanup 및 attendee Email/Slack 금지 계약을 따른다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [3인 baseline/metric](../TASK-019.01/spec.md)
- [5인 비교 baseline](../TASK-019.02/spec.md)
- [TranscriptionProvider 계약](../../phase-04-processing/TASK-006.02/spec.md)
- [Processing pipeline 계약](../../phase-04-processing/TASK-006.04/spec.md)
- [Minutes generation contract](../../phase-04-processing/TASK-006.05/spec.md)

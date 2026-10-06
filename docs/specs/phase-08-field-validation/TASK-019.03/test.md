# 검증 계획

## 자동화 회귀

기존 Backend root의 실제 `./gradlew test` 및 저장소 CI에서 설정한 해당 모듈 test/lint/build 명령을 확인해 실행한다. 테스트를 먼저 추가한다. 후보가 설정 전용이면 지원되는 설정/DI binding/profile resolution 검증을 포함하고, adapter code가 바뀌면 fixed response contract 테스트를 먼저 red로 만든다.

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| CFG-BASELINE | 현재 `TRANSCRIPTION_MODEL` 및 accepted provider config | 기존 환경이 누락/오염된 Secret 없이 provider 선택을 resolve | sanitized config key/model identifier |
| EXT-001-CONTRACT | 고정 Audio/provider response fixture | `TranscriptionProvider` output, speaker labels, timestamps, no-speech/safe errors 및 normalizer ID/정렬이 계약대로 유지 | test case/result |
| EXT-002-CONTRACT | frozen normalized Transcript, roster mapping, default/project template mock input | 기존 StructuredMinutes DTO/schema, roster references, 근거없는 owner/date 차단 유지; settings unchanged | contract test/result |
| PROCESSING-PIPELINE | config 후보/provider 오류 및 success fixture | `AUDIO_ASSEMBLY`→`TRANSCRIPTION`→`DIARIZATION` 순서, safe failure stage/retryability, API-010 snapshot 유지 | application test/status |
| REVIEW-MAPPING | 표준 Speaker list 및 versioned mapping fixture | no real-name inference, one mapping per speaker, 해당 speaker 모든 segment propagation | API/UI regression |
| PRIVACY-LOGS | synthetic sensitive canary in provider/test fixtures | Audio, Transcript, Secret, raw provider response가 API/error/log에 없음 | sanitized assertion |
| CONFIG-ROLLBACK | 후보 설정을 이전 baseline으로 되돌림 | baseline profile로 재선택 가능하고 candidate artifact/output이 baseline을 덮지 않음 | config commit/key value identity |

code path 변경이 없으면 불필요한 application regression suite를 새로 만들지 않는다. 설정 변경이라도 `CFG-BASELINE`, `EXT-001-CONTRACT`, `EXT-002-CONTRACT`를 실행한다.

## Candidate fixture 비교

모든 run은 TASK-019.01/02의 frozen 3인/5인 version/hash, room/device/browser/MIME/API/backend commit/scorecard revision을 사용한다. 후보마다 하나의 config dimension만 바꾸고 candidate별로 fixture당 세 번 독립 Session을 수행한다.

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| CANDIDATE-REGISTRY | baseline + 최대 3 candidate의 config diff | 지원 Provider config 범위에 있고 한 번에 한 dimension만 바뀜 | diff ID/rollback reference |
| BASELINE-3P/5P | 기존 config로 양 frozen fixture 각각 3회 처리 | .019.01/.02 원격 baseline을 같은 scorecard로 재현하거나 config drift를 blocker 기록 | run IDs/hash/per metric |
| CANDIDATE-3P/5P | 각 candidate로 동일 fixture/횟수 처리 | provider/model/API/config hash가 run 그룹마다 고정되고 변경 외 조건 동일 | candidate run IDs |
| CANDIDATE-METRICS | CER/WER/DER components/mapping completion 계산 | per run, median, min/max와 fixture별 baseline delta가 같은 formula로 계산 | scorecard revision/results |
| PARETO-DECISION | CER/WER/DER error totals와 mapping completion 양 fixture 비교 | 두 fixture 어느 쪽도 나빠지지 않고 CER/DER 중 하나가 개선될 때만 채택; 아니면 baseline/inconclusive | decision table/reason |
| VARIANCE-CHECK | 세 독립 반복의 score range와 후보 delta 비교 | 개선 방향이 불안정하거나 run variation과 구분되지 않으면 확정 개선으로 표시하지 않음 | per-run ranges |
| FIELD-REPLAY | 선택/유지 설정으로 Android Chrome 및 iOS Safari에서 영향 run smoke | 녹음/MIME/Chunk/API handoff 호환성이 지원 matrix 안에서 유지 | device/MIME/result |
| EXT-002-NO-TUNE | fixed Transcript/mapping에 기존 EXT-002 config/mock contract 적용 | EXT-002 model/prompt unchanged, DTO/validation non-regression | unchanged config fingerprint + result |
| FAILURE-RETENTION | controlled provider failure | safe retry state, private Audio recovery/discard rules, max-24h expiry and no attendee delivery | category/action/object/message count |
| CLEANUP | 성공 Session Review와 실패 QA Session disposition 완료 | 성공 Audio 삭제, 실패 retention 후 cleanup, fixture/object 수량 zero | counts/timestamps only |

## 채택 판정

- 오류 지표 방향은 CER/WER/DER 및 세 error components 감소, mapping completion 증가는 개선 방향이다.
- 같은 scorecard/세 repetition median을 양 fixture에서 개별 비교한다. Candidate는 어떤 fixture에서도 오류 metric이 baseline을 넘지 않고 mapping completion이 내려가지 않아야 한다.
- 채택은 CER 또는 DER 합산값이 적어도 한 fixture에서 감소하고 다른 fixture의 CER/WER/DER와 mapping completion이 악화되지 않으며, API-010/011/EXT-002 contracts가 green일 때만 가능하다.
- 기울기/작은 차이가 세-run variability와 섞이면 inconclusive. 이 경우 현재 설정을 유지하고 metric/limitation만 보고한다. 임의 숫자 threshold/지표 weighting은 사용하지 않는다.
- candidate가 한 지표를 크게 개선하고 다른 지표를 조금 악화하는 경우에도 채택하지 않는다. 이는 추가 명시적 PRD decision이 생기기 전까지 baseline 유지 사유다.

## Evidence와 보안

- 위치: `docs/evidence/TASK-019.03.md`
- 허용: config/model identifier, config hash sans secrets, commit/build/test command result, fixture IDs/hashes, run/session ID, metric aggregate/range, candidate decision, support limitation, cleanup count.
- 금지: Audio/transcript/speaker voice, participant name/email, provider raw response, key/secret/auth header value, credential-bearing URL.
- 실제 Audio 실패 시 기존 private storage/retry/download/discard/max-24h contract를 확인하고 failure minutes/email/slack을 QA participant에게 보내지 않는다. Evidence에는 object count/expiry/outcome만 기록한다.

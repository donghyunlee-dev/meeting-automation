# 검증 계획

## 선행 조건

- TASK-006.06 Issue #32: API-010 status/version 및 완료 Review Speaker/Transcript snapshot
- TASK-006.02/#28: 설정 기반 TranscriptionProvider typed response; 모델 ID를 영구 계약으로 고정하지 않음
- TASK-006.03/#29: deterministic `speakerId`/`segmentId`, timestamp, 정렬 및 overlap 규칙
- TASK-018.04 Issue #68: 검증된 모바일 녹음 조합 및 해결된 compatibility 결함
- PRD v1.8.1 (2026-10-06), `DEC-007~010`, `FR-007`, `FR-010`, Section 21
- license/재사용 승인이 확인된 synthetic Korean voices와 private non-production QA environment

Phase 8/Provider 설정 선행 작업이 실행되지 않았거나 재현 가능한 iPhone/Android 경로가 없으면 그 결과를 blocker로 기록한다. 임의의 모델/코덱 결과를 기준선으로 대신하지 않는다.

## 책임과 소유권

| 영역 | 담당 | 산출 결과 |
|---|---|---|
| Script와 ground truth | QA | transcript, turn start/end, anonymous voice ID 검수와 fixture 버전 |
| Audio capture | QA | 중앙 스마트폰 녹음, room/playback/device conditions 및 MIME 기록 |
| Provider/Processing 결과 | BE/QA | model/config 고정, 처리 결과와 안전 실패 원인 category |
| 품질 평가 | QA | CER/WER, DER 구성 요소, mapping completion, 3회 변동 계산 |
| Review mapping | FE/QA | Speaker 단위 mapping 동작, API-011 저장/API-010 재조회 및 segment 전파 |
| Evidence/retention | QA | aggregate 지표만 기록, Audio private 보존/정리와 notification suppression |

이 작업은 품질 기준선을 측정한다. FE/BE code/provider settings는 수정하지 않는다. 발견된 구현 결함은 `.019.03` tuning/회귀 task로 넘기되 data loss, secret leak 같은 차단 문제는 별도 bug Issue로 분리한다.

## 실행 순서

- 선행 Issue/Evidence에서 검증된 녹음 조합, MIME, chunk policy 및 호환성 blocker가 해결됐는지 확인한다. 결과: 고정할 baseline environment가 정해진다.
- 세 voice의 scripted dialogue, 정답 transcript, non-overlap turn annotation과 optional challenge segments를 검수한다. 결과: fixture ID/version/hash와 scorecard를 freeze한다.
- 세 개 playback source 위치/거리와 phone 중앙 placement를 고정한다. room/noise, capture device/OS/browser/build, microphone permission, API policy 및 Session metadata를 기록한다.
- 시험 전에 동일 녹음을 처리할 `TRANSCRIPTION_MODEL`, Provider API version, language hint, prompt/config hash와 관련 설정을 snapshot으로 기록한다. 3회 실행 중 설정을 바꾸지 않는다.
- 같은 fixture/config로 독립 QA Session 세 개를 실행해 녹음/processing을 마친다. run마다 합성 오디오만 처리하며 실제 참석자 전달은 꺼 둔다.
- Provider Transcript를 ground truth와 대조해 CER/WER를 계산한다. diarization output은 Task 006.03 표준 Speaker/segment 결과를 수신해 reference interval에 맞춰 error components/DER를 계산한다.
- Reviewer가 익명 Speaker를 roster의 3 participant에 한 번씩 mapping하고 API-011로 저장한다. API-010를 새로 읽어 completion/error 및 전체 matching segment propagation을 확인한다.
- API-010이 REVIEW 이전 partial result를 숨기고 완료 version snapshot이 일관적인지 확인한다. Provider/raw 오류/Transcript가 일반 log/API error에 포함되지 않았는지 synthetic canary로 검사한다.
- 각 successful Session은 Review 진입 후 Audio 정리를 확인한다. 실패하면 사용자에게 명시적인 retry/download/discard UX를 시험하고 private object 보존 시각/24h 상한을 확인한 뒤 QA object를 정리한다. 참석자 알림 전송이 없음을 확인한다.
- 세 run의 aggregate 결과/변동 및 측정 방법/한계를 Evidence로 작성하고 5인 테스트 TASK-019.02가 그대로 재사용할 scorecard와 source fixture를 연결한다.

## 인터페이스와 검증 순서

- Audio input은 TASK-005.08에서 API-009 처리에 전달된 Session object/MIME를 이용하고 Provider input은 TASK-006.02 `TranscriptionCommand`/`ProviderTranscriptionResponse` 계약을 따른다.
- Provider label normalization은 TASK-006.03의 결정적인 `speakerId` 및 시간순 segment를 사용한다. 평가용 anonymized ID permutation은 metric 계산 전용이며 운영 Speaker ID를 수정하지 않는다.
- Review Speaker/Transcript는 TASK-006.06 API-010 snapshot, user mapping은 PRD API-011 `If-Match`/version contract를 사용한다.
- Pipeline/provider code가 달라지면 성능 baseline이 오염되므로 이 Task에서 변경하지 않는다. 결함은 증거화해 후속 tuning task로 이관한다.
- 외부 provider 비용은 고정 3회 run만 사용한다. 실패 Session 사용자 action 재시도는 별도 명시 승인된 QA 결함 재현이 필요할 때만 수행하고 횟수/시각을 기록한다.

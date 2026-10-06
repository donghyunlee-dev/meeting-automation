# 검증 계획

## 자동화 테스트

이 task는 3인/5인 실제 mobile acoustic path의 QA 측정이며 애플리케이션 코드를 수정하지 않는다. 자동화된 app test를 추가하거나 실행하지 않는다. Scorecard 정의/정규화는 TASK-019.01 고정 known-answer check를 재사용하고 본 fixture 결과는 동일 reviewer 절차로 독립 재계산한다. 발견된 기능 회귀는 TASK-019.03/bug Issue로 분리한다.

## 통제 조건

- 한국어 fixture 6~10분, 비중첩 primary speech, five distinct licensed TTS voices, 사람 검수 reference text/turn intervals/anonymous identity.
- Task 019.01의 동일 room, phone/table position, model/OS/browser, app/backend build, MIME/API upload policy, STT provider/API/model/language/prompt hash, noise/power/recording level을 사용한다.
- 세 개 독립 Session run, Task 019.01과 같은 3회 반복 수, normalization/metric formula 및 reviewer mapping process.
- 두 fixture의 room/playback position, word count/speech rate/turn count 분포가 어디서 일치/상이한지 run sheet에 기록한다.

## 수동 QA 행렬

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| BASELINE-3P-REMOTE | Issue #69 및 remote Evidence에서 config/metric/version/run totals 조회 | baseline source commit와 scorecard revision이 5인 comparison에 고정됨 | URL/commit/hash/aggregate only |
| FIXTURE-5P | frozen five-voice script/reference로 room capture setup | 정확히 5 anonymous identities, expected text/turns 및 licensed voice source 기록 | fixture ID/version/hash, duration, setup |
| MATCH-ENV | phone/room/browser/MIME/provider config 및 acoustic level 비교 | 일치값/명시적 deviation이 run 전에 기록되어 이후 delta를 추적 가능 | paired run sheet |
| PROCESS-5P-01..03 | 동일 fixture/config 세 독립 Session 처리 | model/provider/build drift 없이 status/result가 독립 추적됨 | Session IDs, config hash, stage outcome |
| CER-WER-5P | 세 output을 TASK-019.01 동일 방법으로 비교 | run별 CER/WER 및 reference denominator 계산 | scorecard revision/result |
| DIARIZATION-5P | predicted intervals와 reference identities를 max-overlap 일대일 매칭 | missed/false-alarm/confusion/DER 결과와 reference duration 분모가 기록 | aggregate duration values |
| MAP-5P | 5개 Speaker label과 roster를 Reviewer가 API-011에 mapping | correct unique mapping count/5 및 wrong/duplicate/unmapped counts | fraction, API version |
| MAP-PROPAGATION-5P | API-010 재조회 후 speaker segment별 participant label 검토 | 한 Speaker의 전체 segments가 같은 Participant를 표시하고 다른 speaker는 영향 없음 | counts/version only |
| COMPARE-3P-5P | 양쪽 run별/min/mean/max와 baseline delta 표 생성 | 실험 조건 deviation과 metric 차이가 분리돼 기록 | comparison table/fixture IDs |
| AUDIO-RETENTION-5P | success Review cleanup 및 isolated failure action 확인 | success object cleanup, failure private max-24h path, attendee email/Slack 0회 | expiry/object/message count |
| FIXTURE-CLEANUP-5P | processing 종료/실패 후 QA assets 제거 | QA Audio object 0 또는 격리 cleanup failure follow-up | aggregate count only |

## 성공과 해석

- 완료 조건: three-run 5인 CER/WER/DER/mapping metric 및 variation, API-010/011 mapping contract, remote 3인 baseline comparison, environment deviations, retention/privacy Evidence가 모두 존재한다.
- 결함: 3인 scorecard와 다른 정의 적용, config drift, invalid speaker references, map propagation failure, privacy/retention violation, Transcript/audio/Secret log exposure.
- 5인 수치가 3인 수치보다 악화되더라도 임계값이 없으므로 이 task에서는 자체로 fail cutoff를 만들지 않는다. 정확히 측정해 TASK-019.03이 tuning target을 판단하게 한다.
- room/device/model 등의 조건이 다르면 raw delta는 제공하되 인원 수 증가의 인과 효과로 단정하지 않는다. 미검증 조건은 Evidence에 미검증으로 표시한다.
- overlap challenge 결과는 primary non-overlap DER와 분리한다.

## Evidence 및 cleanup

- 위치: `docs/evidence/TASK-019.02.md`
- 허용: fixture/source hash, build/device/OS/browser/MIME/config version, Session IDs, CER/WER/DER aggregates, mapping completion, trial ranges, condition deviations, cleanup/message counts.
- 금지: Audio/Transcript/ground-truth dialogue/voice files, 개인 이름/Email, provider response, Secret/credential, storage URLs.
- Synthetic source/rendered Audio는 제한된 QA storage에만 두고 success REVIEW 뒤 삭제한다. 처리 실패 object는 기존 private retry/download/discard/max-24h retention을 따른다. 실패 회의록/Email/Slack은 attendees에게 보내지 않는다.

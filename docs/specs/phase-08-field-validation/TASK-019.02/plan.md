# 검증 계획

## 선행 조건

- TASK-019.01 Issue #69: approved 3-person fixture, metric definitions, config identity, three-run baseline and scorecard
- TASK-006.06 Issue #32: complete API-010 Review snapshot and speaker/segment contract
- TASK-006.03 Issue #29: provider-neutral deterministic speaker/segment contract
- TASK-018.04 Issue #68: usable mobile recording configuration and documented field limits
- PRD v1.8.1, `DEC-007~010`, `FR-007`, `FR-010`, Section 21
- five individually licensed synthetic Korean voices and protected non-production QA storage

3인 baseline가 원격 Evidence에서 확인되지 않거나 이를 만든 설정/metric version을 재현할 수 없으면 fixture 실행을 임의 baseline으로 대체하지 않고 차단 사유를 기록한다.

## 역할과 경계

| 영역 | 담당 | 결과 |
|---|---|---|
| 5인 script/정답 | QA | 익명 화자별 text/turn, fixture version/hash |
| 물리 녹음 | QA | 동일 room/phone/browser 조건, source position/level 기록 |
| STT/diarization processing | BE/QA | 같은 provider/model/prompt config와 표준 결과 |
| 점수/비교 | QA | 5인 지표, 3인 대비 delta/variation/deviation |
| Review mapping | FE/QA | 5개 Speaker 매핑 및 segment 전파 |
| privacy/retention | QA | aggregate Evidence, Audio cleanup/24h 실패 경로, 무전달 확인 |

이 task는 측정만 하며 provider/prompt/UI/Backend 구현은 변경하지 않는다. 관찰된 결함은 TASK-019.03 또는 별도 bug Issue로 넘기되 data privacy/handoff breach는 blocker로 분리한다.

## 실행 순서

- Issue #69 및 `docs/evidence/TASK-019.01.md`에서 scorecard, fixture, provider/API config hash, 세 run baseline, device/browser/MIME 조건을 확인한다. 결과: 기준점이 재현 가능하다.
- 기존 3인과 같은 길이/언어/유사 script complexity/turn 분포를 가진 5인 scripted fixture와 사람 검수 ground truth를 만든다. 결과: 다섯 voice code, exact text, interval annotation, version/hash가 고정된다.
- 동일 room, central phone, 5 source positions, 각 거리/level, device/OS/browser/build, MIME/policy와 network를 측정한다. 3인 capture에서 일치하지 않는 값은 명시한다.
- 3인 baseline의 provider/model/API/language/prompt config hash와 app/backend commit을 그대로 고정한다. 결과: provider setting drift가 없다.
- 동일 fixture/config의 3개 독립 Session으로 녹음/processing을 실행한다. run별 output, 실패 stage 및 condition을 기록한다.
- TASK-019.01과 동일 CER/WER normalization, reference-interval speaker scoring, one-to-one anonymous label assignment와 mapping completion 식을 사용해 run 점수를 계산한다.
- 동일 환경으로 확인된 3인 수치와 5인 수치를 나란히 놓고 average/min/max, absolute delta와 deviation을 기록한다. 환경 불일치 delta는 설명/원인 추정을 분리한다.
- Reviewer가 expected 5 speaker를 roster의 5 participant에 한번씩 mapping하고 API-011 저장 후 API-010 재조회/전체 segment 전파를 확인한다.
- API-010 partial-content/privacy/log 경계와 성공 Audio cleanup을 확인한다. 실패 시 private storage 24h max/recovery action/no attendee delivery를 QA 전용으로 검증한다.
- `.019.03`에 전달할 scorecard/data/repeat limitation을 `docs/evidence/TASK-019.02.md`에 요약하고 synthetic QA fixture/object를 정리한다.

## 인터페이스

- STT 입력은 API-009 pipeline과 TASK-006.02 `TranscriptionCommand`의 protected audio/MIME/language/duration 계약을 따른다.
- diarization output은 TASK-006.03 normalized speaker IDs/segments 및 TASK-006.06 API-010 complete REVIEW snapshot에서 비교한다.
- user mapping은 PRD API-011 `If-Match` versioned update 후 API-010 snapshot으로 확인한다. Voice identity는 평가용 anonymous reference만 사용한다.
- Metric 정의는 TASK-019.01의 고정 버전만 소비한다. 새 CER/DER/threshold나 retry strategy를 본 Task에서 추가하지 않는다.

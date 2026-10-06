# 5인 회의 fixture 및 품질 비교

## 목표

TASK-019.01에서 고정한 CER/WER, diarization error 구성 및 Speaker mapping scorecard를 그대로 적용해 5인 한국어 synthetic speech fixture 결과를 수집하고 3인 baseline과 비교한다. 이 task는 인원 수 증가 조건의 관찰값을 만들며 provider 설정이나 pass threshold를 조정하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-019.02`, `DEC-007~010`, `FR-007`, `FR-010`, PRD Section 21을 구체화한다. Issue [#71](https://github.com/donghyunlee-dev/meeting-automation/issues/71). 직접 선행은 TASK-019.01 Issue #69이다.

## 범위

- ground-truth transcript/turn interval/익명 화자 ID가 있는 5인 한국어 합성 음성 fixture
- 3인 run과 같은 비운영 room, smartphone placement, device/OS/browser, MIME/upload policy, provider/model/API/language/prompt 설정으로 녹음 및 처리
- 세 번의 독립 Session run으로 STT CER/WER, missed/false alarm/speaker confusion/DER, Speaker mapping completion 및 run variation 측정
- TASK-019.01의 fixture/config/version/hash/scorecard와 3인 baseline 결과를 원본 기준점으로 사용
- API-010 Speaker/Transcript snapshot, API-011 mapping과 segment 전파 및 sensitive logging/Audio cleanup 확인
- 3인 대비 metric 절대값·차이·변동 및 setup deviation을 `docs/evidence/TASK-019.02.md`에 기록

## 비범위

- provider/model/prompt/API config 수정, threshold 정하기 및 설정 tuning (`TASK-019.03`)
- 새로운 metric 정의, 3인 baseline 재계산/대체
- 자동 실명 인식/voiceprint 또는 speaker 외 segment별 수동 mapping (DEC-009/010 위반)
- Minutes 생성 정확도 및 publish/email/slack 전달
- 실제 회의/참석자 음성, Audio/transcript를 GitHub/Evidence에 저장

## 5인 Fixture와 공정 비교

- 동일 한국어, 같은 6~10분 길이와 유사한 단어 수/발화 속도/turn 수/turn duration 분포로 5개의 개별 licensed synthetic TTS voice를 사용한다. Voice cloning은 하지 않는다. ground-truth speaker ID는 익명 코드만 쓴다.
- 같은 실내 QA room에서 5개의 재생 위치를 중앙 스마트폰 주위에 배치한다. 각 source의 phone까지 거리와 playback level을 run sheet에 기록하고 3인 fixture에서 3개 위치가 담당하는 반경/setting과 맞춘다. 인원 수 외 room/device/environment 변경이 있으면 delta 해석을 제한한다.
- Primary fixture는 non-overlap 발화로 고정하여 기존 DER 계산을 그대로 적용한다. Overlap/noise challenge가 있으면 별도 qualitative 결과로 분리하며 기본 점수에 합산하지 않는다.
- TASK-019.01 Issue #69에서 기록한 fixture/voice license, scorecard revision, model/provider/API/language/prompt config hash, application/backend build, actual MIME, device/OS/browser와 가능한 한 같은 장비를 사용한다.
- 하나라도 일치시킬 수 없는 조건은 3인 baseline과의 비교표에 양쪽 값을 나란히 적고 해당 delta를 인원수 효과로 단정하지 않는다. 모델/설정은 5인 run 중 변경하지 않는다.
- 원음 및 ground-truth transcript는 제한된 QA 저장소만 사용하고 Issue/Evidence에는 fixture ID/version/hash와 aggregate metric만 남긴다. 실패는 private object storage/retry/download/discard 및 24h cleanup 경로, attendee delivery 금지를 따른다.

## 측정 및 해석

- CER/WER text normalization과 edit distance 공식, diarization label permutation 및 DER 구성 요소는 TASK-019.01 `spec.md`/`test.md` 정의를 변경 없이 재사용한다.
- Mapping completion rate는 올바른 고유 mapping을 완료한 expected speaker 수 / 5 × 100으로 계산한다. 중복 Participant, wrong mapping, unmapped 및 누락/추가 predicted speaker를 별도 집계한다.
- 각 metric은 run별 결과, 세 run aggregate(평균과 최소/최대), 같은 조건을 맞춘 3인 baseline 수치, 5인-minus-3인 absolute delta를 기록한다.
- PRD에 절대 CER/DER/mapping 임계값이 없으므로 숫자를 PASS/FAIL cutoff로 만들지 않는다. 결과는 TASK-019.03 설정 조정 및 회귀 판단에 제공한다.

## 수용 기준

- 다섯 익명 voice의 fixture/version/hash, reference transcript/turn interval 및 비교 조건이 재현 가능하다.
- 동일 scorecard/provider config를 고정한 세 run에서 CER/WER, diarization error components/DER, mapping completion과 변동 범위가 계산된다.
- 같은 장비/설정의 3인 baseline 대비 metric delta와 모든 environment deviation이 표에 기록된다. 조건을 맞추지 못한 delta를 인원 수 효과로 확정하지 않는다.
- API-010에서 예상 다섯 speaker의 표준 reference 및 Review snapshot이 일관되고, API-011 변경 후 같은 Speaker의 전체 segment에 한 Participant mapping이 적용된다.
- 자동 실명/voiceprint이 사용되지 않고 transcript/provider 원문/Secret/Audio는 일반 로그/Issue/Evidence에 없다.
- 성공 Audio cleanup, 실패 Audio private/최대 24h recovery 보존 및 후속 정리/no attendee Email·Slack이 확인된다.
- `.019.03`가 5인 고정 fixture를 동일하게 재사용할 수 있는 scorecard, provider config hash 및 limitation을 Evidence에 남긴다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [3인 baseline과 metric 정의](../TASK-019.01/spec.md)
- [3인 test matrix](../TASK-019.01/test.md)
- [Processing/Review API-010](../../phase-04-processing/TASK-006.06/spec.md)
- [Speaker 정규화 contract](../../phase-04-processing/TASK-006.03/spec.md)
- [Phase 8 현장 안정화](../TASK-018.04/spec.md)

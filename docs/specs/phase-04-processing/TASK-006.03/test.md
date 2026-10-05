# Diarization 표준화 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| DIAR-01 | label `p2` 첫 발화가 p1보다 먼저 발생 | p2가 `speaker_a`, p1은 `speaker_b` | ordering fixture test |
| DIAR-02 | 여러 segment가 동일 providerLabel 참조 | 모두 동일 `speakerId` 참조 | identity map test |
| DIAR-03 | 선언된 speaker label에 참조 segment 없음 | 해당 speaker output에서 제외 | unused speaker test |
| DIAR-04 | 같은 start time 또는 out-of-order input segments | stable start/end/input ordinal sorting | chronology test |
| DIAR-05 | 서로 시간 겹치는 두 speaker segments | 둘 다 보존, timestamp/text 불변 | overlap fixture test |
| DIAR-06 | Provider segment ID 누락 또는 중복 | unique sequence-based `seg_*` IDs 생성 | segment identity test |
| DIAR-07 | speakers/segments 모두 empty | 유효한 빈 TranscriptResult | no-speech test |
| DIAR-08 | unknown providerLabel, missing/blank label | safe invalid result; partial Transcript 저장 없음 | reference validation test |
| DIAR-09 | 음수 start, end<start, duration 초과 | validation failure | timestamp boundary test |
| DIAR-10 | 빈 transcript text | validation failure, 원문 로그 없음 | text validation/log test |
| DIAR-11 | 동일 입력 두 번 실행 | 동일 speaker/segment IDs, sort, values | deterministic replay test |
| DIAR-12 | output serialization | Provider label/model-specific fields와 Participant 실명 추론 없음 | standard model contract test |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 수동/통합 QA

- TASK-006.02 provider-neutral fixture를 normalizer에 전달하고 PRD 표준 `TranscriptResult`를 대조한다.
- 겹치는 발화, 말하지 않은 declared speaker, provider label 순서 변경을 포함한 fixture를 검토한다.
- 결과가 API/data model의 speaker_a 형식과 시간 정렬을 사용하고 provider-specific field를 노출하지 않는지 확인한다.

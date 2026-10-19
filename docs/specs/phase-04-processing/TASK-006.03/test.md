> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

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

Backend root에서 `.\gradlew.bat test --tests com.meetingautomation.transcription.DiarizationResultAdapterTests`를 실행하고 결과를 기록한다. 변경한 코드와 직접 관련된 테스트만 실행하며 전체 Backend suite와 `clean build`는 실행하지 않는다.

## 수동/통합 QA

- TASK-006.02 provider-neutral fixture를 normalizer에 전달하고 PRD 표준 `TranscriptResult`를 대조한다.
- 겹치는 발화, 말하지 않은 declared speaker, provider label 순서 변경을 포함한 fixture를 검토한다.
- 결과가 API/data model의 speaker_a 형식과 시간 정렬을 사용하고 provider-specific field를 노출하지 않는지 확인한다.

# Diarization 표준화 작업 항목

1. TASK-006.02 response type 및 PRD Speaker/TranscriptSegment contract를 확인한다. 완료 증거: adapter input/output schema가 일치한다.
2. provider labels/references, overlapping/boundary/empty speaker/no-speech fixture를 준비한다. 완료 증거: 각 수용 기준을 식별하는 자동화 case가 있다.
3. invalid label/time/text validation 테스트를 먼저 작성해 실패를 확인한다.
4. first utterance order로 providerLabel→`speaker_*` mapping을 구현하고 unused speaker를 제외한다. 완료 증거: 반복 label이 같은 internal ID를 참조한다.
5. unique `seg_*` ID 및 stable chronological sort를 구현한다. 완료 증거: 겹치는 구간/time/text가 손상 없이 보존된다.
6. no-speech empty result와 오류/log privacy를 구현한다. 완료 증거: 빈 정상 응답과 invalid response가 구별된다.
7. deterministic replay 테스트와 `./gradlew test`, `./gradlew clean build`를 실행한다. 완료 증거: 변환 결과와 명령 evidence를 남긴다.

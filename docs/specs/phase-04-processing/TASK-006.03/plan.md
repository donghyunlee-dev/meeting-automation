# Diarization 표준화 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `DEC-008`, `FR-008`, `EXT-001`, `TASK-006.03`
- 선행 TASK-006.02 Issue [#28](https://github.com/donghyunlee-dev/meeting-automation/issues/28): label 보존 `ProviderTranscriptionResponse`
- 본 TASK Issue [#29](https://github.com/donghyunlee-dev/meeting-automation/issues/29)
- 후속 TASK-006.04: normalized TranscriptResult 처리/저장 pipeline
- Data contract: `docs/product/data-spec.md` Speaker/TranscriptSegment

## 변경 경계

- Backend domain/application normalizer: providerLabel → internal speakerId, segmentId 생성, 정렬
- Input validator: labels, times, text, duration 및 empty result validation
- Unit tests: provider-neutral fixtures와 overlap/boundary/no-speech cases
- 외부 provider SDK, HTTP/API DTO 및 Session persistence 변경 없음

이 adapter는 순수 deterministic 변환으로 구현한다. provider-specific identifier나 Participant 참조를 domain output에 노출하지 않아 provider 선택을 바꿔도 Review 데이터 계약이 유지된다.

## 구현 순서

1. TASK-006.02 typed response와 PRD data-spec을 대조한다. 결과: 입력/output type이 한 방향으로 연결된다.
2. label ordering, segment sorting, overlap, empty speaker/no-speech, invalid boundary fixture 테스트를 먼저 작성한다. 결과: identity/sort policy가 고정된다.
3. providerLabel reference/time/text validator를 구현한다. 결과: malformed output이 변환 전에 거절된다.
4. first utterance ordering으로 stable speaker ID를 만들고 unused speaker를 제외한다. 결과: 동일 input ID가 안정적이다.
5. 표준 segment ID를 만들고 stable chronological ordering 후 TranscriptResult를 반환한다. 결과: overlap과 원 시간값을 유지한 표준 model이 생성된다.
6. deterministic replay 및 로그 비노출 회귀 테스트, Backend test/build를 실행한다. 결과: adapter가 순수 변환 경계로 검증된다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. 변환 자체는 provider/audio network 없이 고정 object fixture로 검증한다.

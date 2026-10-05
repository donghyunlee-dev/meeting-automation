# Diarization 결과 표준화 Adapter

## 목표

TASK-006.02 `ProviderTranscriptionResponse`의 opaque provider label을 PRD 표준 `Speaker`/`TranscriptSegment`로 결정적으로 변환해 Review와 후속 pipeline이 Provider ID에 의존하지 않게 한다. PRD v1.7.0 (2026-10-05), `DEC-008`, `FR-008`, `EXT-001`, `TASK-006.03`을 구체화한다. Issue [#29](https://github.com/donghyunlee-dev/meeting-automation/issues/29).

## 범위

- Provider speaker label과 segment reference를 internal `speakerId`로 정규화
- Provider segment를 unique internal `segmentId`, 시간/화자/text가 있는 표준 segment로 변환
- timestamp 검증과 segment 시간순 안정 정렬
- 겹치는 발화 보존, segment가 없는 speaker 제거 및 no-speech empty 결과 처리
- deterministic output fixture 및 결과 검증

## 비범위

외부 provider 호출/DTO parsing(`TASK-006.02`), Audio 처리, 실명/voiceprint/Participant 자동 판별, Speaker-to-Participant UI/API mapping, Transcript 저장/Session 상태(`TASK-006.04`), Minutes 생성은 포함하지 않는다.

## 정규화 계약

입력은 TASK-006.02의 provider-neutral `{speakers:[{providerLabel}],segments:[{providerSegmentId?,providerLabel,startMs,endMs,text}]}`다. 출력은 PRD의 표준 형태다.

```text
Speaker = { speakerId, label }
TranscriptSegment = { segmentId, speakerId, startMs, endMs, text }
TranscriptResult = { speakers: Speaker[], segments: TranscriptSegment[] }
```

- Segment가 참조하는 providerLabel만 실제 speaker로 간주한다. 입력에 선언됐지만 segment가 없는 label은 결과에서 제외해 빈 화자 카드를 만들지 않는다.
- 모든 referenced providerLabel은 첫 발화의 시간(`startMs`, 동률이면 입력 순서) 기준으로 speaker 순서를 고정하고 `speaker_a`, `speaker_b`, …, `speaker_z`, `speaker_aa` 형식 internal ID를 부여한다. 같은 label 참조는 항상 동일 ID를 사용한다.
- 사람 이름이나 참석자 ID를 추론하지 않는다. 표준 Speaker에는 `participantId`를 추가하지 않으며 기존 데이터 모델상 선택 필드가 필요한 경우 미매핑 상태로 유지한다.
- 출력 segment는 unique `seg_001`, `seg_002`, … ID를 부여한다. provider ID 유무/중복에 관계없이 결과 순서 기반으로 생성해 provider 식별자를 표준 모델에 노출하지 않는다.
- 모든 segment는 `startMs >= 0`, `endMs >= startMs`, `endMs <= meetingDurationMs` 및 non-empty text를 만족해야 한다. provider label reference가 입력 speaker set에 없으면 결과 전체를 거절한다.
- Output segment는 `startMs`, `endMs`, input ordinal 순으로 stable sort한다. 겹치는 구간은 삭제·병합·시간 조정하지 않고 원래 시간값을 보존한다.
- 입력에 segment가 없고 speakers도 없으면 유효한 empty TranscriptResult다. referenced speaker 없는 non-empty segment 또는 잘못된 label/timestamp는 invalid result다.
- 같은 입력은 같은 ID/정렬/콘텐츠를 출력한다. original text는 수정 없이 전달하며 로그/오류에 포함하지 않는다.

## 수용 기준

- provider label은 first utterance order로 deterministic `speakerId`에 대응하고 모든 segment reference가 유효하다.
- 미사용 speaker 항목은 제외되고 no-speech input은 빈 결과로 반환된다.
- segment ID가 unique하고 결과가 시작/종료 시간 순으로 정렬된다.
- overlapping segment와 원래 timestamp/text는 보존된다.
- 잘못된 label reference, timestamp, empty text는 결과를 저장하지 않고 안전하게 실패한다.
- 입력 반복 결과가 deterministic하며 Provider label/model-specific field가 표준 API/data에 노출되지 않는다.

## 결정 및 전제

TASK-006.02는 provider label을 보존한 typed response까지만 제공한다. 이 Task가 PRD final TranscriptResult로 변환하는 유일한 표준화 경계이며 Participant 인식/매핑은 사용자가 Review에서 수행한다.

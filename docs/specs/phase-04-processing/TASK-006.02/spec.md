# TranscriptionProvider 계약과 STT Adapter

## 목표

후속 처리 pipeline이 assembled Audio를 provider adapter로 전송하고 provider-specific transcription/diarization 결과를 typed `ProviderTranscriptionResponse`로 변환한다. PRD v1.7.0 (2026-10-05), `FR-007`, `EXT-001`, `TASK-006.02`를 구체화한다. Issue [#28](https://github.com/donghyunlee-dev/meeting-automation/issues/28).

## 범위

- `TranscriptionCommand` 입력: 보호된 assembled Audio 참조/stream, MIME, optional `languageHint`, `meetingDurationMs`
- `TranscriptionProvider.transcribeWithDiarization(command) -> ProviderTranscriptionResponse` port 및 설정 기반 provider adapter
- provider의 model-specific speaker/segment 응답을 provider-neutral label-preserving response로 변환
- raw speaker label reference 및 기본 시간/text validation
- provider timeout, 제한 오류, 인증/config 오류 및 malformed response의 안전한 분류
- 요청/응답/로그에서 Audio/Transcript/Secret 비노출

## 비범위

Chunk 조립 및 terminal audio cleanup(`TASK-006.01/017.02`), API-009/job lifecycle, 전체 Processing pipeline과 retry state (`TASK-006.04`), internal Speaker/TranscriptSegment 표준화(`TASK-006.03`), Minutes 생성, 특정 모델 버전 영구 고정, provider 선택 UI는 포함하지 않는다.

## Port와 표준 모델

```text
TranscriptionCommand = {
  audio: protected assembled file reference or streaming bytes,
  mimeType: string,
  languageHint?: string,
  meetingDurationMs: integer
}

ProviderTranscriptionResponse = {
  speakers: [{providerLabel}],
  segments: [{providerSegmentId?, providerLabel, startMs, endMs, text}]
}
```

provider/model은 `TRANSCRIPTION_MODEL` 및 Backend 설정으로 정한다. 특정 OpenAI model ID를 Domain/API 계약에 고정하지 않는다. adapter는 provider-specific DTO를 provider-neutral typed output으로 변환하고 Provider Secret은 Backend secret configuration에서만 읽는다.

- provider speaker label은 응답 내에서 opaque label로 보존하고 segment label 참조가 speaker 목록에 없으면 malformed response로 실패한다. internal `speakerId` 또는 Participant를 이 단계에서 만들지 않는다.
- provider segment ID는 선택 metadata로 보존한다. 표준 `segmentId` 생성/중복 해결, deterministic internal speaker ID 및 시간순 표준 정렬은 TASK-006.03에서 수행한다.
- 기본 timestamp 조건은 `startMs >= 0`, `endMs >= startMs`, `endMs <= meetingDurationMs`다. segment text는 trim 후 비어 있지 않아야 하며 원문을 exception/log에 복사하지 않는다. 실제 음성이 없는 응답은 empty speakers/segments로 표현할 수 있다.
- 외부 요청은 보호된 file stream으로 전송하고 전체 Audio를 메모리에 추가 복사하지 않는다. 호출 timeout은 Backend HTTP client의 기존 제한 설정을 사용하며 요청별 무한 대기는 금지한다.
- Provider timeout/일시 네트워크/429/5xx는 안전한 retryable `PROCESSING_FAILED`로 매핑한다. 지원 MIME/duration/file size 거절은 retryable 아님, 인증/config 문제는 운영 설정 오류로 분류하며 외부 원문을 노출하지 않는다. 실제 재시도 횟수와 pipeline 상태 변경은 TASK-006.04가 결정한다.
- malformed/불완전 standardizable response는 `PROCESSING_FAILED`; provider 응답 원문, Audio 및 Secret은 API/error/log에 남기지 않는다.

## 수용 기준

- mock/fixed Audio fixture가 정해진 command로 provider adapter를 호출하며 label 보존 typed response로 변환된다.
- provider speaker/segment raw label 참조와 기본 시간/text 규칙이 검증된다.
- canonical speaker ID, 표준 segment ID와 결과 정렬은 TASK-006.03 책임으로 남는다.
- no-speech 결과는 빈 provider-neutral response로 보존된다.
- timeout/429/5xx는 retryable, invalid audio/config/auth/malformed response는 적절한 안전 실패로 분류된다.
- provider 원문/Audio/Transcript/Secret이 일반 로그, 오류 response 및 trace에 포함되지 않는다.
- model selection은 설정을 통해 교체할 수 있고 Domain/API 표준 계약은 바뀌지 않는다.

## 결정 및 전제

외부 모델은 배포 시점의 공식 diarization 지원 설정으로 선택하며 본 Task는 model ID를 정하지 않는다. Provider label의 internal `speakerId` 변환과 표준 Transcript segment 구성은 TASK-006.03이 담당한다. Provider 자동 retry와 pipeline 재실행은 TASK-006.04가 소유한다.

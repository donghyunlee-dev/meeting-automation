> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# TranscriptionProvider 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| STT-01 | 설정된 model과 고정 Audio fixture, diarization response fixture | typed command 호출 및 표준 speaker/segments 결과 | adapter mapping test |
| STT-02 | provider speaker label과 segment 참조 | raw label을 보존한 typed response | adapter mapping test |
| STT-03 | segment가 provider speaker 목록에 없는 label 참조 | 결과 reject, safe `PROCESSING_FAILED` | provider reference validation test |
| STT-04 | 음수/역전/meeting duration 초과 timestamp | 결과 reject, downstream 미호출 | basic time range test |
| STT-05 | empty text 또는 provider 응답에서 required label 누락 | invalid provider response failure | response validation test |
| STT-06 | no-speech fixture의 빈 speakers/segments | 유효한 empty provider-neutral response | mapping test |
| STT-07 | Provider timeout/network/429/5xx | 일반 원문 미포함 retryable failure classification | timeout/error mapper test |
| STT-08 | provider invalid MIME/file size/duration/auth rejection | 재시도 불가 안전 분류 및 외부 response body 미노출 | permanent error test |
| STT-09 | malformed provider payload | invalid response failure, transcript 저장 없음 | adapter contract test |
| STT-10 | sentinel Audio, transcript, Secret로 로그 capture | 모든 sentinel이 log/error response에 없음 | security/log assertion |
| STT-11 | 서로 다른 `TRANSCRIPTION_MODEL` 설정 | 같은 provider-neutral port 결과 타입 사용 | configuration wiring test |
| STT-12 | Provider HTTP 400 with `error.param=response_format` vs `file` | Safe `CONFIGURATION_ERROR` vs `INVALID_AUDIO`; no provider details in error | Adapter tests |
| STT-13 | Override `TRANSCRIPTION_CONNECT_TIMEOUT_SECONDS` | The configured connect timeout is bound and used; read timeout stays separate | Adapter configuration binding test |
| STT-14 | Caller-declared duration is at or below 30 seconds | `chunking_strategy=auto` is still sent for every diarized request | Adapter test |

Backend root에서 다음 focused tests를 실행한다. 무관한 Backend 테스트를 포함한 전체 suite, clean build는 실행하지 않는다.

backend/에서 실행:
`& .\gradlew.bat test --tests com.meetingautomation.transcription.TranscriptionCommandTests --tests com.meetingautomation.transcription.ProviderTranscriptionResponseTests --tests com.meetingautomation.transcription.openai.OpenAiTranscriptionAdapterTests`

별도 컴파일 확인이 필요하면 `compileJava` 또는 `compileTestJava`를 사용한다.

## 통합/수동 QA

- 개발 환경 provider 설정으로 고정 test audio를 전달하고 표준 speaker/segment 구조를 확인한다.
- provider timeout/error를 주입해 UI/API 노출용 safe failure와 retryable 분류를 확인한다.
- 일반 로그, trace 및 예외에 audio/transcript/secret/provider raw body가 없는지 검색한다.
- model 설정 변경 시 Domain/API 응답 DTO 수정 없이 adapter configuration만 교체되는지 확인한다.

The response-format error test inspects only the safe `error.param` field; it does not expose or retain the provider message or body.

OpenAI's [Create transcription API reference](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create) lists `chunking_strategy` as optional overall, required for diarized inputs over 30 seconds, and includes an `auto` example whose output duration is 27.4 seconds. Always sending it avoids relying on caller-reported duration.

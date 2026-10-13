# TranscriptionProvider 작업 항목

1. EXT-001, TranscriptSegment, assembled Audio output, 설정과 HTTP client 기반을 확인한다. 완료 증거: provider-independent port input이 정합하다.
2. command/result/failure type 및 config boundary를 정의한다. 완료 증거: Domain은 외부 SDK/DTO를 참조하지 않는다.
3. 고정 Audio fixture, provider response fixture와 timeout/malformed/error mapping 테스트를 먼저 작성해 실패를 확인한다.
4. 설정된 model/API adapter request, protected stream, timeout과 Secret injection을 구현한다. 완료 증거: model ID를 바꿔도 표준 port는 유지된다.
5. provider-specific speaker/segment를 label 보존 `ProviderTranscriptionResponse`로 변환한다. 완료 증거: 기본 label/time/text validation이 통과하고 internal speakerId 변환은 다음 TASK 경계에 남는다.
6. retryable/permanent failure mapping과 민감 데이터 로그 redaction을 구현한다. 완료 증거: 외부 원문/오디오/secret은 응답·로그에서 보이지 않는다.
7. empty no-speech result, 정상 fixture 및 malformed fixture를 검증한다. 완료 증거: downstream pipeline에 표준 DTO만 전달된다.
8. `backend/`에서 `& .\gradlew.bat test --tests com.meetingautomation.transcription.TranscriptionCommandTests --tests com.meetingautomation.transcription.ProviderTranscriptionResponseTests --tests com.meetingautomation.transcription.openai.OpenAiTranscriptionAdapterTests`를 실행한다. 전체 Backend suite 또는 clean build는 실행하지 않는다. 완료 증거: 선택된 테스트의 결과와 adapter contract를 기록한다.

9. Distinguish a provider `response_format` rejection from an invalid audio `file` response, bind a configurable connect timeout, and verify both through the focused adapter test.

10. Send `chunking_strategy=auto` on every diarized request because assembled audio length is not verified against the caller-reported duration.

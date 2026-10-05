# TranscriptionProvider 작업 항목

1. EXT-001, TranscriptSegment, assembled Audio output, 설정과 HTTP client 기반을 확인한다. 완료 증거: provider-independent port input이 정합하다.
2. command/result/failure type 및 config boundary를 정의한다. 완료 증거: Domain은 외부 SDK/DTO를 참조하지 않는다.
3. 고정 Audio fixture, provider response fixture와 timeout/malformed/error mapping 테스트를 먼저 작성해 실패를 확인한다.
4. 설정된 model/API adapter request, protected stream, timeout과 Secret injection을 구현한다. 완료 증거: model ID를 바꿔도 표준 port는 유지된다.
5. provider speaker/segment를 deterministic IDs와 TranscriptResult로 변환한다. 완료 증거: speaker reference/time/order/unique ID validation이 통과한다.
6. retryable/permanent failure mapping과 민감 데이터 로그 redaction을 구현한다. 완료 증거: 외부 원문/오디오/secret은 응답·로그에서 보이지 않는다.
7. empty no-speech result, 정상 fixture 및 malformed fixture를 검증한다. 완료 증거: downstream pipeline에 표준 DTO만 전달된다.
8. `./gradlew test`, `./gradlew clean build`를 실행한다. 완료 증거: test/build와 adapter contract 결과를 기록한다.

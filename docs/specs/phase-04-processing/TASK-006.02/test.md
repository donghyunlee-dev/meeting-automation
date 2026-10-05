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

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 통합/수동 QA

- 개발 환경 provider 설정으로 고정 test audio를 전달하고 표준 speaker/segment 구조를 확인한다.
- provider timeout/error를 주입해 UI/API 노출용 safe failure와 retryable 분류를 확인한다.
- 일반 로그, trace 및 예외에 audio/transcript/secret/provider raw body가 없는지 검색한다.
- model 설정 변경 시 Domain/API 응답 DTO 수정 없이 adapter configuration만 교체되는지 확인한다.

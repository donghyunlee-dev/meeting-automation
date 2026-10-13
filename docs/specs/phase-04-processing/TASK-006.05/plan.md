# Transcript 기반 Minutes 생성 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-009`, `DEC-012`, `EXT-002`, `TASK-006.05`
- 선행 TASK-006.04 Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30): normalized Transcript/Session processing context
- 선행 TASK-004.02 Issue [#15](https://github.com/donghyunlee-dev/meeting-automation/issues/15): Template catalog/static resource
- 본 TASK Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31)
- 후속 TASK-006.06: API-010 Minutes/Transcript/Speaker data GET; TASK-006.07: Processing UI
- 후속 Minutes render/update: 해당 UI/API task가 structured model 사용

## 변경 경계

- Domain/application `MinutesGenerationProvider` port, claim별 segment citation result 및 filtering/command validation
- Backend static Template repository에서 id/version/content 조회
- EXT-002 adapter가 `MINUTES_MODEL`을 읽어 schema-constrained output 요청
- Session application service가 Transcript/Minutes와 `REVIEW` transition 원자 반영
- Audio/STT/diarization 또는 Provider 문서 저장은 변경하지 않는다.

## 구현 순서

1. Template API/catalog version, Task-006.04 Session Transcript/Speaker mapping, StructuredMinutes schema를 확인한다. 결과: 생성 command input이 정합하다.
2. fixture-driven valid/empty/malformed/participant/date/provider failure 및 미지원 claim filtering tests를 먼저 작성한다. 결과: filtering과 구조 오류의 경계가 저장 전에 고정된다.
3. Template resource/prompt loader와 최소-input command mapper를 구현한다. 결과: Secret/email/Audio가 provider command에 포함되지 않는다.
4. empty Transcript fast path로 empty draft를 만든다. 결과: LLM 호출과 추론 없는 reviewable output이 보장된다.
5. EXT-002 provider port/adapter와 schema-constrained output parse를 구현한다. 결과: `MINUTES_MODEL` 교체가 Domain contract를 바꾸지 않는다.
6. citation reference를 검증해 근거가 없는 claim만 제거하고, 남은 StructuredMinutes field/roster/date/template validation 및 atomic Session save/REVIEW transition을 구현한다. 결과: 미지원 claim은 빠지고 구조 오류 시 부분 output과 완료 state가 노출되지 않는다.
7. redaction 및 Backend test/build를 실행한다. 결과: template/transcript/privacy와 성공 transition 증거가 남는다.

## 검증 명령

Windows PowerShell, `backend/` 폴더에서 아래 변경 관련 selector만 실행한다. 전체 Backend suite나 `clean build`는 실행하지 않는다. Provider API는 mock transport와 고정 Transcript fixtures를 사용해 schema/error 검증을 분리한다.

```powershell
.\gradlew.bat test --tests com.meetingautomation.api.processing.ProcessingPipelineTests --tests com.meetingautomation.minutes.GroundedMinutesResultNormalizerTests --tests com.meetingautomation.minutes.openai.OpenAiMinutesGenerationAdapterTests --tests com.meetingautomation.MeetingSessionCreationApiTests --tests com.meetingautomation.api.template.TemplateControllerTests
```

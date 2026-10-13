# Minutes 생성 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| MINUTES-01 | non-empty Transcript, default Template, Participant/mapping fixture | EXT-002 command와 유효 StructuredMinutes 저장 | provider/application test |
| MINUTES-02 | project Template 선택 | matching id/version/content 사용 | template mapping test |
| MINUTES-02A | session 생성 뒤 catalog Template version이 바뀜 | 저장된 version 불일치로 Provider 호출 없이 safe failure, REVIEW 아님 | pinned template version test |
| MINUTES-03 | empty Transcript | provider 호출 0회, template ID/version 및 빈 summary/arrays 초안 | empty transcript test |
| MINUTES-04 | 누락 summary/section/action field 또는 wrong type | `PROCESSING_FAILED`, minutes 미저장 | schema validation test |
| MINUTES-05 | action owner ID가 roster 밖 | schema/reference 오류, 결과 partial save 없음 | Participant reference validation |
| MINUTES-06 | invalid date, 날짜 형식 위반, 없는 dueDate/null | invalid non-null date reject; nullable owner/date 허용 | date validation test |
| MINUTES-07 | Speaker mapping 없음, provider가 실명/담당자를 제안 | 근거/roster 불충분 owner null, 추론된 identity 저장 없음 | no-invention test |
| MINUTES-08 | 전사에 없는 segment ID 또는 근거가 빈 summary/discussion/decision/follow-up claim | 해당 claim만 제거하고 지원되는 다른 claim은 보존 | grounded output fixture test |
| MINUTES-08A | 근거가 빈 Action task claim | 해당 Action item만 제거; 나머지 Action item은 보존 | grounded action fixture test |
| MINUTES-08B | task는 지원되나 due date 근거 없음 | Action item과 task는 저장하고 dueDate만 null | grounded date fixture test |
| MINUTES-08B1 | date는 있으나 citation segment에 canonical ISO 값이 없음 | Action item과 task는 저장하고 dueDate만 null | grounded date fixture test |
| MINUTES-08C | task는 지원되나 owner 근거 또는 speaker mapping 없음 | Action item과 task는 저장하고 ownerParticipantId는 null | grounded owner fixture test |
| MINUTES-09 | Provider timeout/429/5xx | typed retryable `PROCESSING_FAILED`, Session not REVIEW | failure mapping test |
| MINUTES-10 | Provider 401/invalid config/malformed JSON | safe permanent failure, raw body 미노출 | error/security test |
| MINUTES-11 | Minutes persist transaction failure | Session REVIEW transition 없음, partial Minutes 없음 | atomic update test |
| MINUTES-12 | 성공 완료 | Session `REVIEW`, `DRAFT_READY`, progress 100 | Session state integration test |
| MINUTES-13 | provider/log capture sentinel Transcript/Template/Secret | sentinel values가 일반 로그/error response에 없음 | log redaction assertion |

Windows PowerShell, `backend/` 폴더에서 다음 직접 영향받는 테스트만 실행한다. 전체 Backend suite와 `clean build`는 Developer/QA task-local 검증에서 실행하지 않으며 GitHub Actions 설정은 별도 변경 없이 유지한다.

```powershell
.\gradlew.bat test --tests com.meetingautomation.api.processing.ProcessingPipelineTests --tests com.meetingautomation.minutes.GroundedMinutesResultNormalizerTests --tests com.meetingautomation.minutes.openai.OpenAiMinutesGenerationAdapterTests --tests com.meetingautomation.MeetingSessionCreationApiTests --tests com.meetingautomation.api.template.TemplateControllerTests
```

## 통합/수동 QA

- API-002 Template catalog에서 제공하는 각 template id/version으로 구조화 초안을 만든다.
- 고정 Transcript fixture에 명시적인 speaker mapping과 미매핑 speaker를 섞어 output 근거/owner null 처리를 확인한다.
- empty Transcript 입력에서 Review route가 무근거 summary 없이 빈 초안을 보여주는지 후속 API-010 fixture로 확인한다.
- provider timeout 및 schema-invalid output이 API-010에서 안전한 실패로 보이고 REVIEW 완료로 표시되지 않는지 확인한다.

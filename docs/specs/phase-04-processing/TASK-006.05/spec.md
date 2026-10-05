# Transcript 기반 Minutes 생성

## 목표

처리 pipeline에서 표준 Transcript와 선택한 Template을 입력으로 structured Minutes 초안을 생성·검증해 Session에 저장하고 Review 단계로 넘긴다. PRD v1.7.0 (2026-10-05), `FR-009`, `DEC-012`, `EXT-002`, `TASK-006.05`를 구체화한다. Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31).

## 범위

- Session Template ID/version과 Backend static template content 조회
- 최소 Participant `{id,name}` 참조, 사용자 Speaker mapping 및 normalized Transcript 구성
- `MinutesGenerationProvider.generateMinutes(MinutesCommand)` port 호출
- Provider output을 StructuredMinutes schema로 검증 및 Session에 원자 저장
- no-speech Transcript의 빈 초안 생성
- 정상 생성 시 `DRAFT_READY`/100% 및 Session `REVIEW` transition
- provider timeout/schema/secret/content 오류 안전 매핑

## 비범위

Audio/STT/diarization 재호출, Template 편집/선택 UI, 문서 Provider 저장, Markdown rendering(`TASK-007.*`), Minutes 수정/재생성 API(`TASK-009.*`), delivery는 포함하지 않는다.

## 생성 계약

입력은 `templateId`, `templateVersion`, Backend static Template prompt/content, 필요한 최소 `{participantId,name}` roster references, current `speakerId→participantId` mappings, normalized Transcript다. Email/Secret, Audio, provider-specific speaker label은 입력하지 않는다. `MINUTES_MODEL` 설정으로 EXT-002 구현을 선택한다.

Output은 아래 `StructuredMinutes`이며, 모든 필드를 포함한다.

```json
{
  "templateId":"default.md",
  "templateVersion":"1.0.0",
  "summary":"string",
  "discussionPoints":["string"],
  "decisions":["string"],
  "actionItems":[{"ownerParticipantId":"pt_001","task":"API 검토","dueDate":"2026-10-10"}],
  "followUps":["string"]
}
```

- `templateId`는 Session에 저장된 선택값과 같아야 한다. `templateVersion`은 해당 id에 Backend가 제공한 버전과 같아야 하며 provider가 임의 변경할 수 없다.
- summary는 문자열, 나머지 section은 배열이다. section item의 빈/공백 text, null/예상 밖 타입을 허용하지 않는다. `actionItems`의 `task`는 필수 non-empty string, `ownerParticipantId`는 Session roster ID 중 하나 또는 null, `dueDate`는 유효 `YYYY-MM-DD` 또는 null이어야 한다.
- provider output이 Participant id를 잘못 참조하거나 transcript에 근거 없는 결정/담당자/날짜를 생성하지 않도록 prompt와 schema validator를 함께 사용한다. 참조 오류/잘못된 날짜/schema parsing 실패는 결과 저장 없이 `PROCESSING_FAILED`로 처리한다.
- Speaker mapping이 없는 화자는 이름/Participant를 추론하지 않는다. Action item owner는 근거와 현재 mapping이 명확하지 않으면 null이다.
- Transcript가 빈 경우 외부 LLM을 호출하지 않는다. 선택 Template의 id/version과 `summary:""`, 빈 section arrays를 담은 결정적 empty draft를 저장한다. 근거 없는 결론/담당자/날짜를 만들지 않는다.
- provider timeout/429/5xx는 typed retryable processing failure로, invalid config/auth/malformed structured output은 safe non-retryable failure로 전달한다. 자동 재시도 횟수는 추가하지 않으며 TASK-006.04의 Processing failure contract를 따른다.
- 성공 Minutes와 Session status/stage/progress는 한 Session mutation 경계에서 저장한다. output persist가 실패하면 Session은 `REVIEW`가 아니며 success response를 보이지 않는다. 성공 시 `status=REVIEW`, processing `{stage:"DRAFT_READY",progressPercent:100}`이다.
- Template prompt/content, Transcript/Minutes, roster email, provider raw output 및 Secret을 로그/오류에 남기지 않는다. 운영 로그에는 `sessionId`, `traceId`, stage, outcome, safe error code만 남긴다.

## 수용 기준

- non-empty Transcript는 선택 Template version 및 최소 Participant/Speaker mapping input으로 EXT-002를 호출한다.
- 유효 structured result가 schema/roster/template 참조 검증 후 atomic 저장된다.
- empty Transcript는 외부 호출 없이 template-aligned empty draft를 저장하고 Review 상태를 완료한다.
- 잘못된 필드, Participant 참조, 날짜, parsing/provider error에서 부분 Minutes를 저장하지 않는다.
- 근거 없는 결정/담당자/날짜를 만들지 않고 owner 불명은 null로 둔다.
- 성공 후에만 `REVIEW`/`DRAFT_READY`/100%가 보이며 output persist 실패는 실패로 보존된다.
- Transcript/Minutes/Template/Secret/provider body가 일반 로그 및 오류 response에 없다.

## 결정 및 전제

빈 Transcript는 provider 호출/비용 없이 비어 있는 구조화 초안으로 보존한다. user가 빈 Transcript를 열어도 false summary/action을 보지 않는다. `REVIEW` transition은 Transcript와 Minutes가 같은 Session에 저장된 뒤 한 번 수행한다.

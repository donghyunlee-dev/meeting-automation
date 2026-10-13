# Transcript 기반 Minutes 생성

## 목표

처리 pipeline에서 표준 Transcript와 선택한 Template을 입력으로 structured Minutes 초안을 생성·검증해 Session에 저장하고 Review 단계로 넘긴다. PRD v1.7.0 (2026-10-05), `FR-009`, `DEC-012`, `EXT-002`, `TASK-006.05`를 구체화한다. Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31).

## 범위

- Session Template ID/version과 Backend static template content 조회
- 최소 Participant `{id,name}` 참조, 사용자 Speaker mapping 및 normalized Transcript 구성
- `MinutesGenerationProvider.generateMinutes(MinutesCommand)` port 호출
- Provider의 claim별 transcript 근거를 검증하고 지원되지 않는 claim만 제거한 뒤 StructuredMinutes를 Session에 원자 저장
- no-speech Transcript의 빈 초안 생성
- 정상 생성 시 `DRAFT_READY`/100% 및 Session `REVIEW` transition
- provider timeout/schema/secret/content 오류 안전 매핑

## 비범위

Audio/STT/diarization 재호출, Template 편집/선택 UI, 문서 Provider 저장, Markdown rendering(`TASK-007.*`), Minutes 수정/재생성 API(`TASK-009.*`), delivery는 포함하지 않는다.

## 생성 계약

입력은 Session 생성 시 고정한 `templateId`/`templateVersion`, 그 동일 버전의 Backend static Template content, 필요한 최소 `{participantId,name}` roster references, current `speakerId→participantId` mappings, normalized Transcript다. 선택 Template version은 Session 생성 시점에 고정하며 processing 시 catalog가 해당 id/version을 더 제공하지 않으면 Provider 호출 없이 safe failure한다. API는 계속 template ID만 받으며 version/name은 응답에 노출하지 않는다. Email/Secret, Audio, provider-specific speaker label은 provider command에 넣지 않는다. `MINUTES_MODEL` 설정으로 EXT-002 구현을 선택한다.

Provider adapter는 claim별 Transcript segment 근거를 붙인 내부 생성 결과를 반환한다. 내부 JSON은 다음 shape를 사용하며 모든 key와 배열을 포함한다. `summaryClaims`/`discussionPointClaims`/`decisionClaims`/`followUpClaims`의 각 원소는 `{text,evidenceSegmentIds}`이고, 각 Action claim은 task/owner/due date 근거를 분리한다.

```json
{
  "templateId":"default.md",
  "templateVersion":"1.0.0",
  "summaryClaims":[{"text":"API 경계를 합의했다.","evidenceSegmentIds":["seg_001"]}],
  "discussionPointClaims":[],
  "decisionClaims":[],
  "actionItemClaims":[{
    "task":"API 검토",
    "taskEvidenceSegmentIds":["seg_002"],
    "ownerParticipantId":null,
    "ownerEvidenceSegmentIds":[],
    "dueDate":null,
    "dueDateEvidenceSegmentIds":[]
  }],
  "followUpClaims":[]
}
```

각 evidence ID 배열은 비어 있을 수 있다. ID가 비었거나 하나라도 입력 Transcript에 없으면 그 근거를 사용한 텍스트 claim은 지원되지 않은 것으로 간주한다. 구현은 참조 segment가 존재하는지 확인하지만 claim과 인용문 사이의 의미적 함의까지 독립 검증하지는 않는다. summary claims는 원래 순서대로 줄바꿈 결합한다. 날짜는 유효한 ISO 값뿐 아니라 그 ISO 문자열이 인용된 Transcript segment 본문에 그대로 있어야 유지한다. 근거가 비었거나 segment에는 날짜가 없으면 date만 null이다. 근거 ID는 검증·필터링 후 저장되는 `StructuredMinutes`에 포함하지 않는다. 저장 결과는 아래 canonical `StructuredMinutes`이며 모든 필드를 포함한다.

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
- 내부 생성 결과의 각 claim은 자기 근거 segment ID 배열을 가진다. 배열이 비었거나 하나라도 현재 Transcript에 없는 ID를 참조하면 해당 텍스트 claim만 제거한다. 따라서 미지원 summary claim은 summary에서 빠지고, 미지원 discussion/decision/follow-up claim은 해당 목록에서 빠진다. task claim이 미지원인 Action item은 제거한다. 유효한 task에 붙은 due date는 유효한 ISO date와 일치하는 literal이 인용된 Transcript segment에 있을 때만 유지하고, 그렇지 않으면 날짜만 null로 만든다. owner 근거 또는 현재 speaker-to-participant mapping이 없으면 owner만 null로 만든다. Owner 근거 segment는 mapping된 화자가 해당 Participant를 말한 segment여야 한다.
- claim/evidence 구조 자체의 잘못된 타입·필드 누락, StructuredMinutes 필수 필드/타입 오류, roster 밖 owner ID, 잘못된 calendar date 및 JSON parsing 실패는 filtering 대상이 아니다. 결과를 저장하지 않고 `PROCESSING_FAILED`로 처리한다.
- Speaker mapping은 후속 TASK-007.01에서 추가된다. 현재 `Speaker`는 participant mapping을 담지 않으므로 이 task에서는 provider가 특정 Participant를 owner로 출력해도 저장 owner는 null이다. 이후 mapping이 제공되면 Transcript segment의 speaker mapping과 일치할 때만 owner를 유지한다. 화자 이름이나 Participant를 새로 추론하지 않는다.
- Transcript가 빈 경우 외부 LLM을 호출하지 않는다. 선택 Template의 id/version과 `summary:""`, 빈 section arrays를 담은 결정적 empty draft를 저장한다. 근거 없는 결론/담당자/날짜를 만들지 않는다.
- provider timeout/429/5xx는 typed retryable processing failure로, invalid config/auth/malformed structured output은 safe non-retryable failure로 전달한다. 자동 재시도 횟수는 추가하지 않으며 TASK-006.04의 Processing failure contract를 따른다.
- Minutes 생성 진입 시 `processing.stage=MINUTES_GENERATION`을 기록한다. 이 stage의 failure/retry snapshot 계약은 후속 TASK-017.02 (#61)에서 확장되며, 기존 Transcript와 speaker mapping을 재사용해 Minutes 생성만 다시 수행한다.
- 성공 Minutes와 Session status/stage/progress는 한 Session mutation 경계에서 저장한다. output persist가 실패하면 Session은 `REVIEW`가 아니며 success response를 보이지 않는다. 성공 시 `status=REVIEW`, processing `{stage:"DRAFT_READY",progressPercent:100}`이다.
- Template prompt/content, Transcript/Minutes, roster email, provider raw output 및 Secret을 로그/오류에 남기지 않는다. 운영 로그에는 `sessionId`, `traceId`, stage, outcome, safe error code만 남긴다.

## 수용 기준

- non-empty Transcript는 선택 Template version 및 최소 Participant/Speaker mapping input으로 EXT-002를 호출한다.
- 유효 structured result가 schema/roster/template 참조 검증을 통과하고, 근거가 확인되지 않는 텍스트 claim만 필터링된 뒤 atomic 저장된다.
- empty Transcript는 외부 호출 없이 template-aligned empty draft를 저장하고 Review 상태를 완료한다.
- 구조 오류, roster 밖 owner, 잘못된 날짜, parsing/provider error에서는 부분 Minutes를 저장하지 않는다. 이는 근거가 없는 claim을 제거하는 규칙과 별개다.
- Transcript segment를 참조하지 않는 텍스트/결정/action task는 각각 제거한다. 근거 없는 owner와 due date만 null 처리하며, owner 불명은 null이다.
- 성공 후에만 `REVIEW`/`DRAFT_READY`/100%가 보이며 output persist 실패는 실패로 보존된다.
- Transcript/Minutes/Template/Secret/provider body가 일반 로그 및 오류 response에 없다.

## 결정 및 전제

빈 Transcript는 provider 호출/비용 없이 비어 있는 구조화 초안으로 보존한다. user가 빈 Transcript를 열어도 false summary/action을 보지 않는다. `REVIEW` transition은 Transcript와 Minutes가 같은 Session에 저장된 뒤 한 번 수행한다.

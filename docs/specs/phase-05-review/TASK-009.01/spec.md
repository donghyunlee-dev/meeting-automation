# Minutes 재생성 API

## 목표

`POST /api/v1/meeting-sessions/{sessionId}/minutes/regenerate`가 기존 Transcript와 Speaker mapping을 사용해 사용자가 선택한 Template으로 Minutes만 다시 생성한다. 재생성 실패 시 재생성 직전 저장된 Minutes와 Template provenance를 원자적으로 복구하고 Session을 `REVIEW`로 돌린다. PRD v1.7.0 (2026-10-05), `DEC-011`, `DEC-012`, `FR-013`, `FR-014`, `API-010`, `API-013`, `TASK-009.01`을 구체화한다. Issue [#40](https://github.com/donghyunlee-dev/meeting-automation/issues/40).

## 범위

- `REVIEW` Session 및 `REGENERATE_MINUTES` action, `If-Match`, `Idempotency-Key`, Template을 검증하는 API-013
- 재생성 입력 snapshot으로 Transcript, 현재 Speaker mapping, 선택된 Participant roster의 최소 참조, Template id/version/content를 전달
- 기존 Audio/STT/Diarization 없이 `MinutesGenerationProvider`만 호출
- `StructuredMinutes` 검증과 새 Minutes 저장의 원자적 완료
- 이전 Minutes 전체 snapshot의 실패 복구와 `REVIEW` 복귀
- API-010 공통 `{data}` envelope, Session version, task-specific `lastOperation` 결과 노출
- 중복 요청, 동시 변경, 없는 Session, 유효하지 않은 입력 및 안전한 실패 처리

## 비범위

Template 선택/확인 UI와 polling UX (`TASK-009.02`), STT/Diarization 회귀 검증 작업 (`TASK-009.03`), Template 편집, API-012 수동 Minutes 수정, 새로운 실패 재시도 횟수/자동 재시도 정책, Session 영속화, Participant 추가/활성/접속 상태 처리는 포함하지 않는다.

## 사용자 결정

사용자 선택 **A**: 재생성 실패 시 기존 Minutes를 원자 복구하고 Session을 `REVIEW`로 되돌린다. API-010은 복구된 Review snapshot을 반환한다. Frontend는 요청했던 Template이 적용되지 않았다는 안전한 실패 결과를 표시한다. 이 재생성 전용 rollback은 일반 처리 실패의 `PROCESSING_FAILED`와 구별한다. Provider 예외 원문은 응답/로그에 포함하지 않는다.

## API-013 계약

```http
POST /api/v1/meeting-sessions/{sessionId}/minutes/regenerate
Idempotency-Key: <opaque-key>
If-Match: "<current-version>"
Content-Type: application/json

{"templateId":"project.md"}
```

- 요청은 현재 `status=REVIEW`이고 서버가 계산한 `allowedActions`에 `REGENERATE_MINUTES`가 있을 때만 허용한다. API-010 action은 UI 안내이며 서버 검증을 대체하지 않는다.
- `templateId`는 현재 Template catalog에 존재하는 선택 가능 ID여야 한다. Backend가 Template content/version을 조회하며 클라이언트는 prompt나 version을 제출하지 않는다.
- Session에 현재 저장된 Minutes 전체(Template id/version 포함), Transcript, Speaker mapping을 같은 version snapshot에서 캡처한다. Session roster에서 필요한 `{participantId,name}` 참조만 생성 입력에 포함한다.
- 명시적 확인 후 호출한 재생성은 저장된 현재 Minutes 전체를 교체한다. 클라이언트의 미저장 draft는 TASK-009.02가 API-012로 먼저 저장하거나 사용자에게 폐기 확인을 받아야 한다.
- 수락은 HTTP 202와 PRD의 `{data:{sessionId,status:"PROCESSING",stage:"DRAFT_REGENERATION",templateId}}`를 반환한다. 재생성 중 API-010은 기존 API-010 처리 중 계약대로 partial Review data를 숨긴다.
- 성공은 검증된 새 StructuredMinutes와 Template provenance를 한 Session mutation으로 저장하고 `status=REVIEW`, `processing={stage:"DRAFT_READY",progressPercent:100}`으로 전이한다. Version은 수락 및 최종 mutation 경계에서 단조 증가한다.
- 같은 Idempotency-Key와 정규화 요청은 최초 수락 결과를 재사용한다. 같은 키로 session/template/If-Match가 다른 요청은 `409 IDEMPOTENCY_KEY_CONFLICT`다. 키 누락 또는 형식 오류는 `400 VALIDATION_FAILED`다.
- `If-Match` 누락/형식 오류는 `400 VALIDATION_FAILED`, version 불일치는 `412 SESSION_VERSION_CONFLICT`, 없는 Session은 `404 SESSION_NOT_FOUND`다. Session이 `REVIEW`가 아니거나 action이 허용되지 않으면 `409 SESSION_STATE_CONFLICT`다. 잘못되거나 없는 Template은 `400 VALIDATION_FAILED`다. 어느 거절에서도 Session/Minutes를 변경하지 않는다.

## 처리와 원자성

1. 요청 검증과 idempotency 예약을 수행한다. 같은 idempotent replay는 provider를 다시 호출하지 않는다.
2. 현재 version을 비교하고 Review snapshot을 읽어 immutable regeneration command를 만든다. Audio bytes/path와 Audio adapter 참조는 command에 포함하지 않는다.
3. 수락 mutation에서 상태를 `PROCESSING`, stage를 `DRAFT_REGENERATION`으로 설정하고 복구용 기존 Minutes와 Template provenance 및 operation 식별자를 Session 내부에 보존한다. Transcript와 Speaker mapping은 변경하지 않는다.
4. provider 결과를 TASK-006.05의 StructuredMinutes 규칙으로 검증한다. `templateId`/`templateVersion`, 필수 field/type, 공백 item, roster owner 참조 및 `dueDate`를 검사한다. Transcript 근거가 부족한 담당자는 null이어야 한다. 빈 Transcript는 provider를 호출하지 않고 선택 Template에 맞춘 빈 draft를 결정적으로 생성한다.
5. 성공 시 작업 식별자와 현재 version이 여전히 일치하는지 검사한 뒤 새 Minutes를 저장하고 Review로 전이한다. 부분 Minutes는 공개하지 않는다.
6. Provider timeout/429/5xx, auth/config 오류, schema 오류 또는 저장 오류 등 재생성 단계의 실패는 같은 작업 식별자에 대해 복구용 Minutes 전체와 Template provenance를 원자적으로 복원하고 `REVIEW`로 전이한다. 실패 처리 경합에서 이미 종료/대체된 작업은 snapshot을 덮어쓰지 않는다.
7. 실패 복귀도 새로운 Session version을 갖는다. API-010은 일관된 단일 Review snapshot과 다음 metadata를 함께 반환한다.

```json
{
  "lastOperation": {
    "type": "MINUTES_REGENERATION",
    "outcome": "FAILED",
    "requestedTemplateId": "project.md",
    "errorCode": "MINUTES_REGENERATION_FAILED"
  }
}
```

`lastOperation`은 API-010 공통 필수 envelope가 아닌 재생성 기능의 optional extension이다. 성공이면 `outcome:"SUCCEEDED"`, 요청 Template id와 새 Minutes가 같은 snapshot으로 반환된다. 실패는 항상 고정된 안전 코드 `MINUTES_REGENERATION_FAILED`만 제공하며 provider 상세/원문은 제공하지 않는다. 다른 operation이나 일반 `PROCESSING_FAILED`에는 이 extension을 붙이지 않는다. API-010 callers는 모르는 optional field를 무시할 수 있어야 한다.

## 수용 기준

- 정상 수락은 검증된 선택 Template과 동일한 `If-Match` snapshot으로 한 번의 regeneration operation을 시작하고 HTTP 202를 반환한다.
- 재생성 command는 현재 Transcript, Speaker mapping, 필요한 roster 참조 및 선택 Template만 포함하고 Audio/STT/Diarization 경로에 닿지 않는다.
- 성공 결과는 StructuredMinutes 및 Template provenance 검증 후 원자 저장된다. API-010은 `REVIEW`, `DRAFT_READY`, 100%, 성공 `lastOperation`, 새 Minutes를 일관된 version으로 반환한다.
- 모든 regeneration 실패는 이전 저장 Minutes 전체와 Template provenance를 복구하고 `REVIEW`로 되돌린다. API-010은 `UPDATE_MINUTES`, `REGENERATE_MINUTES`, `CONFIRM`을 재계산해 포함하고 실패 `lastOperation`을 반환한다.
- API-010 실패 read에서 이전 Minutes가 바이트/필드 기준 동등하게 보존되고 Transcript와 Speaker mapping이 유지된다. Session version은 이전 값보다 크다.
- provider/schema/persistence 오류 원문, prompt, Transcript, Minutes 및 Secret은 일반 응답/로그에 노출되지 않는다.
- 중복 idempotent replay는 operation/provider 호출을 중복하지 않고, key 충돌·version conflict·상태 충돌·입력 오류는 mutation 전에 거부된다.
- 빈 Transcript는 provider 호출 없이 선택 Template의 빈 StructuredMinutes를 성공 저장한다.
- TASK-009.02가 API-010의 optional `lastOperation` 결과를 사용해 요청 Template 적용 여부와 안전한 실패를 식별할 수 있다.

## 의존성 및 근거

- TASK-008.03 (#39): API-010 versioned Review snapshot과 API-012 저장/requery 계약
- TASK-006.05 (#31): `MinutesGenerationProvider`, StructuredMinutes 검증 및 빈 Transcript 처리
- TASK-006.06 (#32): API-010 공통 응답과 일반 `PROCESSING_FAILED` 노출 규칙. 본 작업은 regeneration failure에서만 rollback/Review 복귀하도록 명시적 예외를 추가한다.
- PRD `API-013`의 `Idempotency-Key`, `If-Match`, 202 response와 DEC-011/012를 따른다.

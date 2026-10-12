# ⚙️ 문서 서비스 간 표준 자료 export와 import

> PRD v1.9.0 · 2026-10-10 · TASK-022.04 · 영역 BE
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 🎯 결과

양쪽 Adapter가 앱 관리 회의록·Transcript·실패 문서·참석자를 표준 모델로 읽고 반대 Provider에 복사하는 전송 Port를 구현한다. 이후 Publish/History가 같은 문서 codec을 재사용하도록 계약을 고정한다.

## 🔗 선행과 계약

- 선행: TASK-022.02
- 관련: DEC-021~024, FR-031~034, EXT-003, API-017, API-018의 공유 Adapter 기반
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

readManifest는 pagination을 완료하고 source revision/digest를 반환한다. exportMeeting은 schemaVersion 검증을 수행한다. importParticipant/importMeeting은 생성 payload에 transferKey·canonical digest를 기록한다. 기존 target가 같은 키·같은 digest인 경우만 재사용한다. participant ID map으로 mapping/owner 참조를 변경하고 externalSessionId를 보존한다.

## 📦 표준 문서 형식과 전송 marker

Notion과 Confluence 모두 페이지 본문에 같은 schema v1 JSON code block을 저장한다. Provider별 block API만 다르고 JSON 필드·정규화 규칙은 공유 codec 하나를 사용한다. 사용자는 기계용 block을 볼 수 있으며 이를 수정하지 않도록 설명한다. Notion code block과 Confluence preformatted code block을 사용하고, 회의록 본문은 제품 data spec의 고정 순서대로 사람이 읽을 수 있는 heading/paragraph/list로 저장한다. JSON block에는 본문이나 Transcript를 중복해서 넣지 않는다. Provider rich-text 응답의 여러 조각은 순서대로 이어 붙인 뒤 JSON으로 검증한다.

Meeting marker 예시:

```json
{
  "format": "meeting-automation",
  "recordType": "meeting",
  "schemaVersion": "1.0",
  "externalSessionId": "ms_source",
  "meetingAt": "2026-10-10T10:00:00+09:00",
  "templateId": "default.md",
  "participantIds": ["source-pt-1"],
  "speakers": [{"speakerId":"speaker-a","label":"Speaker A","participantId":"source-pt-1"}],
  "failure": null,
  "transfer": {
    "transferKey": "sha256:<hex>",
    "sourceConnectionFingerprint": "sha256:<hex>",
    "sourceDocumentId": "source-page-id",
    "sourceRevision": "provider-revision",
    "canonicalDigest": "sha256:<hex>",
    "participantIdMap": {"source-pt-1":"target-pt-1"}
  }
}
```

`transfer` is omitted for an ordinary meeting created by Publish and required for an imported record. Participant pages use the same envelope with `recordType: "participant"`, schema version, and the participant content digest; their provider page ID remains the local `Participant.id`. Meeting body fields are the standard title/date/template, structured Minutes, Transcript, Speaker mappings, and participant roster from `data-spec.md`. Optional Minutes and Transcript remain absent when the source has none. A failed meeting carries `failure: {"stage":"...","errorCode":"...","audioDisposition":"..."}` and does not synthesize either content section.

The canonical digest is lowercase SHA-256 over UTF-8 canonical JSON of the normalized standard record: object keys are sorted, serialization has no insignificant whitespace, array order is preserved, timestamps use their ISO offset representation, and provider page IDs/URLs, revisions, and transfer envelope are excluded. For a copied record, participant references are translated back through `participantIdMap` before calculating/comparing the source digest. The provider revision is stored separately. A transfer key is lowercase SHA-256 of the canonical JSON object `{sourceConnectionFingerprint, sourceDocumentId}`. The fingerprint identifies provider type plus configured root identity; tokens, account emails, authorization headers, and other credentials are never used in or stored by this value.

## 🔁 Manifest, import, and conflict behavior

- `readManifest` exhausts every provider page cursor and returns every app-managed meeting with source ID, revision, normalized digest, and transfer key. It must not use `listMeetings(max=100)` as a complete manifest. Every child page under the configured Meetings/Participants roots must have one valid supported marker; a missing/duplicate marker, repeated/invalid cursor, malformed page, unknown schema, or broken reference fails the whole preflight; it is never silently skipped.
- The transfer service validates the complete source manifest and references before any target create. It creates/reuses participants first and builds a source participant ID → target participant ID map. It then rewrites Meeting `participantIds`, Speaker `participantId` mappings, and Minutes `ownerParticipantId` references. It preserves `externalSessionId`, title, meeting date, template, content, speaker IDs, and failure metadata.
- Target participants are matched only by transfer key, never by display name or email. A target with the same transfer key and same canonical digest is reusable. A repeated transfer key with a different digest, duplicate marker, duplicate `externalSessionId`, existing unmarked collision, or digest/read-back mismatch is a conflict; the adapter does not overwrite an existing page.
- After a create, the adapter reads the page and marker back and verifies content, references, transfer key, and digest. If a write response is ambiguous, it exhausts target pagination and checks the marker. Exactly one verified matching target is reused; a mismatched or duplicate match is a conflict. If absence or correctness cannot be established, return `RECONCILIATION_REQUIRED` and do not automatically issue another create.
- The source is read-only. Before returning a completed manifest/import result, the source revision/digest is rechecked against preflight. Source edits discovered during the operation invalidate completion. No source page is updated, moved, or deleted.

This task provides provider-neutral codecs and transfer ports/adapters only. It does not add a public migration API, durable migration operation/journal, copy UI, or active-provider switching; TASK-022.05/06 own those flows. The codec and adapter tests use synthetic content. Any real Provider QA uses dedicated test pages and must not copy private meeting content.

## ✅ 수용 기준

- **TRANSFER-ROUNDTRIP**: title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일.
- **TRANSFER-PARTICIPANTS**: source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용.
- **TRANSFER-FAILURE-DOC**: 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음.
- **TRANSFER-PAGINATION**: manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음.
- **TRANSFER-CONFLICT**: preflight/충돌 오류; 덮어쓰기·조용한 누락 없음.
- **TRANSFER-IDEMPOTENCY**: 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# 문서 서비스 간 표준 자료 export와 import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notion과 Confluence 사이에서 앱 관리 회의록·참석자 문서를 손실·중복 없이 표준 형식으로 읽고 복사하는 provider-neutral 전송 계약과 adapter를 구현한다.

**Architecture:** `DocumentTransferPort`가 전체 manifest, export, target participant/meeting import를 제공한다. 공통 codec은 provider JSON marker를 읽고 쓰며 정규화 record의 canonical JSON/SHA-256을 계산하고, 각 adapter는 cursor pagination과 provider page/block API만 담당한다. 전송 흐름은 manifest/reference 사전 검증 후 참석자를 먼저 복사하고 ID 참조를 재작성해 회의록을 생성하며, read-back 및 원본 revision 재검증이 끝나야 성공을 반환한다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Gradle 9.7.1 wrapper, JUnit 5, 기존 Notion/Confluence HTTP adapter 구현. Run Gradle commands from `backend/`.

**Spec:** [TASK-022.04 spec](./spec.md)

## Global Constraints

- 두 provider 모두 같은 `format: meeting-automation`, `schemaVersion: 1.0` JSON marker를 본문 code block에 보관한다.
- Canonical digest는 UTF-8 canonical JSON의 lowercase SHA-256이며 object key sort, 공백 제거, array 순서 유지, provider ID/URL·revision·transfer envelope 제외 규칙을 따른다.
- Transfer key는 `{sourceConnectionFingerprint, sourceDocumentId}` canonical JSON의 lowercase SHA-256이다. credential/account email/auth header는 fingerprint에 포함하지 않는다.
- manifest는 모든 cursor를 소진한다. marker 누락/중복/불량, unknown schema, 반복·불량 cursor, 깨진 참조는 전체 preflight 실패다.
- 원본은 read-only이고, 같은 transfer key·digest만 재사용한다. 충돌은 덮어쓰지 않으며 확인 불가 쓰기는 `RECONCILIATION_REQUIRED`로 끝내고 재생성하지 않는다.
- 코드/계약 테스트는 synthetic meeting/participant 데이터만 사용한다. 공개 API, durable journal, copy UI, active provider 변경은 범위 밖이다.

## Review Focus

- 같은 canonical record라도 JSON 속성 순서가 바뀌어도 digest가 같아야 한다 — Task 1에서 `canonicalDigest_isStableAcrossObjectKeyOrder`로 고정한다.
- provider cursor가 반복되거나 중간 페이지가 누락되면 일부 성공 manifest 대신 preflight 오류여야 한다 — Task 2에서 `readManifest_rejectsRepeatedCursorAndReturnsAllPages`로 고정한다.
- source participant ID와 target ID가 다를 때 meeting, speaker, minutes owner가 모두 target ID로 바뀌어야 한다 — Task 3에서 `importMeeting_rewritesEveryParticipantReference`로 고정한다.
- create 응답이 불명확할 때 target에서 하나의 검증된 marker가 확인되지 않으면 두 번째 create 없이 reconciliation 오류여야 한다 — Task 3에서 `importMeeting_doesNotRetryUnverifiedAmbiguousCreate`로 고정한다.
- 원본 revision이 작업 중 바뀌면 완료로 보고하지 않고 원본을 변경하지 않아야 한다 — Task 3에서 `transfer_rejectsSourceChangedAfterPreflight`로 고정한다.

---

### Task 1: 표준 transfer model, marker codec, canonical identity

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/DocumentTransferPort.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferManifest.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferRecord.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferMarker.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferCodec.java`
- Create: `backend/src/test/java/com/meetingautomation/document/transfer/TransferCodecTests.java`
- Modify only if model additions are required: `MeetingMetadata.java`, `MeetingDocument.java`, `CreateMeetingCommand.java`

**Interfaces:**
- `DocumentTransferPort.readManifest() -> TransferManifest`
- `DocumentTransferPort.exportMeeting(String sourceDocumentId) -> TransferRecord`
- `DocumentTransferPort.importParticipant(TransferRecord, String sourceConnectionFingerprint) -> Participant`
- `DocumentTransferPort.importMeeting(TransferRecord, Map<String,String> participantIdMap, String sourceConnectionFingerprint) -> MeetingDocumentRef`
- `TransferCodec.parseMarker(List<String> codeBlockText) -> TransferMarker`; `encodeMarker(TransferMarker) -> String`; require exactly one supported marker and concatenate rich-text fragments in order.
- `TransferCodec.canonicalDigest(TransferRecord) -> String`; `TransferCodec.transferKey(String sourceConnectionFingerprint, String sourceDocumentId) -> String`; `connectionFingerprint(String providerType, String configuredRootIdentity) -> String`.

- [x] Write codec tests for v1 marker round-trip, joined text fragments, missing/duplicate/malformed marker and unknown schema; assert concrete parsed fields or typed errors.
- [x] Write digest/key tests including key-order stability, array-order sensitivity, excluded provider metadata/envelope, lowercase SHA-256 and credential-free fingerprint input.
- [x] Run `./gradlew.bat test --tests com.meetingautomation.document.transfer.TransferCodecTests`; verify new tests fail because transfer types/codec are absent.
- [x] Add immutable transfer records and typed validation/conflict exception codes; retain optional Minutes/Transcript and failure metadata without synthesizing content.
- [x] Implement one shared canonical JSON serializer and marker codec; preserve source offsets and array order exactly as spec requires.
- [x] Run the focused test command and `./gradlew.bat test`; both must pass.
- [x] Commit the model/codec and tests as `feat: define canonical document transfer format`.

### Task 2: Complete paginated manifest and provider page codec adapters

**Files:**
- Modify: `backend/src/main/java/com/meetingautomation/document/notion/NotionPageHierarchyAdapter.java`
- Modify: `backend/src/main/java/com/meetingautomation/document/confluence/ConfluencePageHierarchyAdapter.java`
- Modify: `backend/src/test/java/com/meetingautomation/document/notion/NotionPageHierarchyAdapterTests.java`
- Modify: `backend/src/test/java/com/meetingautomation/document/confluence/ConfluencePageHierarchyAdapterTests.java`
- Create or extend: `backend/src/test/java/com/meetingautomation/document/DocumentProviderContractTest.java`

**Interfaces:**
- Both adapters implement the Task 1 `DocumentTransferPort` signatures.
- Provider-specific private page mappers convert meeting bodies to/from existing `MeetingDocument`, `StructuredMinutes`, `TranscriptSegment`, `Participant`, and v1 `TransferMarker`. Ordinary participant creates/updates emit or refresh the participant marker digest; legacy unmarked children remain preflight failures and are never auto-adopted.
- Manifest entries contain provider page ID, revision, normalized digest, and transfer key; enumerate every child of configured Meetings and Participants roots.

- [x] Add Notion/Confluence adapter contract tests with >100 synthetic children over multiple cursors; assert every page is returned once and cursor exhaustion is reached.
- [x] Add tests that reject repeated/invalid cursor, missing/duplicate marker, malformed JSON, unsupported schema and broken participant references; assert no partial manifest is returned.
- [x] Run focused adapter tests and record expected failures before implementation.
- [x] Add provider-specific code block read/write mapping: Notion code block and Confluence preformatted block both use the shared Task 1 codec; keep human-readable body sections and marker separate.
- [x] Implement provider cursor traversal without using `listMeetings(100)` as manifest completeness boundary; preserve provider revision separately from canonical digest.
- [x] Add participant CRUD marker tests: create writes one v1 marker with the normalized participant digest; update refreshes the digest; legacy unmarked child still fails transfer preflight. Add create/read-back adapter tests for meeting fields and marker round-trip, including absent optional Minutes/Transcript and failed-document metadata.
- [x] Run focused adapter suites plus `./gradlew.bat test`; all must pass.
- [x] Commit adapter mapping and pagination as `feat: add paginated transfer support to document providers`.

### Task 3: Safe participant-first copy, reconciliation, and source stability

**Files:**
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/DocumentTransferService.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferConflictException.java`
- Create: `backend/src/main/java/com/meetingautomation/document/transfer/TransferResult.java`
- Create: `backend/src/test/java/com/meetingautomation/document/transfer/DocumentTransferServiceTests.java`
- Modify: provider adapter tests/contract fixtures only where required by the new port.

**Interfaces:**
- `DocumentTransferService.transfer(DocumentTransferPort source, DocumentTransferPort target) -> TransferResult`
- `TransferResult` returns manifest/copy outcomes only after target read-back and source revision/digest recheck.
- Participant import is keyed only by transfer key + canonical digest; never by name/email. Meeting import receives the complete source-to-target participant ID map.

- [x] Add service tests for participant-first import, all participant/Speaker/action owner ID rewrites, stable `externalSessionId`, round-trip meeting body, and failed records with optional content absent.
- [x] Add conflict tests for transfer-key/digest mismatch, duplicate transfer key/externalSessionId, unmarked target collision, duplicate target marker, and digest/read-back mismatch; assert no overwrite and typed conflict reason.
- [x] Add ambiguous-create tests: exactly one matching verified target is reused; zero/multiple/unverified matches return `RECONCILIATION_REQUIRED` and target create is called only once.
- [x] Add source revision mutation test; assert transfer fails and no source mutation call occurs.
- [x] Run focused tests to capture failing behavior, then implement preflight-all-before-create, participant mapping, meeting copy, target reconciliation and final source recheck.
- [x] Run `./gradlew.bat test` and `./gradlew.bat clean build`; confirm both exit 0.
- [x] Commit service and test suite as `feat: implement safe document transfer workflow`.

### Task 4: Integration regression and handoff evidence

**Files:**
- Modify: `docs/specs/phase-02-document-setup/TASK-022.04/tasks.md`
- Modify: `docs/specs/phase-02-document-setup/TASK-022.04/test.md`
- Create after QA: `docs/evidence/TASK-022.04/verification.md`

- [ ] Run `./gradlew.bat test` and `./gradlew.bat clean build` on the final candidate SHA; record exact command/exit status.
- [x] Run `node .codex/automation/workflow.mjs inspect`; inspect issue/PR, task cursor and checkpoint before publication.
- [x] Update task checklist and test plan with actual results; never mark DONE from design completion alone.
- [ ] Commit implementation changes, push `codex/issue-99-document-transfer`, open/update one PR targeting `master`, and record exact head SHA.
- [ ] Complete independent code review and local QA through the PRD workflow; record evidence without secrets or meeting content, then leader merges only when every gate matches the exact head.

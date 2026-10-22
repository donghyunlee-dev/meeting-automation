# Notion Meeting Index Implementation Plan

**Goal:** Use a Notion index table for meeting history and fetch detail pages directly by ID.

**Architecture:** Bootstrap a Notion-only `Meeting Index` page beneath the existing `Meeting Data` page. Extend the transfer port with summary listing and provider hooks so Notion can index list data and duplicate lookup while Confluence keeps its current implementation.

**Tech Stack:** Java 25, Spring Boot, Jackson, Spring `RestClient`, JUnit 5, AssertJ, `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-10-22-notion-meeting-index-design.md`

## Global Constraints

- Do not add dependencies or change provider setup contracts outside what the Notion index needs.
- Do not backfill old meeting pages.
- Do not add index write retry, repair, or deletion behavior.
- Keep Confluence history listing behavior unchanged.

## Review Focus

- A missing or duplicate index/table must fail with existing safe provider errors, not silently scan all meeting pages.
- Index pagination must consume all result cursors and reject repeated cursors.
- Row parsing must preserve participant IDs/names and safely handle text split across rich-text runs.
- Empty participant lists remain valid; malformed rows must fail visibly.
- Detail lookup must preserve `MEETING_NOT_FOUND` and must not list meeting pages first.

## File map

- `document/bootstrap/BootstrapPages.java`, `NotionBootstrapPages.java`, `DocumentStructureInitializer.java`: create/reuse the Notion index during setup.
- `document/notion/NotionMeetingIndex.java`: encode/decode the table rows and issue index requests.
- `document/transfer/DocumentTransferPort.java`, `ProviderTransferSupport.java`: summary-list API and provider hooks with existing defaults.
- `document/notion/NotionPageHierarchyAdapter.java`: Notion index integration for setup, list, publish, and duplicate lookup.
- `api/meeting/MeetingListService.java`, `MeetingDetailService.java`: use summary listing and direct export.
- Existing related tests: add behavior coverage at the bootstrap, Notion adapter, transfer API, and history API boundaries.

### Task 1: Create the index at Notion connection bootstrap

**Tests:** `DocumentStructureInitializerTests`, `NotionBootstrapPagesTests`.

- Add a provider hook to ensure an index beneath `Meeting Data`; default no-op preserves Confluence.
- Notion bootstrap creates a `Meeting Index` page with a marked table and six fixed columns, or reuses the unique existing page.
- Add a test proving both new and reused roots ensure the index exactly once.

### Task 2: Add index table reading and writing

**Tests:** `NotionMeetingIndexTests`, `NotionPageHierarchyAdapterTests`.

- Store document ID, external session ID, title, meeting time, participant summaries, and URL in each row.
- Read table rows through cursor pagination and decode into `MeetingSummary`.
- Append a row after successful meeting-page publication; do not add retry/repair behavior.
- Use the index for Notion session-ID lookup, reading the matching page directly for full-record verification.

### Task 3: Route APIs through the index and direct page lookup

**Tests:** `MeetingListApiTests`, `MeetingDetailApiTests`, `DocumentTransferAdapterTests`.

- Add `listMeetingSummaries()` to `DocumentTransferPort`, defaulting to the existing full-record implementation.
- Notion overrides that method with index rows; Confluence uses the default behavior.
- `MeetingListService` maps summaries and keeps the existing sort/limit behavior.
- `MeetingDetailService` calls `exportMeeting(documentId)` directly.
- Test that list never calls full record listing for Notion summaries and detail never invokes list.

### Task 4: Validate integration

- Run focused bootstrap, Notion adapter, and meeting API tests.
- Run `./gradlew test` from `backend/`.
- Inspect final diff and preserve the unrelated dirty files in the original checkout.

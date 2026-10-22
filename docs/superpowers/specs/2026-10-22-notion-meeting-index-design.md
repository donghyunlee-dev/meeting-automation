# Notion Meeting Index Design

## Goal

Make meeting history and detail reads fast by storing a compact index in Notion and reading a meeting page directly by its ID.

## Scope and behavior

- During document connection bootstrap, create a `Meeting Index` child page under `Meeting Data` for Notion connections.
- Put a table with columns for meeting page ID, session ID, title, meeting time, participant data, and document URL on that page.
- When a meeting is published, append its metadata row after the meeting page has been created and verified.
- List API reads metadata rows from the index; it does not open every meeting page or participant page.
- Detail API reads the requested meeting page directly using its document ID.
- Duplicate-session lookup during publish uses the index and opens only the matching meeting page for verification.
- Do not backfill pre-existing meeting pages. Only meetings published after index creation appear in the index.
- Do not add index write retries, reconciliation, repair, deletion handling, or unrelated provider changes. Confluence keeps its current list behavior.

## Data flow

Connection bootstrap ensures `Meeting Data` and then ensures its `Meeting Index` child page and table. A successful publish writes the existing full meeting record under `Meeting Data`, verifies that page, then appends one compact table row. History listing reads the index table and maps each row to `MeetingSummary`. Detail lookup calls `exportMeeting(documentId)` directly and retains current response validation.

## Failure behavior

Follow existing Notion API error translation. No special retry or repair path is added for an index row write. Existing meeting-page creation and detail validation behavior remains in place.

## Verification

Add regression coverage for index bootstrap/reuse, table row encode/decode and pagination, summary-only history reads, direct detail reads, and index-based duplicate lookup. Run focused tests and the complete backend test suite.

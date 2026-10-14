import { describe, expect, it } from 'vitest';
import { filterMeetings, groupMeetingsByLocalMonth, hasInvalidDateRange } from './meetingFilters';
import type { MeetingSummary } from './meetingListApi';

const meetings: MeetingSummary[] = [
  { documentId: 'new', title: 'Ｆｕｌｌ width Review', meetingAt: '2026-11-01T12:00:00', participants: [{ id: 'p1', name: 'Ada Lovelace' }], documentUrl: null },
  { documentId: 'middle', title: 'Planning', meetingAt: '2026-10-31T12:00:00', participants: [{ id: 'p2', name: 'Grace Hopper' }], documentUrl: null },
  { documentId: 'old', title: 'Retrospective', meetingAt: '2026-10-01T12:00:00', participants: [{ id: 'p3', name: 'Lin Chen' }], documentUrl: null },
];

describe('meeting filters', () => {
  it('matches title and participant names using trimmed NFKC case-insensitive substrings without exposing email', () => {
    expect(filterMeetings(meetings, { query: '  FULL width  ', from: '', to: '' }).map(({ documentId }) => documentId)).toEqual(['new']);
    expect(filterMeetings(meetings, { query: 'GRACE HOPPER', from: '', to: '' }).map(({ documentId }) => documentId)).toEqual(['middle']);
    expect(filterMeetings(meetings, { query: 'private@example.com', from: '', to: '' })).toEqual([]);
  });

  it('combines text and inclusive local date boundaries with AND while preserving API order', () => {
    expect(filterMeetings(meetings, { query: 'review', from: '2026-11-01', to: '2026-11-01' }).map(({ documentId }) => documentId)).toEqual(['new']);
    expect(filterMeetings(meetings, { query: 'review', from: '2026-10-01', to: '2026-10-31' })).toEqual([]);
    expect(filterMeetings(meetings, { query: '', from: '', to: '2026-10-31' }).map(({ documentId }) => documentId)).toEqual(['middle', 'old']);
    expect(filterMeetings(meetings, { query: '', from: '2026-10-31', to: '' }).map(({ documentId }) => documentId)).toEqual(['new', 'middle']);
  });

  it('ignores a reversed date range and groups filtered records by local month without reordering records', () => {
    expect(hasInvalidDateRange('2026-11-01', '2026-10-01')).toBe(true);
    expect(filterMeetings(meetings, { query: '', from: '2026-11-01', to: '2026-10-01' })).toEqual(meetings);
    expect(groupMeetingsByLocalMonth(meetings).map(({ label, meetings: group }) => [label, group.map(({ documentId }) => documentId)])).toEqual([
      ['2026년 11월', ['new']], ['2026년 10월', ['middle', 'old']],
    ]);
  });
});


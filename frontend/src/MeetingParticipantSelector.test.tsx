import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MeetingParticipantSelector } from './MeetingParticipantSelector';
afterEach(cleanup);
it('searches department without losing selections and supports legacy participants', () => {
  render(<MeetingParticipantSelector participants={[{ id: 'a', name: 'Ada', email: 'a@example.com', department: 'Platform' }, { id: 'b', name: 'Grace', email: 'g@example.com' }]} selectedIds={['b']} isDisabled={false} isReady pickerOpen onTogglePicker={vi.fn()} onToggleParticipant={vi.fn()} />);
  fireEvent.change(screen.getByRole('searchbox', { name: '참석자 검색' }), { target: { value: 'PLATFORM' } });
  expect(screen.getByText('Platform')).toBeTruthy();
  expect(screen.queryByText('Grace')).toBeNull();
  fireEvent.change(screen.getByRole('searchbox', { name: '참석자 검색' }), { target: { value: '' } });
  expect((screen.getByRole('checkbox', { name: 'Grace g@example.com' }) as HTMLInputElement).checked).toBe(true);
});

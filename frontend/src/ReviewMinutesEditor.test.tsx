import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ReviewMinutesEditor } from './ReviewMinutesEditor';

const minutes = {
  templateId: 'default',
  templateVersion: '1',
  summary: '요약',
  discussionPoints: [],
  decisions: [],
  actionItems: [],
  followUps: [],
};

it('shows a neutral, available save button when the draft matches the saved baseline', () => {
  render(<ReviewMinutesEditor
    sessionId="session-1"
    minutes={minutes}
    baseline={minutes}
    participants={[]}
    ownerState="ready"
    saveState={{ sessionId: 'session-1', status: 'idle' }}
    onSave={vi.fn()}
    onChange={vi.fn()}
  />);

  const saveButton = screen.getByRole('button', { name: '저장' });
  expect((saveButton as HTMLButtonElement).disabled).toBe(false);
});

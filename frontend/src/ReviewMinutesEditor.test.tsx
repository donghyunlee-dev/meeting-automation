import { render } from '@testing-library/react';
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

it('keeps the save action available when the draft matches the saved baseline', () => {
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

  const saveButton = document.querySelector<HTMLButtonElement>('.review-flow-dock button.button-secondary');
  expect(saveButton).not.toBeNull();
  expect(saveButton?.disabled).toBe(false);
});

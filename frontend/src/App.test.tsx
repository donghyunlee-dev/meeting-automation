import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('renders the application title as a level-one heading', () => {
    render(<App />);

    expect(screen.getByRole('heading', { level: 1, name: 'Meeting Automation' }).textContent).toBe(
      'Meeting Automation',
    );
  });
});

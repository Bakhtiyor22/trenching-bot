import { describe, expect, it } from 'vitest';
import { formatRelative } from './format';

describe('formatRelative', () => {
  const now = new Date('2026-10-19T12:00:00Z');
  it.each([
    [59, 'just now'],
    [60, '1 min ago'],
    [59 * 60, '59 min ago'],
    [23 * 3600, '23 h ago'],
    [6 * 86400, '6 days ago'],
  ])('formats %s seconds ago', (seconds, expected) => {
    expect(formatRelative(new Date(now.getTime() - seconds * 1000).toISOString(), now)).toBe(
      expected,
    );
  });
  it('switches to the date after seven days', () => {
    expect(formatRelative('2026-10-12T12:00:00Z', now)).toMatch(/Oct|10/);
  });
});

import { describe, expect, it } from 'vitest';
import { formatDay, formatRelative } from './format';

describe('formatDay', () => {
  it('reads a calendar date as the day it names, whatever the time zone', () => {
    expect(formatDay('2026-11-30')).toBe('30 Nov 2026');
    expect(formatDay('2026-01-01')).toBe('1 Jan 2026');
  });
  it('returns text that is not a real date as it was sent', () => {
    expect(formatDay('2026-02-31')).toBe('2026-02-31');
    expect(formatDay('next month')).toBe('next month');
  });
});

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
    // The date is written in the viewer's locale ("12 Oct 2026", "12 окт. 2026 г."), so only its numbers are checked.
    const text = formatRelative('2026-10-12T12:00:00Z', now);
    expect(text).not.toMatch(/ago|just now/);
    expect(text).toMatch(/\b12\b/);
    expect(text).toMatch(/2026/);
  });
});

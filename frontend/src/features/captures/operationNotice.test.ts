import { describe, expect, it } from 'vitest';
import { formatWait, operationNotice } from './operationNotice';

describe('operationNotice', () => {
  it('stays silent while an operation runs or after it succeeded', () => {
    expect(operationNotice({ state: 'RUNNING', code: null, message: null })).toBeNull();
    expect(operationNotice({ state: 'SUCCEEDED', code: null, message: null })).toBeNull();
  });

  it('explains a known code in plain words instead of the backend message', () => {
    expect(
      operationNotice({
        state: 'FAILED',
        code: 'MANIFEST_VERSION',
        message: 'Capture validation failed category=MANIFEST_VERSION',
      })?.detail,
    ).not.toContain('category=');
    expect(
      operationNotice({
        state: 'INCOMPLETE',
        code: 'HORIZON_NOT_ELAPSED',
        message: 'Retry after 12:20 (estimated).',
      }),
    ).toEqual({
      title: 'This run stopped before finishing',
      detail:
        'A market window has not ended on chain yet. The capture retries on its own after the estimated end, or resume it then.',
      code: 'HORIZON_NOT_ELAPSED',
      color: 'amber',
    });
  });

  it('shows the backend message for a code this version has no words for', () => {
    expect(
      operationNotice({ state: 'FAILED', code: 'SOMETHING_NEW', message: 'Provider said no.' })
        ?.detail,
    ).toBe('Provider said no.');
  });

  it('falls back to the stage reason, then to a sentence for the state, so a failure always says why', () => {
    expect(
      operationNotice(
        { state: 'INCOMPLETE', code: null, message: null },
        'MARKET_CAPTURE_INCOMPLETE',
      ),
    ).toMatchObject({
      detail: 'Market coverage is incomplete. Increase scan pages if needed and explicitly resume.',
      code: 'MARKET_CAPTURE_INCOMPLETE',
    });
    expect(
      operationNotice({ state: 'FAILED', code: 'SOMETHING_NEW', message: null }),
    ).toMatchObject({
      title: 'This run failed',
      detail:
        'The run failed without a recorded reason. Inspect the saved evidence before retrying.',
      code: 'SOMETHING_NEW',
      color: 'red',
    });
  });
});

describe('formatWait', () => {
  it.each([
    [45, '45s'],
    [119, '119s'],
    [1200, '20 min'],
    [1201, '21 min'],
    [3600, '1 h'],
    [5400, '1 h 30 min'],
  ])('formats %d seconds as %s', (seconds, text) => {
    expect(formatWait(seconds)).toBe(text);
  });
});

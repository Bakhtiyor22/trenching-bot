import { describe, expect, it } from 'vitest';
import {
  camelCaseWords,
  operationStateLabel,
  stageStateChip,
  summaryKeyLabel,
} from './captureLabels';

describe('capture labels', () => {
  it('puts inspection summary keys into words, including grouped and per-window keys', () => {
    expect(summaryKeyLabel('requestsUsed')).toBe('Requests used');
    expect(summaryKeyLabel('outcome.SAVED')).toBe('Download outcome · saved');
    expect(summaryKeyLabel('rejection.NOT_A_BUY')).toBe('Rejected · not a buy');
    expect(summaryKeyLabel('window.0.mint')).toBe('Window 1 · mint');
    expect(summaryKeyLabel('window.2.detailGaps')).toBe('Window 3 · detail gaps');
    expect(summaryKeyLabel('someNewKey')).toBe('Some new key');
  });

  it('words camelCase keys and operation states', () => {
    expect(camelCaseWords('medianErrorBps')).toBe('Median error bps');
    expect(operationStateLabel('STOPPED_BUDGET')).toBe('stopped budget');
  });

  it('gives every stage state a word and a tone, unknown states needing a look', () => {
    expect(stageStateChip('READY')).toMatchObject({ label: 'Ready', tone: 'success' });
    expect(stageStateChip('INVALID')).toMatchObject({ label: 'Invalid', tone: 'failure' });
    expect(stageStateChip('BLOCKED_BY_GAPS')).toMatchObject({
      label: 'Blocked by gaps',
      tone: 'attention',
    });
  });
});

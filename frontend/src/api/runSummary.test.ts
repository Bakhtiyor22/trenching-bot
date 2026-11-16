import { describe, expect, it } from 'vitest';
import { oldShapeRun, sampleRun } from '../mocks/fixtures';
import {
  deriveRunStatus,
  normalizeRunPage,
  normalizeRunSummary,
  type RawRunSummary,
} from './runSummary';

/** A run as a backend from before server-side run search sends it. */
function oldRun(over: Partial<RawRunSummary> = {}): RawRunSummary {
  const old = oldShapeRun(sampleRun);
  return { ...old, ...over };
}

describe('run summaries from either backend', () => {
  it('derives the status with rule R1 from the exact counts and P&L', () => {
    expect(deriveRunStatus({ filledCount: 0, openCount: 0, realizedPnl: '1' })).toBe('NO_ENTRY');
    expect(deriveRunStatus({ filledCount: 2, openCount: 1, realizedPnl: '-1' })).toBe('OPEN');
    expect(deriveRunStatus({ filledCount: 1, openCount: 0, realizedPnl: '0.000000000001' })).toBe(
      'PROFIT',
    );
    expect(deriveRunStatus({ filledCount: 1, openCount: 0, realizedPnl: '-0.000000000001' })).toBe(
      'LOSS',
    );
    expect(deriveRunStatus({ filledCount: 1, openCount: 0, realizedPnl: '0.000000000000' })).toBe(
      'FLAT',
    );
  });

  it('fills the fields an old backend leaves out and keeps the ones a new backend sends', () => {
    expect(normalizeRunSummary(oldRun())).toMatchObject({
      status: 'LOSS',
      datasetName: null,
      mints: [],
      anchorBlockTime: null,
    });
    expect(normalizeRunSummary(sampleRun)).toEqual(sampleRun);
  });

  it('keeps the request time as the snapshot and leaves missing counts null, never zero', () => {
    const page = normalizeRunPage(
      { items: [oldRun()], page: 0, size: 20, total: 1 },
      '2026-11-16T09:00:00.000Z',
    );
    expect(page.asOf).toBe('2026-11-16T09:00:00.000Z');
    expect(page.statusCounts).toBeNull();
    expect(page.items[0]!.mints).toEqual([]);
  });
});

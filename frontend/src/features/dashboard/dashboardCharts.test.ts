import { describe, expect, it } from 'vitest';
import type { RecentRun } from '../../api/types';
import { cumulativePnlOption, cumulativePnlSteps, runIdFromClick } from './dashboardCharts';

const SOL = 'So11111111111111111111111111111111111111112';
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';

const run = (id: string, createdAt: string, realizedPnl: string, quoteMint = SOL): RecentRun => ({
  id,
  createdAt,
  quoteMint,
  realizedPnl,
});

describe('cumulativePnlSteps', () => {
  it('sums each quote mint exactly, oldest first, whatever order the runs arrive in', () => {
    const steps = cumulativePnlSteps([
      run('b', '2026-10-05T11:48:49Z', '-3.580241077133'),
      run('c', '2026-10-06T00:00:00Z', '1.5', USDC),
      run('a', '2026-10-02T21:42:32Z', '26.089701492540'),
    ]);
    expect(steps.get(SOL)?.map((s) => [s.runId, s.total])).toEqual([
      ['a', '26.089701492540'],
      ['b', '22.509460415407'],
    ]);
    // A different mint starts its own total and is never added to SOL.
    expect(steps.get(USDC)?.map((s) => s.total)).toEqual(['1.5']);
  });
});

describe('cumulativePnlOption', () => {
  it('draws one step line per mint and opens the clicked run', () => {
    const now = Date.parse('2026-10-09T00:00:00Z');
    const option = cumulativePnlOption(
      [run('a', '2026-10-02T21:42:32Z', '26.08'), run('b', '2026-10-03T00:00:00Z', '1', USDC)],
      now,
    );
    const series = option.series as Array<{ name: string; step: string; data: unknown[] }>;
    expect(series.map((s) => [s.name, s.step])).toEqual([
      ['SOL', 'end'],
      ['EPjF…Dt1v', 'end'],
    ]);
    // Each line starts from zero before its first run and holds its total until now; the anchors open nothing.
    const sol = series[0]!.data as Array<{ value: [number, number] }>;
    expect(sol.map((d) => d.value[1])).toEqual([0, 26.08, 26.08]);
    expect(sol.at(-1)?.value[0]).toBe(now);
    expect(runIdFromClick({ data: sol[0] })).toBeNull();
    expect(runIdFromClick({ data: sol[1] })).toBe('a');
  });
});

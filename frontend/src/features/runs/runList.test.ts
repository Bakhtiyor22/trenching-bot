import { describe, expect, it } from 'vitest';
import type { RunSummary } from '../../api/types';
import { edgeRun, sampleRun } from '../../mocks/fixtures';
import { DEFAULT_SORT, filterRuns, sortRuns, toggleSort } from './runList';

const run = (
  id: string,
  realizedPnl: string,
  createdAt: string,
  scenarioCount = 1,
): RunSummary => ({
  ...sampleRun,
  id,
  realizedPnl,
  createdAt,
  scenarioCount,
});

const winner = run('aaaa1111', '26.089701492540', '2026-10-02T21:42:32Z', 3);
const loser = run('bbbb2222', '-3.580241077133', '2026-10-05T11:48:49Z', 2);
const flat = run('cccc3333', '0.000000000000', '2026-10-04T00:00:00Z', 5);
const names = new Map([[sampleRun.datasetId, 'dip-sample-v3']]);

describe('filterRuns', () => {
  it('keeps runs by the sign of their P&L', () => {
    const runs = [winner, loser, flat];
    expect(filterRuns(runs, '', 'profit', names)).toEqual([winner]);
    expect(filterRuns(runs, '', 'loss', names)).toEqual([loser]);
    expect(filterRuns(runs, '', 'flat', names)).toEqual([flat]);
    expect(filterRuns(runs, '', 'all', names)).toHaveLength(3);
  });

  it('matches any part of the run id, dataset id, config hash or dataset name', () => {
    expect(filterRuns([winner, loser], 'BBBB', 'all', names)).toEqual([loser]);
    expect(filterRuns([winner, edgeRun], 'dip-sample', 'all', names)).toEqual([winner]);
    expect(filterRuns([winner], sampleRun.configurationHash.slice(10, 20), 'all', names)).toEqual([
      winner,
    ]);
    expect(filterRuns([winner], 'nothing-like-this', 'all', names)).toEqual([]);
  });
});

describe('sortRuns', () => {
  it('sorts newest first by default and by exact P&L or scenario count on request', () => {
    const runs = [winner, loser, flat];
    expect(sortRuns(runs, DEFAULT_SORT).map((r) => r.id)).toEqual([
      'bbbb2222',
      'cccc3333',
      'aaaa1111',
    ]);
    expect(sortRuns(runs, { key: 'pnl', descending: true }).map((r) => r.id)).toEqual([
      'aaaa1111',
      'cccc3333',
      'bbbb2222',
    ]);
    expect(sortRuns(runs, { key: 'pnl', descending: false }).map((r) => r.id)).toEqual([
      'bbbb2222',
      'cccc3333',
      'aaaa1111',
    ]);
    expect(sortRuns(runs, { key: 'scenarios', descending: true }).map((r) => r.id)).toEqual([
      'cccc3333',
      'aaaa1111',
      'bbbb2222',
    ]);
  });

  it('flips the active column and starts a new one descending', () => {
    expect(toggleSort(DEFAULT_SORT, 'created')).toEqual({ key: 'created', descending: false });
    expect(toggleSort(DEFAULT_SORT, 'pnl')).toEqual({ key: 'pnl', descending: true });
  });
});

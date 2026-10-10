import type { RunSummary } from '../../api/types';
import { compareDecimals, decimalSign } from '../../lib/decimal';

export type Outcome = 'all' | 'profit' | 'loss' | 'flat';
export type SortKey = 'created' | 'pnl' | 'scenarios';
export interface Sort {
  key: SortKey;
  /** Descending is the default for every column: newest, best, most. */
  descending: boolean;
}

export const DEFAULT_SORT: Sort = { key: 'created', descending: true };

const OUTCOME_SIGN: Record<Exclude<Outcome, 'all'>, -1 | 0 | 1> = { profit: 1, loss: -1, flat: 0 };

/**
 * Narrows the loaded page of runs by a free-text query and an outcome. The query matches any part of
 * the run id, dataset id, configuration hash or dataset name, case-insensitively.
 */
export function filterRuns(
  runs: RunSummary[],
  query: string,
  outcome: Outcome,
  datasetNames: Map<string, string>,
): RunSummary[] {
  const needle = query.trim().toLowerCase();
  return runs.filter((run) => {
    if (outcome !== 'all' && decimalSign(run.realizedPnl) !== OUTCOME_SIGN[outcome]) return false;
    if (!needle) return true;
    const haystack = [
      run.id,
      run.datasetId,
      run.configurationHash,
      datasetNames.get(run.datasetId) ?? '',
    ];
    return haystack.some((value) => value.toLowerCase().includes(needle));
  });
}

/** Sorts a copy; P&L is compared exactly as decimal strings. Ties keep the newest first. */
export function sortRuns(runs: RunSummary[], sort: Sort): RunSummary[] {
  const byCreated = (a: RunSummary, b: RunSummary) => a.createdAt.localeCompare(b.createdAt);
  const compare: Record<SortKey, (a: RunSummary, b: RunSummary) => number> = {
    created: byCreated,
    pnl: (a, b) => compareDecimals(a.realizedPnl, b.realizedPnl),
    scenarios: (a, b) => a.scenarioCount - b.scenarioCount,
  };
  const direction = sort.descending ? -1 : 1;
  return [...runs].sort((a, b) => direction * compare[sort.key](a, b) || -byCreated(a, b));
}

/** Clicking the active column flips its direction; another column starts descending. */
export function toggleSort(current: Sort, key: SortKey): Sort {
  return current.key === key ? { key, descending: !current.descending } : { key, descending: true };
}

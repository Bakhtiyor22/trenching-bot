import { decimalSign } from '../lib/decimal';
import type { RunPage, RunStatus, RunSummary } from './types';

/**
 * Run summaries as a backend from before server-side run search sends them: without `status`, `datasetName`, `mints`
 * and `anchorBlockTime`, and pages without `asOf` and `statusCounts`. Every fetcher of runs normalizes here, so no view
 * needs its own guard and both backends render.
 */
export type RawRunSummary = Omit<
  RunSummary,
  'status' | 'datasetName' | 'mints' | 'anchorBlockTime'
> &
  Partial<Pick<RunSummary, 'status' | 'datasetName' | 'mints' | 'anchorBlockTime'>>;

export type RawRunPage = Omit<RunPage, 'items' | 'asOf' | 'statusCounts'> & {
  items: RawRunSummary[];
  asOf?: string | null;
  statusCounts?: RunPage['statusCounts'];
};

/** Plan rule R1: no fill is NO_ENTRY, then any open position is OPEN, then the exact sign of the realized P&L. */
export function deriveRunStatus(
  run: Pick<RunSummary, 'filledCount' | 'openCount' | 'realizedPnl'>,
): RunStatus {
  if (run.filledCount === 0) return 'NO_ENTRY';
  if (run.openCount > 0) return 'OPEN';
  const sign = decimalSign(run.realizedPnl);
  return sign > 0 ? 'PROFIT' : sign < 0 ? 'LOSS' : 'FLAT';
}

export function normalizeRunSummary(run: RawRunSummary): RunSummary {
  return {
    ...run,
    status: run.status ?? deriveRunStatus(run),
    datasetName: run.datasetName ?? null,
    mints: run.mints ?? [],
    anchorBlockTime: run.anchorBlockTime ?? null,
  };
}

/**
 * A page of runs. Without `asOf` the snapshot is the time the request was sent (or the `asOf` it asked for); without
 * `statusCounts` it stays null, which says the backend ignored the new filters. Counts are never made up.
 */
export function normalizeRunPage(page: RawRunPage, requestedAsOf: string): RunPage {
  return {
    ...page,
    items: page.items.map(normalizeRunSummary),
    asOf: page.asOf ?? requestedAsOf,
    statusCounts: page.statusCounts ?? null,
  };
}

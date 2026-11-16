import type { RunDetail, RunStatus, RunSummary } from '../api/types';
import { createAutomationDb, MOCK_LABELS } from './automationFixtures';
import { ENGINE_VERSION, SOL_MINT, sampleRunDetail } from './fixtures';

/**
 * Sweep runs the run list serves ahead of the sample runs: 22 runs on the newest three replayed buys of the automation
 * mock (their mint, buy time and dataset), created on 2 Nov. None of them is a loss, so the sample run, the only LOSS,
 * sits on page 2 of 20: the Loss filter must find it on the server, not among the 20 rows of page 1.
 */

const LISTED_RUN_COUNT = 22;
const CREATED_BASE_MS = Date.UTC(2026, 10, 2, 16, 0);
const MINUTE_MS = 60_000;
/** Statuses cycled through the listed runs; LOSS is deliberately absent (see above). */
const CYCLE: RunStatus[] = ['PROFIT', 'FLAT', 'NO_ENTRY', 'OPEN', 'PROFIT'];
const PROFIT_PNL = ['0.031200000000', '0.004100000000', '0.061900000000'];

function hex(seed: number, length: number): string {
  let out = '';
  let x = seed * 2246822519 + 7;
  while (out.length < length) {
    x = (x * 1103515245 + 12345) % 2147483648;
    out += x.toString(16).padStart(8, '0');
  }
  return out.slice(0, length);
}

function counts(status: RunStatus, n: number): Partial<RunSummary> {
  switch (status) {
    case 'NO_ENTRY':
      return { filledCount: 0, rejectedCount: 1, openCount: 0, closedCount: 0 };
    case 'OPEN':
      return { filledCount: 1, rejectedCount: 0, openCount: 1, closedCount: 0 };
    case 'PROFIT':
      return {
        filledCount: 1,
        rejectedCount: 0,
        openCount: 0,
        closedCount: 1,
        realizedPnl: PROFIT_PNL[n % PROFIT_PNL.length]!,
      };
    default:
      return { filledCount: 1, rejectedCount: 0, openCount: 0, closedCount: 1 };
  }
}

function buildListedRuns(): RunSummary[] {
  const buys = createAutomationDb()
    .items.filter((item) => item.state === 'SIMULATED' && item.datasetId && item.blockTime)
    .sort((a, b) => b.blockTime!.localeCompare(a.blockTime!))
    .slice(0, 3);
  return Array.from({ length: LISTED_RUN_COUNT }, (_, n) => {
    const buy = buys[Math.floor(n / MOCK_LABELS.length)]!;
    const label = MOCK_LABELS[n % MOCK_LABELS.length]!;
    const status = CYCLE[n % CYCLE.length]!;
    return {
      id: `${hex(n + 1, 8)}${hex(n + 501, 56)}`,
      datasetId: buy.datasetId!,
      engineVersion: ENGINE_VERSION,
      configurationHash: hex(MOCK_LABELS.indexOf(label) + 400, 64),
      resultChecksum: hex(n + 900, 64),
      quoteMint: SOL_MINT,
      scenarioCount: 1,
      realizedPnl: '0.000000000000',
      ...counts(status, n),
      createdAt: new Date(CREATED_BASE_MS - n * 3 * MINUTE_MS).toISOString(),
      storage: 'DATASET_REFERENCE',
      status,
      datasetName: `Decu · ${buy.mint.slice(0, 4)}…${buy.mint.slice(-4)} · ${label}`,
      mints: [buy.mint],
      anchorBlockTime: buy.blockTime,
    } as RunSummary;
  });
}

export const listedRuns: RunSummary[] = buildListedRuns();

/** A listed run's detail: the sample's configuration with the listed summary and no scenarios. */
export function listedRunDetail(id: string): RunDetail | null {
  const run = listedRuns.find((r) => r.id === id);
  return run ? { ...sampleRunDetail, run, scenarios: [] } : null;
}

const ARRIVAL_EVERY_MS = 20_000;
const ARRIVALS = 30;
const arrivalBase = Date.now();

/**
 * `?mockState=runs-arriving`: one new sweep run every 20 seconds from page load, so the "N new runs" notice can be seen.
 * Only those created by [now] exist.
 */
export function arrivingRuns(now: number): RunSummary[] {
  const template = listedRuns[0]!;
  return Array.from({ length: ARRIVALS }, (_, k) => ({
    ...template,
    id: `${hex(k + 3001, 8)}${hex(k + 4001, 56)}`,
    createdAt: new Date(arrivalBase + (k + 1) * ARRIVAL_EVERY_MS).toISOString(),
  })).filter((run) => Date.parse(run.createdAt) <= now);
}

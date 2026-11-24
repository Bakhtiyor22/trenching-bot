import type { RunQuery, RunStatus, RunSummary } from '../../api/types';
import { RUN_STATUSES } from '../../api/types';
import { MAX_SEARCH_CHARS, MIN_SEARCH_CHARS } from '../../api/queries';
import { isSolanaAddress } from '../../lib/base58';
import { compareDecimals, decimalSign } from '../../lib/decimal';
import { formatCount, mintLabel, shortId } from '../../lib/format';
import { addDays, dayInfo, dayStartInstant, isRealDate } from '../automation/automationModel';

export type StatusFilter = RunStatus | 'all';
export type SortKey = 'created' | 'pnl';
export interface Sort {
  key: SortKey;
  /** Descending is the default for every column: newest, best. */
  descending: boolean;
}

export const DEFAULT_SORT: Sort = { key: 'created', descending: true };

/** URL parameters of the Runs page. Every filter lives in the URL so a shared link shows the same list. */
export const RUN_PARAMS = {
  status: 'status',
  mint: 'mint',
  day: 'day',
  createdFrom: 'from',
  createdTo: 'to',
  datasetId: 'datasetId',
  q: 'q',
  asOf: 'asOf',
  page: 'page',
  sort: 'sort',
} as const;
/** The filters "Clear filters" removes. */
export const FILTER_PARAMS = [
  RUN_PARAMS.status,
  RUN_PARAMS.mint,
  RUN_PARAMS.day,
  RUN_PARAMS.createdFrom,
  RUN_PARAMS.createdTo,
  RUN_PARAMS.datasetId,
  RUN_PARAMS.q,
] as const;
/** The old `?outcome=loss` links still open the matching status. */
export const LEGACY_OUTCOME_PARAM = 'outcome';

/** The filter tabs' words; the API values stay `PROFIT … OPEN`, so saved links keep working. */
export const STATUS_LABELS: Record<RunStatus, string> = {
  PROFIT: 'Profit',
  LOSS: 'Loss',
  FLAT: 'Flat',
  NO_ENTRY: 'Not bought',
  OPEN: 'Still held',
};

/** How a status reads inside "No run is …" ("No run is still held."). */
const STATUS_PHRASES: Record<RunStatus, string> = {
  PROFIT: 'in profit',
  LOSS: 'at a loss',
  FLAT: 'flat',
  NO_ENTRY: 'not bought',
  OPEN: 'still held',
};

/** What happened to a run's position, in one word or two, with the chip tone that says it. */
export interface TradeLabel {
  text: 'Not bought' | 'Still held' | 'Sold';
  tone: 'attention' | 'neutral';
}

/**
 * The Trade column's chip, from the server's `status`. Profit, loss and flat all read "Sold": the P&L's sign and colour
 * already tell them apart, so the chip only says what happened to the position.
 */
export function tradeLabel(run: Pick<RunSummary, 'status'>): TradeLabel {
  switch (run.status) {
    case 'NO_ENTRY':
      return { text: 'Not bought', tone: 'neutral' };
    case 'OPEN':
      return { text: 'Still held', tone: 'attention' };
    case 'PROFIT':
    case 'LOSS':
    case 'FLAT':
      return { text: 'Sold', tone: 'neutral' };
    default: {
      const unknown: never = run.status;
      return unknown;
    }
  }
}

/**
 * The Trade column's dimmed second line, only for a run of more than one token: "2 of 3 tokens bought", with
 * " · 1 still held" when a position is still held. Null for a one-token run, whose chip says it all.
 */
export function tradeDetail(
  run: Pick<RunSummary, 'scenarioCount' | 'filledCount' | 'openCount'>,
): string | null {
  if (run.scenarioCount <= 1) return null;
  const bought = `${formatCount(run.filledCount)} of ${formatCount(run.scenarioCount)} tokens bought`;
  return run.openCount > 0 ? `${bought} · ${formatCount(run.openCount)} still held` : bought;
}

/** The filters as read from the URL, raw: a value that is not usable yet is kept so its field can say why. */
export interface RunFilters {
  status: StatusFilter;
  mint: string;
  /** A trading day, `YYYY-MM-DD`. */
  day: string;
  /** Created range in the viewer's local dates, both included. */
  createdFrom: string;
  createdTo: string;
  datasetId: string | null;
  q: string;
}

/** The status in `?status` (or a legacy `?outcome`), case-insensitive; anything unknown is "all". */
export function parseStatus(value: string | null): StatusFilter {
  const upper = value?.trim().toUpperCase().replace(/-/g, '_');
  return RUN_STATUSES.find((status) => status === upper) ?? 'all';
}

export function readFilters(params: URLSearchParams): RunFilters {
  return {
    status: parseStatus(params.get(RUN_PARAMS.status) ?? params.get(LEGACY_OUTCOME_PARAM)),
    mint: params.get(RUN_PARAMS.mint)?.trim() ?? '',
    day: params.get(RUN_PARAMS.day) ?? '',
    createdFrom: params.get(RUN_PARAMS.createdFrom) ?? '',
    createdTo: params.get(RUN_PARAMS.createdTo) ?? '',
    datasetId: params.get(RUN_PARAMS.datasetId) || null,
    q: params.get(RUN_PARAMS.q) ?? '',
  };
}

/** Why a typed text search is not sent, or null when it is usable (or empty). */
export function queryProblem(q: string): string | null {
  const length = q.trim().length;
  if (length === 0) return null;
  if (length < MIN_SEARCH_CHARS) return `Type at least ${MIN_SEARCH_CHARS} characters.`;
  if (length > MAX_SEARCH_CHARS) return `At most ${MAX_SEARCH_CHARS} characters.`;
  return null;
}

/** Why a typed token is not a filter, or null when it is a Solana address (or empty). */
export function mintProblem(mint: string): string | null {
  return mint && !isSolanaAddress(mint) ? 'Not a Solana token address.' : null;
}

/** Why a trading day is not a filter, or null when it is a real date (or empty). */
export function dayProblem(day: string): string | null {
  return day && !isRealDate(day) ? 'Not a real date.' : null;
}

/** What is wrong with the created range, by end. Each end is optional; both are local dates, both included. */
export function createdRangeProblem(from: string, to: string): { from?: string; to?: string } {
  const problems: { from?: string; to?: string } = {};
  if (from && !isRealDate(from)) problems.from = 'Not a real date.';
  if (to && !isRealDate(to)) problems.to = 'Not a real date.';
  if (!problems.from && !problems.to && from && to && from > to)
    problems.to = 'Must be on or after the start date.';
  return problems;
}

/** Local midnight of a `YYYY-MM-DD` date, as an instant. */
export function localMidnight(date: string): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return new Date(year, month - 1, day).toISOString();
}

/** The automation clock trading days are cut on; `/api/automation/status` gives it. */
export interface DayClock {
  zone: string;
  dayStart: string;
}

/**
 * `GET /api/runs` for the filters, sending only usable values: an invalid token or date, or a too-short text, is left
 * out (its field says why). A trading day needs the automation clock; without one it is left out too.
 */
export function toRunQuery(
  filters: RunFilters,
  clock: DayClock | null,
  page: number,
  size: number,
  asOf: string | null,
): RunQuery {
  const day = !dayProblem(filters.day) && filters.day && clock ? filters.day : null;
  const created = createdRangeProblem(filters.createdFrom, filters.createdTo);
  const rangeOk = !created.from && !created.to;
  const q = filters.q.trim();
  return {
    page,
    size,
    datasetId: filters.datasetId,
    status: filters.status === 'all' ? null : filters.status,
    mint: filters.mint && !mintProblem(filters.mint) ? filters.mint : null,
    anchorFrom: day && clock ? dayStartInstant(day, clock.zone, clock.dayStart) : null,
    anchorTo: day && clock ? dayStartInstant(addDays(day, 1), clock.zone, clock.dayStart) : null,
    createdFrom: rangeOk && filters.createdFrom ? localMidnight(filters.createdFrom) : null,
    createdTo: rangeOk && filters.createdTo ? localMidnight(addDays(filters.createdTo, 1)) : null,
    q: q && !queryProblem(q) ? q : null,
    asOf,
  };
}

/**
 * How the list shows a run's realized P&L. With no position closed nothing was realized, so it is missing ("—", with
 * why), not zero: every "Not bought" run, and a run whose positions are all still held. A zero P&L of a run that did
 * close something stays "0", without a USDT line, which would only say "≈ 0.00". Anything else shows its value and,
 * when there is a rate, its USDT line.
 */
export type PnlDisplay = { kind: 'none'; reason: string } | { kind: 'value'; usdt: boolean };

export function pnlDisplay(
  run: Pick<RunSummary, 'closedCount' | 'status' | 'realizedPnl'>,
): PnlDisplay {
  if (run.closedCount === 0)
    return {
      kind: 'none',
      reason:
        run.status === 'NO_ENTRY'
          ? 'Not bought, so nothing was realized'
          : 'Still held, nothing sold yet',
    };
  return { kind: 'value', usdt: decimalSign(run.realizedPnl) !== 0 };
}

/** Plain words for each filter in effect, for the empty state ("Loss", "token 5bYU…okbDs", ...). */
export function activeFilterLabels(
  filters: RunFilters,
  datasetName: string | null | undefined,
): string[] {
  const labels: string[] = [];
  if (filters.status !== 'all') labels.push(STATUS_PHRASES[filters.status]);
  if (filters.mint && !mintProblem(filters.mint)) labels.push(`token ${mintLabel(filters.mint)}`);
  if (filters.day && !dayProblem(filters.day))
    labels.push(`trading day ${dayInfo(filters.day).label}`);
  const created = createdRangeProblem(filters.createdFrom, filters.createdTo);
  if ((filters.createdFrom || filters.createdTo) && !created.from && !created.to)
    labels.push(
      `created ${filters.createdFrom ? dayInfo(filters.createdFrom).short : 'any time'} to ${
        filters.createdTo ? dayInfo(filters.createdTo).short : 'now'
      }`,
    );
  if (filters.datasetId) labels.push(`dataset ${datasetName || shortId(filters.datasetId)}`);
  if (filters.q.trim() && !queryProblem(filters.q)) labels.push(`text “${filters.q.trim()}”`);
  return labels;
}

export function hasFilters(filters: RunFilters): boolean {
  return (
    filters.status !== 'all' ||
    filters.mint !== '' ||
    filters.day !== '' ||
    filters.createdFrom !== '' ||
    filters.createdTo !== '' ||
    filters.datasetId !== null ||
    filters.q.trim() !== ''
  );
}

/** Sorts a copy; P&L is compared exactly as decimal strings. Ties keep the newest first. */
export function sortRuns(runs: RunSummary[], sort: Sort): RunSummary[] {
  const byCreated = (a: RunSummary, b: RunSummary) => a.createdAt.localeCompare(b.createdAt);
  const compare: Record<SortKey, (a: RunSummary, b: RunSummary) => number> = {
    created: byCreated,
    pnl: (a, b) => compareDecimals(a.realizedPnl, b.realizedPnl),
  };
  const direction = sort.descending ? -1 : 1;
  return [...runs].sort((a, b) => direction * compare[sort.key](a, b) || -byCreated(a, b));
}

/** Clicking the active column flips its direction; another column starts descending. */
export function toggleSort(current: Sort, key: SortKey): Sort {
  return current.key === key ? { key, descending: !current.descending } : { key, descending: true };
}

const SORT_KEYS: readonly SortKey[] = ['created', 'pnl'];
const ASCENDING_SUFFIX = '-asc';

/** The sort in `?sort` ("pnl", "pnl-asc", ...); anything unknown is the default. */
export function parseSort(value: string | null): Sort {
  if (!value) return DEFAULT_SORT;
  const ascending = value.endsWith(ASCENDING_SUFFIX);
  const key = SORT_KEYS.find(
    (k) => k === (ascending ? value.slice(0, -ASCENDING_SUFFIX.length) : value),
  );
  return key ? { key, descending: !ascending } : DEFAULT_SORT;
}

/** `?sort` for a sort, or null for the default so the URL stays short. */
export function serializeSort(sort: Sort): string | null {
  if (sort.key === DEFAULT_SORT.key && sort.descending === DEFAULT_SORT.descending) return null;
  return sort.descending ? sort.key : `${sort.key}${ASCENDING_SUFFIX}`;
}

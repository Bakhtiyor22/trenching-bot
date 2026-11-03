import { STEPS } from '../../api/automation';
import type {
  AutomationBackfill,
  AutomationDaySummary,
  AutomationEvaluation,
  AutomationItem,
  AutomationItemState,
  AutomationSession,
  AutomationStatus,
  EvaluationStatus,
} from '../../api/automation';
import { compareDecimals, decimalSign, isDecimalString, toChartNumber } from '../../lib/decimal';
import { reasonText } from '../captures/captureReasons';

/**
 * Pure helpers of the Automation pages: the automation's own time zone, trading days, buy-state buckets, scoreboard
 * bar geometry, the status-by-day grid, backfill forms and progress, and plain-language outcome labels.
 */

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;
/** The backend's default trading-day start; `/status` gives the configured one. */
export const DEFAULT_DAY_START = '12:00';
const OFFSET_ZONE = /^([+-])(\d{2}):?(\d{2})$/;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** The parts of an instant as read on a clock in `zone`; `zone` is the backend's `ZoneId` ("+05:00", "Z" or a region). */
interface ZoneParts {
  year: number;
  month: number;
  day: number;
  weekday: number;
  hour: number;
  minute: number;
  second: number;
}

function offsetMinutes(zone: string): number | null {
  if (zone === 'Z' || zone === 'UTC') return 0;
  const match = OFFSET_ZONE.exec(zone);
  if (!match) return null;
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === '-' ? -minutes : minutes;
}

function utcParts(date: Date): ZoneParts {
  return {
    year: date.getUTCFullYear(),
    month: date.getUTCMonth(),
    day: date.getUTCDate(),
    weekday: date.getUTCDay(),
    hour: date.getUTCHours(),
    minute: date.getUTCMinutes(),
    second: date.getUTCSeconds(),
  };
}

export function zoneParts(iso: string, zone: string): ZoneParts {
  const ms = Date.parse(iso);
  const offset = offsetMinutes(zone);
  if (offset != null) return utcParts(new Date(ms + offset * 60_000));
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
      hourCycle: 'h23',
    }).formatToParts(new Date(ms));
    const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
    const local = Date.UTC(
      get('year'),
      get('month') - 1,
      get('day'),
      get('hour'),
      get('minute'),
      get('second'),
    );
    return utcParts(new Date(local));
  } catch {
    // An unknown zone id: fall back to UTC rather than the browser's zone, and say so through zoneLabel.
    return utcParts(new Date(ms));
  }
}

/** "UTC+5", "UTC−3:30", "UTC", or the region id. */
export function zoneLabel(zone: string): string {
  const offset = offsetMinutes(zone);
  if (offset == null) return zone;
  if (offset === 0) return 'UTC';
  const sign = offset > 0 ? '+' : '−';
  const abs = Math.abs(offset);
  const minutes = abs % 60;
  return `UTC${sign}${Math.floor(abs / 60)}${minutes ? `:${String(minutes).padStart(2, '0')}` : ''}`;
}

const pad = (n: number) => String(n).padStart(2, '0');

/** "21:31" in the zone. */
export function zoneTime(iso: string | null | undefined, zone: string, seconds = false): string {
  if (!iso) return '—';
  const p = zoneParts(iso, zone);
  return `${pad(p.hour)}:${pad(p.minute)}${seconds ? `:${pad(p.second)}` : ''}`;
}

/** "2 Nov, 21:31" in the zone. */
export function zoneDateTime(iso: string | null | undefined, zone: string): string {
  if (!iso) return '—';
  const p = zoneParts(iso, zone);
  return `${p.day} ${MONTHS[p.month]}, ${pad(p.hour)}:${pad(p.minute)}`;
}

export interface TradingDayInfo {
  /** `YYYY-MM-DD`, the date the trading day starts on. */
  key: string;
  /** "Mon 2 Nov". */
  label: string;
  /** "2 Nov", for narrow column heads. */
  short: string;
}

function parseKey(key: string): number {
  const [year, month, day] = key.split('-').map(Number) as [number, number, number];
  return Date.UTC(year, month - 1, day);
}

function keyOf(ms: number): string {
  const date = new Date(ms);
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

/** Labels of a trading day named by its start date. */
export function dayInfo(key: string): TradingDayInfo {
  const date = new Date(parseKey(key));
  const day = date.getUTCDate();
  const month = MONTHS[date.getUTCMonth()];
  return { key, label: `${WEEKDAYS[date.getUTCDay()]} ${day} ${month}`, short: `${day} ${month}` };
}

/** The date `days` after `key` (negative for before). */
export function addDays(key: string, days: number): string {
  return keyOf(parseKey(key) + days * DAY_MS);
}

/** Whole days from `from` to `to`; 0 for the same day. */
export function daysBetween(from: string, to: string): number {
  return Math.round((parseKey(to) - parseKey(from)) / DAY_MS);
}

/** Every date from `from` to `to`, both included, oldest first. */
export function dayRange(from: string, to: string): string[] {
  const count = daysBetween(from, to);
  return count < 0 ? [] : Array.from({ length: count + 1 }, (_, i) => addDays(from, i));
}

function startMinutes(start: string): number {
  const [hours = '12', minutes = '0'] = start.split(':');
  return Number(hours) * 60 + Number(minutes);
}

/**
 * The trading day an instant belongs to: its date in the zone after stepping back by the day start (12:00 by default),
 * so a buy at 01:30 belongs to the day that started the previous noon, and a buy exactly at 12:00 to the new day.
 */
export function tradingDayOf(iso: string, zone: string, start = DEFAULT_DAY_START): string {
  const p = zoneParts(iso, zone);
  const local = Date.UTC(p.year, p.month, p.day, p.hour, p.minute, p.second);
  return keyOf(local - startMinutes(start) * 60_000);
}

/** The UTC instant a trading day starts, for instant filters such as the session list's. */
export function dayStartInstant(key: string, zone: string, start = DEFAULT_DAY_START): string {
  const offset = offsetMinutes(zone) ?? 0;
  return new Date(parseKey(key) + (startMinutes(start) - offset) * 60_000).toISOString();
}

/** "12:00 → 12:00 UTC+5", the span every trading day covers. */
export function daySpanText(zone: string, start = DEFAULT_DAY_START): string {
  return `${start} → ${start} ${zoneLabel(zone)}`;
}

const LAMPORTS_PER_SOL_DIGITS = 9;

/** An exact SOL decimal string from an integer lamport string: "2412000000" → "2.412". */
export function lamportsToSol(lamports: string): string {
  const negative = lamports.startsWith('-');
  const digits = lamports.replace(/^[+-]/, '').padStart(LAMPORTS_PER_SOL_DIGITS + 1, '0');
  const integer = digits.slice(0, -LAMPORTS_PER_SOL_DIGITS).replace(/^0+(?=\d)/, '');
  const fraction = digits.slice(-LAMPORTS_PER_SOL_DIGITS).replace(/0+$/, '');
  return `${negative ? '-' : ''}${integer}${fraction ? `.${fraction}` : ''}`;
}

/** An exact P&L string trimmed to `places` for compact cells, rounding toward zero; the exact value goes in a tooltip. */
export function shortSol(value: string, places = 4): string {
  const sign = decimalSign(value);
  const unsigned = value.replace(/^[+-]/, '');
  const [integer = '0', fraction = ''] = unsigned.split('.');
  const cut = fraction.slice(0, places).padEnd(places, '0');
  const zero = /^0*$/.test(integer) && /^0*$/.test(cut);
  const prefix = sign > 0 && !zero ? '+' : sign < 0 && !zero ? '−' : '';
  return `${prefix}${integer}.${cut}`;
}

export type BuyBucket = 'replayed' | 'inProgress' | 'issues' | 'skipped';

export const BUY_BUCKETS: BuyBucket[] = ['replayed', 'inProgress', 'issues', 'skipped'];

const BUCKET_OF: Record<AutomationItemState, BuyBucket> = {
  DISCOVERED: 'inProgress',
  WAITING: 'inProgress',
  BLOCKED_BUDGET: 'inProgress',
  CAPTURING: 'inProgress',
  CAPTURED: 'inProgress',
  SIMULATED: 'replayed',
  FAILED: 'issues',
  ABANDONED: 'issues',
  SKIPPED_NOT_FIRST_BUY: 'skipped',
};

export function bucketOf(state: AutomationItemState): BuyBucket {
  return BUCKET_OF[state];
}

export function buyBuckets(counts: AutomationSession['itemCounts']): Record<BuyBucket, number> {
  const totals: Record<BuyBucket, number> = { replayed: 0, inProgress: 0, issues: 0, skipped: 0 };
  for (const [state, count] of Object.entries(counts)) {
    const bucket = BUCKET_OF[state as AutomationItemState];
    if (bucket) totals[bucket] += count ?? 0;
  }
  return totals;
}

export function bucketLabel(bucket: BuyBucket, captureEnabled = true): string {
  switch (bucket) {
    case 'replayed':
      return 'Replayed';
    case 'inProgress':
      return captureEnabled ? 'In progress' : 'Found, not captured';
    case 'issues':
      return 'Failed or abandoned';
    case 'skipped':
      return 'Not a first buy';
  }
}

export const itemStateLabels: Record<AutomationItemState, string> = {
  DISCOVERED: 'Found',
  WAITING: 'Waiting',
  BLOCKED_BUDGET: 'Waiting for request cap',
  CAPTURING: 'Capturing',
  CAPTURED: 'Captured',
  SIMULATED: 'Replayed',
  FAILED: 'Failed',
  ABANDONED: 'Abandoned',
  SKIPPED_NOT_FIRST_BUY: 'Not a first buy',
};

export const statusText: Record<EvaluationStatus, string> = {
  SUCCESS: 'Success',
  NO_SUCCESS: 'No success',
  NO_RESULT: 'No result',
  PENDING: 'Pending',
};

export const statusGlyph: Record<EvaluationStatus, string> = {
  SUCCESS: '✓',
  NO_SUCCESS: '✕',
  NO_RESULT: '–',
  PENDING: '◷',
};

/** Why a row or day is pending, e.g. "waiting for Sat 31 Oct to complete". */
export function pendingText(pendingDay: string | null | undefined): string {
  return pendingDay
    ? `waiting for ${dayInfo(pendingDay).label} to complete`
    : 'waiting for its days to complete';
}

export type AutomationMode = 'OFF' | 'DISCOVERY_ONLY' | 'CAPTURING';

export function automationMode(
  status: Pick<AutomationStatus, 'enabled' | 'captureEnabled'>,
): AutomationMode {
  if (!status.enabled) return 'OFF';
  return status.captureEnabled ? 'CAPTURING' : 'DISCOVERY_ONLY';
}

/**
 * What each variation of `decu-sweep-1` changes from the agreed strategy (backend `SweepPlans.DECU_SWEEP_1`). Plans are
 * immutable by id, so this copy only grows; a label of another plan shows without a description.
 */
const PLAN_CHANGES: Record<string, Record<string, string>> = {
  'decu-sweep-1': {
    V00: 'agreed (default)',
    V01: 'dip 15%',
    V02: 'dip 25%',
    V03: 'take profit +35%',
    V04: 'take profit +80%',
    V05: 'stop loss −25%',
    V06: 'hold 5 min, exit 6 min',
    V07: 'dip window 60 s',
    V08: 'min signal 1.5 SOL',
    V09: 'min market cap $20k',
  },
};

/** The label every plan treats as its baseline. */
export const BASELINE_LABEL = 'V00';

export function variationChange(planId: string, label: string): string | null {
  return PLAN_CHANGES[planId]?.[label] ?? null;
}

export interface PnlBar {
  /** Left edge and width of the worst-case bar, in percent of the track; 0 sits at 50%. */
  left: number;
  width: number;
  negative: boolean;
  /** The realized tick's position, in percent. */
  tick: number;
}

/** The half-track scale for a set of values: the largest magnitude, never 0, so bars stay finite. */
export function pnlScale(values: string[]): number {
  const max = Math.max(...values.map((v) => Math.abs(toChartNumber(v) ?? 0)), 0);
  return max > 0 ? max : 1;
}

export function pnlBar(worst: string, realized: string, scale: number): PnlBar {
  const position = (value: string) => {
    const n = toChartNumber(value) ?? 0;
    return 50 + Math.max(-50, Math.min(50, (n / scale) * 50));
  };
  const end = position(worst);
  return {
    left: Math.min(50, end),
    width: Math.abs(end - 50),
    negative: end < 50,
    tick: position(realized),
  };
}

export interface GridRow {
  label: string;
  cells: Array<AutomationEvaluation | null>;
}

/**
 * The status-by-day matrix: one column per trading day of `days` (oldest first), one row per variation (label order),
 * each cell that day's row for the variation, or null when the API returned none for it.
 */
export function statusGrid(
  days: string[],
  evaluations: AutomationEvaluation[],
  labels?: string[],
): { columns: TradingDayInfo[]; rows: GridRow[] } {
  const byDay = new Map<string, Map<string, AutomationEvaluation>>();
  for (const evaluation of evaluations) {
    let row = byDay.get(evaluation.day);
    if (!row) byDay.set(evaluation.day, (row = new Map()));
    row.set(evaluation.label, evaluation);
  }
  const columns = [...days].sort().map(dayInfo);
  const allLabels = labels ?? [...new Set(evaluations.map((e) => e.label))].sort();
  return {
    columns,
    rows: allLabels.map((label) => ({
      label,
      cells: columns.map((c) => byDay.get(c.key)?.get(label) ?? null),
    })),
  };
}

/** The newest day of `days` (newest first) with a verdict, else the newest day. */
export function defaultScoredDay(days: AutomationDaySummary[]): AutomationDaySummary | null {
  return days.find((d) => d.bestStatus != null && d.bestStatus !== 'PENDING') ?? days[0] ?? null;
}

/**
 * A day summary's counts as item counts, so it draws with the same buy-state bar as a session. The summary has no
 * count for buys that were not a first buy: on a complete day every other buy is finished, so the rest are those;
 * otherwise they are still in progress.
 */
export function dayItemCounts(day: AutomationDaySummary): AutomationSession['itemCounts'] {
  const rest = Math.max(0, day.buys - day.simulated - day.failed - day.abandoned);
  return {
    SIMULATED: day.simulated,
    FAILED: day.failed,
    ABANDONED: day.abandoned,
    [day.complete ? 'SKIPPED_NOT_FIRST_BUY' : 'CAPTURING']: rest,
  };
}

/** Outcome groups for the sweep-run filter; every code is one the backend stores (`SweepOutcomes.KNOWN`). */
export const OUTCOME_GROUPS: Array<{ group: string; codes: string[] }> = [
  { group: 'Closed', codes: ['TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT', 'WALLET_SOLD'] },
  {
    group: 'Still open at the end',
    codes: ['HORIZON_REACHED', 'UNRESOLVED', 'MIGRATED', 'NO_EXIT_FILL'],
  },
  {
    group: 'No entry',
    codes: [
      'NO_DIP',
      'REFERENCE_BELOW_MIN_MARKET_CAP',
      'DIP_BELOW_MIN_MARKET_CAP',
      'MIGRATED_BEFORE_ENTRY',
      'UNSUPPORTED_SUPPLY',
      'NO_FILL_AFTER_TRIGGER',
      'EXCEEDS_CURVE',
      'INVALID_QUALITY',
      'ZERO_QUANTITY',
    ],
  },
  {
    group: 'Not a signal or no result',
    codes: [
      'SIGNAL_BELOW_MIN_SIZE',
      'SIGNAL_SIZE_UNAVAILABLE',
      'REFERENCE_UNAVAILABLE',
      'NO_CYCLE',
    ],
  },
  {
    group: 'Refused before replay',
    codes: [
      'MARKET_COVERAGE_ENDED',
      'SELL_CHECK_NEEDS_TRADES',
      'SIGNAL_SIZE_NEEDS_TRADES',
      'DATASET_NOT_FOUND',
      'DATASET_INVALID',
    ],
  },
];

/** Short outcome names for tables; the long explanation comes from {@link reasonText}. */
const OUTCOME_SHORT: Record<string, string> = {
  TAKE_PROFIT: 'Take profit',
  STOP_LOSS: 'Stop loss',
  TIME_EXIT: 'Time exit',
  WALLET_SOLD: 'Decu sold',
  HORIZON_REACHED: 'Still open at the end',
  UNRESOLVED: 'Exit could not be paid',
  MIGRATED: 'Migrated while holding',
  NO_EXIT_FILL: 'No exit fill',
  NO_DIP: 'No dip · no entry',
  REFERENCE_BELOW_MIN_MARKET_CAP: 'Under min market cap · no entry',
  DIP_BELOW_MIN_MARKET_CAP: 'Dip under min market cap · no entry',
  MIGRATED_BEFORE_ENTRY: 'Migrated before entry',
  UNSUPPORTED_SUPPLY: 'Unsupported supply',
  NO_FILL_AFTER_TRIGGER: 'No fill after the dip',
  EXCEEDS_CURVE: 'Larger than the curve',
  INVALID_QUALITY: 'Invalid observation',
  ZERO_QUANTITY: 'Zero tokens',
  SIGNAL_BELOW_MIN_SIZE: 'Buy too small · not a signal',
  SIGNAL_SIZE_UNAVAILABLE: 'Buy size unknown',
  REFERENCE_UNAVAILABLE: 'Reference unknown',
  NO_CYCLE: 'No cycle for this buy',
  MARKET_COVERAGE_ENDED: 'Refused · recording too short',
  SELL_CHECK_NEEDS_TRADES: 'Refused · needs trader data',
  SIGNAL_SIZE_NEEDS_TRADES: 'Refused · needs trader data',
  DATASET_NOT_FOUND: 'Refused · dataset missing',
  DATASET_INVALID: 'Refused · dataset unreadable',
};

export function outcomeLabel(outcome: string): string {
  return OUTCOME_SHORT[outcome] ?? reasonText(outcome) ?? outcome;
}

export function outcomeExplanation(outcome: string): string | null {
  return reasonText(outcome);
}

/** Requests used against the cap, as a percent clamped to 0–100. */
export function capPercent(used: number, cap: number): number {
  if (cap <= 0) return 100;
  return Math.max(0, Math.min(100, (used / cap) * 100));
}

/** Highest worst-case P&L first, then by label; decimals compared exactly. */
export function compareWorstDesc(
  a: { worstCasePnl: string; label: string },
  b: { worstCasePnl: string; label: string },
): number {
  return compareDecimals(b.worstCasePnl, a.worstCasePnl) || a.label.localeCompare(b.label);
}

/** The labels of a known plan in order; empty for a plan this console does not describe. */
export function planLabels(planId: string): string[] {
  return Object.keys(PLAN_CHANGES[planId] ?? {});
}

export type StepMark = 'done' | 'current' | 'failed' | 'skipped' | 'pending';

/** Each pipeline step's mark for an item: done before its step, then its step as current, failed or skipped. */
export function stepMarks(item: Pick<AutomationItem, 'state' | 'step'>): StepMark[] {
  if (item.state === 'SIMULATED') return STEPS.map(() => 'done');
  const at = item.step ? STEPS.indexOf(item.step) : -1;
  const here: StepMark =
    item.state === 'FAILED' || item.state === 'ABANDONED'
      ? 'failed'
      : item.state === 'SKIPPED_NOT_FIRST_BUY'
        ? 'skipped'
        : item.state === 'DISCOVERED'
          ? 'pending'
          : 'current';
  return STEPS.map((_, i) => (i < at ? 'done' : i === at ? here : 'pending'));
}

/** Day counts the status grid offers. */
export const GRID_DAY_CHOICES = ['7', '14', '30'] as const;
export type GridDays = (typeof GRID_DAY_CHOICES)[number];

/** Request budget and range limits of a backfill (backend `AutomationProperties` and the plan's amendments). */
export const BACKFILL_LIMITS = {
  minDryRunBudget: 100,
  minCaptureBudget: 1222,
  maxBudget: 20_000,
  maxDays: 31,
  maxMints: 50,
  /** The capture filter; a lower minimum is refused, a higher one makes the backfill filtered. */
  captureMinSol: '1.5',
} as const;

/** The budget a new dry run starts from. */
export const DEFAULT_DRY_RUN_BUDGET = 500;

export const backfillStateText: Record<AutomationBackfill['state'], string> = {
  QUEUED: 'Queued',
  SCANNING: 'Scanning',
  CAPTURING: 'Capturing',
  DONE: 'Done',
  STOPPED_BUDGET: 'Stopped · budget',
  CANCELLED: 'Cancelled',
};

/** How far the newest-first scan has read back through the range, 0 to 1. */
export function scanFraction(
  b: Pick<AutomationBackfill, 'rangeStart' | 'rangeEnd' | 'scannedTo' | 'state'>,
): number {
  if (b.state === 'CAPTURING') return 1;
  const start = Date.parse(b.rangeStart);
  const end = Date.parse(b.rangeEnd);
  if (!b.scannedTo || end <= start) return b.state === 'DONE' ? 1 : 0;
  return Math.max(0, Math.min(1, (end - Date.parse(b.scannedTo)) / (end - start)));
}

/** Mints typed one per line (commas and spaces also split), de-duplicated in order, with the invalid ones apart. */
export function parseMints(
  text: string,
  isValid: (value: string) => boolean,
): { mints: string[]; invalid: string[] } {
  const tokens = text
    .split(/[\s,]+/)
    .map((t) => t.trim())
    .filter(Boolean);
  const mints: string[] = [];
  const invalid: string[] = [];
  for (const token of tokens) {
    if (!isValid(token)) invalid.push(token);
    else if (!mints.includes(token)) mints.push(token);
  }
  return { mints, invalid };
}

/** A token list, or a minimum above the capture filter, keeps a backfill out of coverage and day verdicts. */
export function isFilteredBackfill(minSignalSol: string, mints: string[]): boolean {
  const min = minSignalSol.trim();
  return (
    mints.length > 0 ||
    (isDecimalString(min) && compareDecimals(min, BACKFILL_LIMITS.captureMinSol) > 0)
  );
}

/** The budget a capture of a dry run's range starts from: its estimate, never below the capture minimum. */
export function captureBudgetFor(estimate: number | null): number {
  return Math.min(
    BACKFILL_LIMITS.maxBudget,
    Math.max(BACKFILL_LIMITS.minCaptureBudget, estimate ?? 0),
  );
}

/** "6 → 9 Oct", the trading days a backfill asked for. */
export function backfillDays(b: Pick<AutomationBackfill, 'fromDay' | 'toDay'>): string {
  const from = dayInfo(b.fromDay);
  const to = dayInfo(b.toDay);
  return b.fromDay === b.toDay ? to.short : `${from.short} → ${to.short}`;
}

/** "Dry run", "Capture" or "Capture · filtered (2 tokens)". */
export function backfillKind(b: Pick<AutomationBackfill, 'dryRun' | 'filtered' | 'mints'>): string {
  if (b.dryRun) return 'Dry run';
  if (!b.filtered) return 'Capture';
  return b.mints.length > 0
    ? `Capture · filtered (${b.mints.length} ${b.mints.length === 1 ? 'token' : 'tokens'})`
    : 'Capture · filtered (higher minimum)';
}

/** Plain explanations of the backend's backfill and run-now refusals (409), by code. */
export const REFUSAL_TEXT: Record<string, string> = {
  BACKFILL_OVERLAPS_COVERAGE:
    'Live discovery already covers these days. A capture backfill fills older days only: end it on or before the day live coverage starts.',
  CAPTURE_DISABLED:
    'Capture is off on the backend (trenching.automation.capture-enabled=false), so only a dry run can start.',
  AUTOMATION_DISABLED: 'Automation is off on the backend (trenching.automation.enabled=false).',
};

/** True for a refusal {@link REFUSAL_TEXT} or the running-backfill message explains. */
export function isBackfillRefusal(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' && (code === 'BACKFILL_ACTIVE' || code in REFUSAL_TEXT);
}

const DATE_KEY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** True for a real calendar date written `YYYY-MM-DD`: `2026-02-31` and `2026-13-01` are rejected. */
export function isRealDate(value: string | null | undefined): value is string {
  const match = value ? DATE_KEY.exec(value) : null;
  if (!match) return false;
  const [, year, month, day] = match.map(Number) as [number, number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

/** What is wrong with a date typed or linked, or null when it is a real `YYYY-MM-DD` date. */
export function dateProblem(value: string | null | undefined): string | null {
  if (!value) return 'Enter a date.';
  if (!DATE_KEY.test(value)) return `${value} is not a date written YYYY-MM-DD.`;
  return isRealDate(value) ? null : `${value} is not a real date.`;
}

/** A 1-based page number from the URL; anything that is not a whole number of at least 1 becomes 1. */
export function parsePage(value: string | null | undefined): number {
  if (!value || !/^\d+$/.test(value)) return 1;
  const page = Number(value);
  return Number.isSafeInteger(page) && page >= 1 ? page : 1;
}

const EVALUATION_STATUSES: readonly EvaluationStatus[] = [
  'SUCCESS',
  'NO_SUCCESS',
  'NO_RESULT',
  'PENDING',
];

/** A verdict filter from the URL, or null when it is missing or not a known status. */
export function parseEvaluationStatus(value: string | null | undefined): EvaluationStatus | null {
  return (EVALUATION_STATUSES as readonly string[]).includes(value ?? '')
    ? (value as EvaluationStatus)
    : null;
}

/** A backfill form pre-filled from a link (a finished dry run's "Capture this range"). */
export interface BackfillPrefill {
  fromDay: string | null;
  toDay: string | null;
  /** Only `dryRun=false` turns the dry run off; anything else keeps the safe default. */
  dryRun: boolean;
  budget: string | null;
  minSignalSol: string | null;
  /** URL keys that were present but not valid, so the form ignored them. */
  ignored: string[];
}

/**
 * Reads the backfill pre-fill keys (`from`, `to`, `dryRun`, `budget`, `min`) and keeps only valid values: real dates,
 * a whole-number budget within the limits of the chosen kind, and a plain SOL decimal at least the capture filter.
 */
export function parseBackfillPrefill(params: URLSearchParams): BackfillPrefill {
  const ignored: string[] = [];
  const keep = <T>(
    key: string,
    valid: (value: string) => boolean,
    map: (value: string) => T,
  ): T | null => {
    const raw = params.get(key);
    if (raw == null) return null;
    if (valid(raw)) return map(raw);
    ignored.push(key);
    return null;
  };
  const dryRunRaw = params.get('dryRun');
  if (dryRunRaw != null && dryRunRaw !== 'true' && dryRunRaw !== 'false') ignored.push('dryRun');
  const dryRun = dryRunRaw !== 'false';
  const min = dryRun ? BACKFILL_LIMITS.minDryRunBudget : BACKFILL_LIMITS.minCaptureBudget;
  return {
    fromDay: keep('from', isRealDate, (v) => v),
    toDay: keep('to', isRealDate, (v) => v),
    dryRun,
    budget: keep(
      'budget',
      (v) => /^\d+$/.test(v) && Number(v) >= min && Number(v) <= BACKFILL_LIMITS.maxBudget,
      (v) => String(Number(v)),
    ),
    minSignalSol: keep(
      'min',
      (v) =>
        isDecimalString(v) &&
        !v.startsWith('-') &&
        compareDecimals(v, BACKFILL_LIMITS.captureMinSol) >= 0,
      (v) => v,
    ),
    ignored,
  };
}

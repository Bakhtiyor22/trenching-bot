import { STEPS } from '../../api/automation';
import type {
  AutomationEvaluation,
  AutomationItem,
  AutomationItemState,
  AutomationSession,
  AutomationStatus,
  EvaluationStatus,
} from '../../api/automation';
import { compareDecimals, decimalSign, toChartNumber } from '../../lib/decimal';
import { reasonText } from '../captures/captureReasons';

/**
 * Pure helpers of the Automation pages: the automation's own time zone, nights, buy-state buckets, scoreboard bar
 * geometry, the status-by-night grid and plain-language outcome labels.
 */

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;
/** A buy before noon belongs to the previous evening's night (Decu trades about 19:00–03:00). */
const NIGHT_ROLLOVER_HOUR = 12;
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

export interface Night {
  /** `YYYY-MM-DD` of the evening the night started on. */
  key: string;
  /** "Mon 2 Nov". */
  label: string;
  /** "2 Nov", for narrow column heads. */
  short: string;
}

/** The night a buy belongs to: the evening date in the zone, where a buy before noon counts for the evening before. */
export function nightOf(iso: string, zone: string): Night {
  const p = zoneParts(iso, zone);
  const date = new Date(
    Date.UTC(p.year, p.month, p.day) - (p.hour < NIGHT_ROLLOVER_HOUR ? DAY_MS : 0),
  );
  const day = date.getUTCDate();
  const month = MONTHS[date.getUTCMonth()];
  return {
    key: `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(day)}`,
    label: `${WEEKDAYS[date.getUTCDay()]} ${day} ${month}`,
    short: `${day} ${month}`,
  };
}

/** "Night of Mon 2 Nov". */
export function nightTitle(session: Pick<AutomationSession, 'firstBuyAt'>, zone: string): string {
  return `Night of ${nightOf(session.firstBuyAt, zone).label}`;
}

/** The UTC instant at which `YYYY-MM-DD` starts in the zone's clock at the night rollover hour, for date filters. */
export function nightStart(key: string, zone: string): string {
  const [year, month, day] = key.split('-').map(Number) as [number, number, number];
  const offset = offsetMinutes(zone) ?? 0;
  return new Date(
    Date.UTC(year, month - 1, day, NIGHT_ROLLOVER_HOUR) - offset * 60_000,
  ).toISOString();
}

/** The night key `days` nights before the night of `nowIso`. */
export function nightKeyBefore(nowIso: string, zone: string, days: number): string {
  const key = nightOf(nowIso, zone).key;
  const [year, month, day] = key.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day) - days * DAY_MS);
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
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
};

export const statusGlyph: Record<EvaluationStatus, string> = {
  SUCCESS: '✓',
  NO_SUCCESS: '✕',
  NO_RESULT: '–',
};

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

export interface GridColumn {
  session: AutomationSession;
  night: Night;
}

export interface GridRow {
  label: string;
  cells: Array<AutomationEvaluation | null>;
}

/**
 * The status-by-night matrix: one column per night (oldest first), one row per variation (label order), each cell that
 * night's evaluation of the variation, or null when the night is not scored for it (open, or a window still filling).
 */
export function statusGrid(
  sessions: AutomationSession[],
  evaluations: AutomationEvaluation[],
  zone: string,
  labels?: string[],
): { columns: GridColumn[]; rows: GridRow[] } {
  const columns = [...sessions]
    .sort((a, b) => Date.parse(a.firstBuyAt) - Date.parse(b.firstBuyAt))
    .map((session) => ({ session, night: nightOf(session.firstBuyAt, zone) }));
  const bySession = new Map<string, Map<string, AutomationEvaluation>>();
  for (const evaluation of evaluations) {
    let row = bySession.get(evaluation.sessionId);
    if (!row) bySession.set(evaluation.sessionId, (row = new Map()));
    row.set(evaluation.label, evaluation);
  }
  const allLabels = labels ?? [...new Set(evaluations.map((e) => e.label))].sort();
  return {
    columns,
    rows: allLabels.map((label) => ({
      label,
      cells: columns.map((c) => bySession.get(c.session.id)?.get(label) ?? null),
    })),
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

/** Night counts the status grid offers. */
export const GRID_NIGHT_CHOICES = ['7', '14', '30'] as const;
export type GridNights = (typeof GRID_NIGHT_CHOICES)[number];

import { ApiError, NETWORK_ERROR } from '../../api/client';
import { STEPS } from '../../api/automation';
import type {
  AutomationBackfill,
  AutomationDaySummary,
  AutomationEvaluation,
  AutomationItem,
  AutomationItemState,
  AutomationSession,
  AutomationStatus,
  AutomationVariation,
  AutomationVariationList,
  EvaluationStatus,
  VariationConfigRequest,
} from '../../api/automation';
import type { Config, ConfigLimits } from '../../api/types';
import {
  compareDecimals,
  decimalSign,
  formatDecimal,
  isDecimalString,
  MINUS,
  toChartNumber,
  truncateDecimal,
} from '../../lib/decimal';
import { humanizeCode, mintLabel } from '../../lib/format';
import { formatPercent } from '../../lib/percent';
import { reasonText } from '../captures/captureReasons';
import {
  changedSettings,
  formatSettingValue,
  PRICE_MEASURE_VALUES,
  SETTING_LABELS,
  type SettingField,
} from '../runs/configLabels';
import {
  formValuesFrom,
  secondsFieldOf,
  secondsText,
  toRunStartRequest,
  validateRunForm,
  type RunFormValues,
} from '../runs/runForm';

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

/** "2 Nov" in the zone. */
export function zoneDate(iso: string | null | undefined, zone: string): string {
  if (!iso) return '—';
  const p = zoneParts(iso, zone);
  return `${p.day} ${MONTHS[p.month]}`;
}

/** "2 Nov, 21:31" in the zone. */
export function zoneDateTime(iso: string | null | undefined, zone: string): string {
  if (!iso) return '—';
  const p = zoneParts(iso, zone);
  return `${p.day} ${MONTHS[p.month]}, ${pad(p.hour)}:${pad(p.minute)}`;
}

/**
 * When a waiting buy tries again, on the automation clock: "next try 10:27" today, "next try 3 Nov, 10:27" on another
 * date, and "next try overdue since 1 Nov, 10:27" once the time has passed (the date only when it is not today).
 */
export function nextTryText(iso: string, zone: string, now: number = Date.now()): string {
  const today = zoneDate(new Date(now).toISOString(), zone);
  const when = zoneDate(iso, zone) === today ? zoneTime(iso, zone) : zoneDateTime(iso, zone);
  return Date.parse(iso) < now ? `next try overdue since ${when}` : `next try ${when}`;
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
  const cut = truncateDecimal(value, places);
  const sign = decimalSign(cut);
  const unsigned = cut.replace(/^-/, '');
  return `${sign > 0 ? '+' : sign < 0 ? MINUS : ''}${unsigned}`;
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

/** Why a pending day has no verdict yet, checked in this order. */
type PendingReason = 'today' | 'uncovered' | 'capturing' | 'window';
const PENDING_REASON_TEXT: Record<PendingReason, string> = {
  today: 'today is still running',
  uncovered: 'not covered by discovery',
  capturing: 'buys still capturing',
  window: 'waiting for earlier days of their 7-day window',
};

function pendingReason(day: AutomationDaySummary, today: string): PendingReason {
  if (day.day >= today) return 'today';
  if (!day.covered) return 'uncovered';
  if (!day.complete) return 'capturing';
  return 'window';
}

/**
 * When every day listed is pending, how many and why ("12 days pending: buys still capturing"), so a verdict filter that
 * matches nothing says what it waits for instead of a bare "no days match". Null while any day has a verdict.
 */
export function allPendingText(
  days: AutomationDaySummary[],
  today: string,
  captureEnabled: boolean,
): string | null {
  if (days.length === 0 || !days.every((d) => d.bestStatus === 'PENDING')) return null;
  const head = `${days.length} ${days.length === 1 ? 'day' : 'days'} pending`;
  if (!captureEnabled) return `${head}: discovery only, so no buy is captured or replayed`;
  const counts = new Map<PendingReason, number>();
  for (const day of days) {
    const reason = pendingReason(day, today);
    counts.set(reason, (counts.get(reason) ?? 0) + 1);
  }
  const reasons = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  if (reasons.length === 1) return `${head}: ${PENDING_REASON_TEXT[reasons[0]![0]]}`;
  return `${head}: ${reasons
    .map(([reason, count]) => `${PENDING_REASON_TEXT[reason]} (${count})`)
    .join(', ')}`;
}

export type AutomationMode = 'OFF' | 'DISCOVERY_ONLY' | 'CAPTURING';

export function automationMode(
  status: Pick<AutomationStatus, 'enabled' | 'captureEnabled'>,
): AutomationMode {
  if (!status.enabled) return 'OFF';
  return status.captureEnabled ? 'CAPTURING' : 'DISCOVERY_ONLY';
}

/** The setting a configuration property of the API is edited and shown as (`takeProfitBps` → `takeProfitPercent`). */
export function settingOfProperty(property: string): SettingField | null {
  const field = secondsFieldOf(property) ?? property.replace(/Bps$/, 'Percent');
  return field in SETTING_LABELS ? (field as SettingField) : null;
}

/** "5 min", "60 s", "0.5 s"; whole minutes from two minutes up, so a dip window of 60 s stays in seconds. */
export function clockText(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return 'off';
  return ms >= 2 * 60_000 && ms % 60_000 === 0 ? `${ms / 60_000} min` : `${secondsText(ms)} s`;
}

const plural = (n: number, one: string, many: string) =>
  `${formatDecimal(String(n))} ${n === 1 ? one : many}`;

/** One setting of `config` in a few words, as variation names read: "take profit 80%", "hold 5 min". */
export function settingShortText(field: SettingField, config: Config): string {
  const values = formValuesFrom(config, null);
  switch (field) {
    case 'dipPercent':
      return `dip ${formatPercent(values.dipPercent)}`;
    case 'takeProfitPercent':
      return `take profit ${formatPercent(values.takeProfitPercent)}`;
    case 'stopLossPercent':
      return `stop loss ${formatPercent(values.stopLossPercent)}`;
    case 'dipWindowSeconds':
      return `dip window ${clockText(config.dipWindowMs)}`;
    case 'maxHoldSeconds':
      return `hold ${clockText(config.maxHoldMs)}`;
    case 'hardExitStartSeconds':
      return `exit ${clockText(config.hardExitStartMs)}`;
    case 'minSignalSol':
      return values.minSignalSol ? `min signal ${values.minSignalSol} SOL` : 'no min signal';
    case 'minMarketCapUsd':
      return `min market cap $${formatDecimal(values.minMarketCapUsd)}`;
    case 'notional':
      return `size ${values.notional} SOL`;
    case 'transactionCostSol':
      return `cost ${values.transactionCostSol} SOL per transaction`;
    case 'horizonSlots':
      return `horizon ${plural(config.horizonSlots, 'slot', 'slots')}`;
    case 'latencySlots':
      return `latency ${plural(config.latencySlots, 'slot', 'slots')}`;
    case 'maxAgeSlots':
      return `max price age ${plural(config.maxAgeSlots, 'slot', 'slots')}`;
    case 'priceMeasure':
      return PRICE_MEASURE_VALUES[config.priceMeasure].toLowerCase();
    case 'followWalletSells':
      return config.followWalletSells ? "follows Decu's sells" : "ignores Decu's sells";
    case 'quoteMint':
      return `quote ${mintLabel(config.quoteMint)}`;
  }
}

/** The settings named by configuration properties, each once, in the order given (bps and percent twins merge). */
export function settingsOfProperties(properties: readonly string[]): SettingField[] {
  const fields: SettingField[] = [];
  for (const property of properties) {
    const field = settingOfProperty(property);
    if (field && !fields.includes(field)) fields.push(field);
  }
  return fields;
}

/** A property no setting shows (the strategy name, say) in words: "strategy version 2". */
function propertyShortText(property: string, config: Config): string {
  const value = (config as unknown as Record<string, unknown>)[property];
  return `${property.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()} ${String(value ?? '—')}`;
}

/** Some properties of `config` in a few words each, joined: "take profit 80%, stop loss 50%". */
export function propertiesText(properties: readonly string[], config: Config): string {
  const fields = settingsOfProperties(properties);
  const unknown = properties.filter((p) => settingOfProperty(p) === null);
  return [
    ...fields.map((field) => settingShortText(field, config)),
    ...unknown.map((p) => propertyShortText(p, config)),
  ].join(', ');
}

/** What a variation changes from the agreed strategy, in a few words, or "agreed (default)" when nothing. */
export function variationText(
  variation: Pick<AutomationVariation, 'differsFromAgreed' | 'config'>,
): string {
  return variation.differsFromAgreed.length === 0
    ? 'agreed (default)'
    : propertiesText(variation.differsFromAgreed, variation.config);
}

/** The plan's variations as the pages need them: names, order, the baseline and lookups. */
export interface VariationBook {
  planId: string | null;
  /** By sequence, active and archived. */
  variations: AutomationVariation[];
  /**
   * The variation every other one is compared with: the active one that differs from nothing agreed. Null while the
   * variations are not loaded, or when none is agreed.
   */
  baseline: string | null;
  find(label: string): AutomationVariation | undefined;
  /** The variation's short text, or null for a label the book does not hold. */
  describe(label: string): string | null;
  /** `labels` in sequence order; labels the book does not hold go last, sorted. */
  order(labels: Iterable<string>): string[];
}

export function variationBook(list: AutomationVariationList | null | undefined): VariationBook {
  const variations = list?.items ?? [];
  const byLabel = new Map(variations.map((v) => [v.label, v]));
  const rank = new Map(variations.map((v, i) => [v.label, i]));
  const baseline =
    variations.find((v) => v.state === 'ACTIVE' && v.differsFromAgreed.length === 0)?.label ?? null;
  return {
    planId: list?.planId ?? null,
    variations,
    baseline,
    find: (label) => byLabel.get(label),
    describe: (label) => {
      const variation = byLabel.get(label);
      return variation ? variationText(variation) : null;
    },
    order: (labels) =>
      [...new Set(labels)].sort(
        (a, b) =>
          (rank.get(a) ?? Number.MAX_SAFE_INTEGER) - (rank.get(b) ?? Number.MAX_SAFE_INTEGER) ||
          a.localeCompare(b),
      ),
  };
}

/**
 * The label the next edit would create: "V" and one more than the highest number, at least two digits. Only a
 * forecast for the form's text; another edit saved first takes it, and the backend names the label it created.
 */
export function nextVariationLabel(book: VariationBook): string | null {
  const numbers = book.variations
    .map((v) => /^V(\d+)$/.exec(v.label)?.[1])
    .filter((n): n is string => n != null)
    .map(Number);
  if (numbers.length === 0) return null;
  return `V${String(Math.max(...numbers) + 1).padStart(2, '0')}`;
}

/** "V04 · take profit 80%", or the bare label when its text is unknown. */
export function variationName(book: VariationBook, label: string): string {
  const text = book.describe(label);
  return text ? `${label} · ${text}` : label;
}

/** One setting changed in the edit form: its label and both values in words ("Take profit", "55%", "80%"). */
export interface SettingChange {
  field: SettingField;
  label: string;
  from: string;
  to: string;
}

/** Each setting `values` changes from `config`, in the form's order of settings. */
export function settingChanges(values: RunFormValues, config: Config): SettingChange[] {
  const before = formValuesFrom(config, values.datasetId);
  return changedSettings(values, config).map((field) => ({
    field,
    label: SETTING_LABELS[field],
    from: formatSettingValue(field, before[field]),
    to: formatSettingValue(field, values[field]),
  }));
}

/**
 * The edit form's own checks: the run form's limits, without a dataset, and a minimum signal size every variation
 * needs, at or above the capture filter (automation never captures a smaller buy, so a lower minimum could not apply).
 */
export function validateVariationForm(
  values: RunFormValues,
  limits: ConfigLimits,
  /** The capture filter from `/status` (`captureMinSignalSol`). */
  captureMinSol: string,
): Record<string, string> {
  const errors = validateRunForm(values, limits);
  delete errors.datasetId;
  const minSignal = values.minSignalSol.trim();
  if (minSignal === '')
    errors.minSignalSol = `Required for a variation: at least the capture filter, ${captureMinSol} SOL`;
  else if (
    !errors.minSignalSol &&
    isDecimalString(minSignal) &&
    compareDecimals(minSignal, captureMinSol) < 0
  )
    errors.minSignalSol = `Must be at least the capture filter, ${captureMinSol} SOL`;
  return errors;
}

/** The configuration an edit posts: every field, as a run start sends them. */
export function variationConfigRequest(values: RunFormValues): VariationConfigRequest {
  return toRunStartRequest(values).config!;
}

/**
 * A refused edit's field reasons keyed by the form's own names: a `config.` prefix is dropped, a level in basis points
 * goes on its percent input (the clocks in milliseconds are placed by `placeFieldErrors`).
 */
export function editFieldReasons(fields: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, reason] of Object.entries(fields)) {
    const field = key
      .replace(/^config\./, '')
      .replace(/^(dip|takeProfit|stopLoss)Bps$/, '$1Percent');
    out[field] = out[field] ? `${out[field]}; ${reason}` : reason;
  }
  return out;
}

/** Why an edit was refused or failed, in plain words, for the variation `label`. */
export function variationEditErrorText(error: unknown, label: string): string {
  if (!(error instanceof ApiError))
    return error instanceof Error ? error.message : 'Unexpected error.';
  switch (error.code) {
    case 'VARIATION_ARCHIVED':
      return `${label} has been archived meanwhile, so it can't be edited any more. Its replacement, shown above once the page refreshes, can be.`;
    case 'VARIATION_UNCHANGED':
      return `These settings are exactly ${label}'s own. Change at least one setting to save a new variation.`;
    case 'VARIATION_DUPLICATE': {
      const other = error.fields.label;
      return `${other ? `Active variation ${other}` : 'Another active variation'} already has exactly these settings, so a copy would only replay the same strategy twice.`;
    }
    case 'VARIATION_BASELINE':
      return `${label} is the agreed baseline every other variation is compared with, so it can't be edited.`;
    case 'AUTOMATION_CONFLICT':
      return `Another edit of ${label} was saved at the same moment, so this one was not. Reload the variation and edit it again.`;
    case 'AUTOMATION_VARIATION_NOT_FOUND':
      return `The backend has no variation ${label}.`;
    case 'VARIATION_LIMIT_REACHED':
      return 'The plan has reached its limit of variations, so no new one can be saved. Nothing was saved.';
    case 'CONFIG_INVALID':
    case 'INVALID_PERCENT':
    case 'CONFLICTING_FIELDS':
      return 'The backend refused some settings. Nothing was saved.';
    case NETWORK_ERROR:
      return 'The backend could not be reached, so it is not known whether the edit was saved. Saving again sends the same request: it cannot create the variation twice.';
    default:
      return error.message;
  }
}

const EDIT_UNPLACED_SUBJECTS: Record<string, string> = {
  requestId: 'The request id',
  label: 'The variation',
};

/** A refused edit's reasons that no input shows ("The request id must not be blank."), one sentence each. */
export function unplacedReasonsText(unplaced: ReadonlyArray<{ field: string; reason: string }>) {
  return unplaced
    .filter(({ field }) => field !== 'label')
    .map(({ field, reason }) => `${EDIT_UNPLACED_SUBJECTS[field] ?? field} ${reason}.`)
    .join(' ');
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
  minCaptureBudget: 2422,
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

/** Why a failed buy that failed with `failureCode` cannot be retried, in plain words. */
const RETRY_NOT_ALLOWED_TEXT: Record<string, string> = {
  BACKFILL_BUDGET:
    'It stopped because its backfill ran out of request budget, so a retry cannot pick it up. Start a new backfill over this day to capture it.',
  MISSING_BLOCK_TIME:
    'Its transaction has no block time, so its market window cannot be placed. A retry would fail the same way.',
};

/**
 * A plain explanation of a retry refusal code: from an item's `retryRefusal` or a 409 answer. `failureCode` is the
 * buy's failure (or the answer's `fields.failureCode`), which says why `RETRY_NOT_ALLOWED` applies.
 */
export function retryRefusalText(code: string, failureCode?: string | null): string {
  switch (code) {
    case 'ITEM_NOT_FAILED':
      return 'Only a failed buy can be retried, and this one is not failed (any more).';
    case 'RETRY_NOT_ALLOWED':
      return (
        (failureCode ? RETRY_NOT_ALLOWED_TEXT[failureCode] : undefined) ??
        'This kind of failure cannot be retried.'
      );
    case 'BACKFILL_NOT_CAPTURING':
      return 'Its backfill is still scanning for buys. Retry it once the scan has finished.';
    case 'CAPTURE_DISABLED':
      return 'Capture is off on the backend (trenching.automation.capture-enabled=false). Turn it on to retry.';
    default:
      return `Not retried: ${humanizeCode(code)}.`;
  }
}

/** Why a retry request failed, in plain words: a refusal, an unknown buy, a bad request, or the backend's message. */
export function retryErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      if (error.code === 'ITEM_NOT_FAILED')
        return 'This buy is no longer failed, so it was not retried. It may already be retrying; the page now shows its current state.';
      return retryRefusalText(error.code, error.fields.failureCode);
    }
    if (error.status === 404 && error.code === 'AUTOMATION_ITEM_NOT_FOUND')
      return 'This buy no longer exists on the backend.';
    if (error.status === 400) return `The backend refused the retry request: ${error.message}`;
    return error.message;
  }
  return error instanceof Error ? error.message : 'Unexpected error.';
}

/** Why `item` cannot be retried now, in plain words, or null when it can. */
export function itemRefusalText(
  item: Pick<AutomationItem, 'retryRefusal' | 'failureCode'>,
): string | null {
  return item.retryRefusal ? retryRefusalText(item.retryRefusal, item.failureCode) : null;
}

/** The step a retry sends `item` back to: a capture deleted from the console starts over, a failed sweep is swept again. */
export function retryStepOf(
  item: Pick<AutomationItem, 'step' | 'failureCode'>,
): AutomationItem['step'] {
  if (item.failureCode === 'CAPTURE_NOT_FOUND') return 'CREATE_SESSION';
  if (item.failureCode === 'SWEEP_FAILED') return 'SWEEP';
  return item.step;
}

export const RETRY_SPEND_NOTE = 'It spends provider requests under the 24 h cap like any capture.';

/** "retried 2×", or null for a buy never retried. */
export function retriesText(retries: number): string | null {
  return retries > 0 ? `retried ${retries}×` : null;
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

/**
 * A trading-day range typed in a filter, both days included, as the instants the sweep-run API takes: `from` at the
 * start of its day (inclusive) and `to` at the start of the day after (exclusive). An empty side is open; a side that
 * is not a real date, or a start after the end, gets an error and is not sent.
 */
export interface DayFilter {
  fromInstant: string | null;
  toInstant: string | null;
  errors: { from?: string; to?: string };
}

export function dayFilter(
  from: string | null | undefined,
  to: string | null | undefined,
  zone: string,
  start = DEFAULT_DAY_START,
): DayFilter {
  const errors: DayFilter['errors'] = {};
  if (from) errors.from = dateProblem(from) ?? undefined;
  if (to) errors.to = dateProblem(to) ?? undefined;
  if (!errors.from) delete errors.from;
  if (!errors.to) delete errors.to;
  if (from && to && !errors.from && !errors.to && daysBetween(from, to) < 0)
    errors.to = 'The end day is before the start day.';
  const ok = Object.keys(errors).length === 0;
  return {
    fromInstant: ok && from ? dayStartInstant(from, zone, start) : null,
    toInstant: ok && to ? dayStartInstant(addDays(to, 1), zone, start) : null,
    errors,
  };
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

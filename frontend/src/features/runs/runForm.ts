import type { Config, ConfigLimits, PriceMeasure, Range, RunStartRequest } from '../../api/types';
import { isSolanaAddress } from '../../lib/base58';
import { SELL_CHECK_NEEDS_TRADES, SIGNAL_SIZE_NEEDS_TRADES } from '../../lib/codes';
import { recordsTrades, TRADES_SCHEMA_VERSION } from '../../lib/datasetSchema';
import { trimFractionZeros, validateDecimal } from '../../lib/decimal';
import { bpsToPercent } from '../../lib/percent';

/** SOL has 9 decimals: one lamport. */
const LAMPORT_SCALE = 9;

/** The editable integer `SimulationConfig` parameters of the dip-entry strategy. */
export const INTEGER_FIELDS = ['horizonSlots', 'latencySlots', 'maxAgeSlots'] as const;
export type IntegerField = (typeof INTEGER_FIELDS)[number];
export const PERCENT_FIELDS = ['dipPercent', 'takeProfitPercent', 'stopLossPercent'] as const;
export type PercentField = (typeof PERCENT_FIELDS)[number];
/** The optional block-time clocks, typed in seconds and sent in milliseconds; empty means unset (untimed). */
export const SECONDS_FIELDS = [
  'dipWindowSeconds',
  'maxHoldSeconds',
  'hardExitStartSeconds',
] as const;
export type SecondsField = (typeof SECONDS_FIELDS)[number];
/** The config fields, and API properties, that carry the clocks in milliseconds. */
export type MillisField = 'dipWindowMs' | 'maxHoldMs' | 'hardExitStartMs';
/** The config field in milliseconds behind each seconds field. */
export const MILLIS_FIELD: Record<SecondsField, MillisField> = {
  dipWindowSeconds: 'dipWindowMs',
  maxHoldSeconds: 'maxHoldMs',
  hardExitStartSeconds: 'hardExitStartMs',
};
/** The seconds field a config field in milliseconds is typed in (the inverse of `MILLIS_FIELD`); undefined for any other. */
export function secondsFieldOf(field: string): SecondsField | undefined {
  return SECONDS_FIELDS.find((seconds) => MILLIS_FIELD[seconds] === field);
}
/** The two entry and exit rules that are a choice, not a number. */
export type RuleField = 'priceMeasure' | 'followWalletSells';

export interface RunFormValues {
  datasetId: string | null;
  /** Kept as the typed text and sent as a string, never as a JS number. */
  notional: string;
  /** Kept as the typed text, like notional. */
  minMarketCapUsd: string;
  /** Kept as the typed text, like notional. */
  transactionCostSol: string;
  maxAgeSlots: number | string;
  dipPercent: string;
  takeProfitPercent: string;
  stopLossPercent: string;
  horizonSlots: number | string;
  latencySlots: number | string;
  quoteMint: string;
  /** Typed seconds with at most 3 decimals; empty means unset. */
  dipWindowSeconds: string;
  maxHoldSeconds: string;
  hardExitStartSeconds: string;
  priceMeasure: PriceMeasure;
  followWalletSells: boolean;
  /** SOL, typed text of whole lamports; empty means no minimum signal size. */
  minSignalSol: string;
}

export const FIELD_LABELS: Record<IntegerField | PercentField | SecondsField | RuleField, string> =
  {
    dipPercent: "Dip below Decu's first buy",
    takeProfitPercent: 'Take profit',
    stopLossPercent: 'Stop loss',
    dipWindowSeconds: 'Dip window',
    maxHoldSeconds: 'Maximum hold',
    hardExitStartSeconds: 'Hard exit start',
    horizonSlots: 'Observation window',
    latencySlots: 'Fill delay',
    maxAgeSlots: 'Max price age',
    priceMeasure: 'Price measure',
    followWalletSells: "Follow Decu's sells (no entry, and exit if holding)",
  };

/** How each price measure reads in the run form. */
export const PRICE_MEASURE_LABELS: Record<PriceMeasure, string> = {
  TRADE_AVERAGE: "Each trade's average price",
  POST_TRADE: 'Price after each trade (spec)',
};

export const FIELD_HINTS: Record<
  | IntegerField
  | PercentField
  | SecondsField
  | RuleField
  | 'notional'
  | 'minMarketCapUsd'
  | 'transactionCostSol'
  | 'quoteMint'
  | 'minSignalSol',
  string
> = {
  notional: 'Quote units spent per entry, as a plain decimal.',
  transactionCostSol:
    'SOL charged per transaction (network + priority fee or tip), on entry and on exit.',
  minMarketCapUsd:
    'Minimum market cap in US dollars at Decu’s buy (strictly above), from the dataset’s SOL/USDT rate.',
  dipPercent: 'Buy once a trade is this percent below the wallet’s first price.',
  takeProfitPercent: 'Sell once a trade is this percent above the entry price.',
  stopLossPercent: 'Sell once a trade is this percent below the entry price.',
  dipWindowSeconds:
    'Only a dip within this many seconds of Decu’s buy triggers an entry; empty for no limit.',
  maxHoldSeconds: 'Sell this many seconds after the entry fill at the latest.',
  hardExitStartSeconds:
    'Sell this many seconds after Decu’s buy at the latest; the earlier of the two clocks wins.',
  horizonSlots:
    'Slots after the wallet’s first buy that are read; 4,500 is about 16 minutes at today’s slot pace.',
  latencySlots: 'Slots between a trigger and the trade that fills it (1 = next slot).',
  maxAgeSlots: 'How old (by price slot) an observation may be when used.',
  quoteMint: 'Only swaps against this mint create signals. Wrapped SOL by default.',
  minSignalSol: `Decu's buy is a signal only when it pays more than this, curve payment plus protocol and creator fees; empty for no minimum. Needs a dataset of schema ${TRADES_SCHEMA_VERSION} or later.`,
  priceMeasure:
    'Which price the reference, the dip and take profit / stop loss compare. The exit levels still come from our own fill.',
  followWalletSells: `No entry once Decu sells the token before the dip, including a sell in the dip's own slot; sell everything if he sells while we hold. Needs a dataset of schema ${TRADES_SCHEMA_VERSION} or later.`,
};

/**
 * The rules that read the tracked wallet's trades from the market data, in the order the backend checks them
 * (`ReplayRunner.run`): the sell rule, then the minimum signal size. A dataset that records no trades refuses either.
 */
export const TRADE_RULES = ['followWalletSells', 'minSignalSol'] as const;
export type TradeRule = (typeof TRADE_RULES)[number];

/** The run-start refusal code of each trade rule on a dataset that records no trades. */
const TRADE_RULE_CODES: Record<TradeRule, string> = {
  followWalletSells: SELL_CHECK_NEEDS_TRADES,
  minSignalSol: SIGNAL_SIZE_NEEDS_TRADES,
};

/** The trade rules switched on in [values]: the sell rule ticked, a minimum signal size typed. */
export function activeTradeRules(values: RunFormValues): TradeRule[] {
  return TRADE_RULES.filter((rule) =>
    rule === 'followWalletSells' ? values.followWalletSells : values.minSignalSol.trim() !== '',
  );
}

/**
 * The trade rules on in [values] that a dataset of [schemaVersion] cannot run, so a start would be refused; none when
 * the dataset records trades or its schema is not known.
 */
export function tradeRuleConflicts(
  values: RunFormValues,
  schemaVersion: number | undefined,
): TradeRule[] {
  return schemaVersion === undefined || recordsTrades(schemaVersion)
    ? []
    : activeTradeRules(values);
}

/**
 * The trade rules to turn off after a refusal [code]. The backend names only the first rule it checks, but the
 * refusal proves the dataset records no trades, so every trade rule still on goes, or the named one when none is.
 * None for any other refusal.
 */
export function refusedTradeRules(values: RunFormValues, code: string | null): TradeRule[] {
  const named = TRADE_RULES.find((rule) => TRADE_RULE_CODES[rule] === code);
  if (!named) return [];
  const active = activeTradeRules(values);
  return active.length > 0 ? active : [named];
}

/** The form values that turn [rules] off: the sell rule unticked, the minimum signal size cleared. */
export function tradeRulesOff(
  rules: readonly TradeRule[],
): Partial<Pick<RunFormValues, TradeRule>> {
  return {
    ...(rules.includes('followWalletSells') ? { followWalletSells: false } : {}),
    ...(rules.includes('minSignalSol') ? { minSignalSol: '' } : {}),
  };
}

export function formValuesFrom(config: Config, datasetId: string | null): RunFormValues {
  return {
    datasetId,
    notional: trimFractionZeros(config.notional),
    minMarketCapUsd: trimFractionZeros(config.minMarketCapUsd),
    transactionCostSol: trimFractionZeros(config.transactionCostSol),
    maxAgeSlots: config.maxAgeSlots,
    dipPercent: trimFractionZeros(config.dipPercent ?? bpsToPercent(config.dipBps)),
    takeProfitPercent: trimFractionZeros(
      config.takeProfitPercent ?? bpsToPercent(config.takeProfitBps),
    ),
    stopLossPercent: trimFractionZeros(config.stopLossPercent ?? bpsToPercent(config.stopLossBps)),
    horizonSlots: config.horizonSlots,
    latencySlots: config.latencySlots,
    quoteMint: config.quoteMint,
    dipWindowSeconds: secondsText(config.dipWindowMs),
    maxHoldSeconds: secondsText(config.maxHoldMs),
    hardExitStartSeconds: secondsText(config.hardExitStartMs),
    priceMeasure: config.priceMeasure,
    followWalletSells: config.followWalletSells,
    minSignalSol: config.minSignalSol ? trimFractionZeros(config.minSignalSol) : '',
  };
}

/** Milliseconds as typed seconds without trailing zeros (30000 → "30", 500 → "0.5"); null → "". */
export function secondsText(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '';
  const whole = Math.trunc(ms / 1000);
  const fraction = String(ms % 1000)
    .padStart(3, '0')
    .replace(/0+$/, '');
  return fraction === '' ? String(whole) : `${whole}.${fraction}`;
}

const SECONDS_PATTERN = /^[0-9]+(\.[0-9]{1,3})?$/;

/** Typed seconds as whole milliseconds, exactly with string math ("1.234" → 1234); "" → null; invalid → undefined. */
export function secondsToMs(input: string): number | null | undefined {
  const value = input.trim();
  if (value === '') return null;
  if (!SECONDS_PATTERN.test(value)) return undefined;
  const [whole = '0', fraction = ''] = value.split('.');
  return Number(whole) * 1000 + Number(fraction.padEnd(3, '0'));
}

function secondsError(input: string, range: Range): string | null {
  const ms = secondsToMs(input);
  if (ms === null) return null;
  if (ms === undefined) return 'Use whole seconds or up to 3 decimals';
  if (ms < range.min || (range.max !== null && ms > range.max)) return secondsRangeError(range);
  return null;
}

/** A clock outside [range], which is in milliseconds, said in the seconds its input takes. */
function secondsRangeError(range: Range): string {
  const min = secondsText(range.min);
  return range.max === null
    ? `Must be at least ${min} s`
    : `Must be between ${min} and ${secondsText(range.max)} s`;
}

/** A clock set without [other], the clock it needs: "Set together with the maximum hold". */
function pairError(other: SecondsField): string {
  return `Set together with the ${FIELD_LABELS[other].toLowerCase()}`;
}

export function rangeText(range: Range): string {
  return range.max === null ? `≥ ${range.min}` : `${range.min} – ${range.max}`;
}

function integerError(value: number | string, range: Range): string | null {
  if (value === '' || value === null || value === undefined) return 'Required';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isInteger(n)) return 'Must be a whole number';
  if (n < range.min || (range.max !== null && n > range.max)) {
    return range.max === null
      ? `Must be at least ${range.min}`
      : `Must be between ${range.min} and ${range.max}`;
  }
  return null;
}

/** A plain non-negative decimal within [range]; compared exactly on its digits, never as a float. */
function marketCapError(input: string, range: Range): string | null {
  const value = input.trim();
  if (value === '') return 'Required';
  if (!/^[0-9]+(\.[0-9]+)?$/.test(value))
    return 'Use a plain decimal such as 10000 (no sign, exponent or separators)';
  const [integer = '0', fraction = ''] = value.split('.');
  const whole = BigInt(integer);
  const hasFraction = /[1-9]/.test(fraction);
  if (
    whole < BigInt(range.min) ||
    (range.max !== null &&
      (whole > BigInt(range.max) || (whole === BigInt(range.max) && hasFraction)))
  ) {
    return range.max === null
      ? `Must be at least ${range.min}`
      : `Must be between ${range.min} and ${range.max}`;
  }
  return null;
}

/** The smallest positive amount at [scale] decimals, e.g. "0.000000001" (one lamport) at 9. */
export function smallestUnit(scale: number): string {
  return scale === 0 ? '1' : `0.${'1'.padStart(scale, '0')}`;
}

/** Empty means no minimum; otherwise whole lamports within the backend's range (checked exactly, never as a float). */
function minSignalError(input: string, limits: ConfigLimits): string | null {
  const value = input.trim();
  if (value === '') return null;
  const scale = limits.minSignalSolScale ?? LAMPORT_SCALE;
  return validateDecimal(value, {
    maxFractionDigits: scale,
    maxIntegerDigits: 4,
    ...(limits.minSignalSol ?? { min: smallestUnit(scale), max: '1000' }),
  });
}

/** Client-side validation mirroring the backend limits; returns one message per invalid field. */
export function validateRunForm(
  values: RunFormValues,
  limits: ConfigLimits,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!values.datasetId) errors.datasetId = 'Choose a dataset';
  const maxIntegerDigits = limits.notionalPrecision - limits.notionalScale;
  const notional = validateDecimal(values.notional, {
    maxFractionDigits: limits.notionalScale,
    maxIntegerDigits,
    min: smallestUnit(limits.notionalScale),
  });
  if (notional) errors.notional = notional;
  const transactionCost = validateDecimal(values.transactionCostSol, {
    maxFractionDigits: limits.transactionCostScale,
    maxIntegerDigits,
    ...limits.transactionCostSol,
  });
  if (transactionCost) errors.transactionCostSol = transactionCost;
  const marketCap = marketCapError(values.minMarketCapUsd, limits.minMarketCapUsd);
  if (marketCap) errors.minMarketCapUsd = marketCap;
  for (const field of PERCENT_FIELDS) {
    const value = values[field];
    const range = limits[field] ?? { min: '0.01', max: '99.99' };
    const error = !/^[0-9]+(\.[0-9]{1,2})?$/.test(value)
      ? 'Use a percent with at most 2 decimals'
      : validateDecimal(value, { maxFractionDigits: 2, maxIntegerDigits: 3, ...range });
    if (error) errors[field] = error;
  }
  for (const field of INTEGER_FIELDS) {
    const error = integerError(values[field], limits[field]);
    if (error) errors[field] = error;
  }
  for (const field of SECONDS_FIELDS) {
    const error = secondsError(values[field], limits[MILLIS_FIELD[field]]);
    if (error) errors[field] = error;
  }
  const holdSet = values.maxHoldSeconds.trim() !== '';
  const hardSet = values.hardExitStartSeconds.trim() !== '';
  if (holdSet && !hardSet && !errors.maxHoldSeconds)
    errors.hardExitStartSeconds = pairError('maxHoldSeconds');
  if (hardSet && !holdSet && !errors.hardExitStartSeconds)
    errors.maxHoldSeconds = pairError('hardExitStartSeconds');
  if (!isSolanaAddress(values.quoteMint)) errors.quoteMint = 'Must be a 32-byte base58 address';
  const minSignal = minSignalError(values.minSignalSol, limits);
  if (minSignal) errors.minSignalSol = minSignal;
  return errors;
}

/**
 * The `POST /api/runs` body. Notional, minimum market cap and transaction cost stay strings; integers are JSON numbers;
 * the clocks are whole milliseconds, or null when empty; the price measure is a string and the sell rule a boolean.
 */
export function toRunStartRequest(values: RunFormValues): RunStartRequest {
  return {
    datasetId: values.datasetId ?? '',
    config: {
      notional: values.notional.trim(),
      minMarketCapUsd: values.minMarketCapUsd.trim(),
      transactionCostSol: values.transactionCostSol.trim(),
      maxAgeSlots: Number(values.maxAgeSlots),
      dipPercent: values.dipPercent,
      takeProfitPercent: values.takeProfitPercent,
      stopLossPercent: values.stopLossPercent,
      horizonSlots: Number(values.horizonSlots),
      latencySlots: Number(values.latencySlots),
      quoteMint: values.quoteMint.trim(),
      dipWindowMs: secondsToMs(values.dipWindowSeconds) ?? null,
      maxHoldMs: secondsToMs(values.maxHoldSeconds) ?? null,
      hardExitStartMs: secondsToMs(values.hardExitStartSeconds) ?? null,
      priceMeasure: values.priceMeasure,
      followWalletSells: values.followWalletSells,
      minSignalSol: values.minSignalSol.trim() === '' ? null : values.minSignalSol.trim(),
    },
  };
}

/** A refused run start's reason for one field, named by its API property (`presetId`, `dipWindowMs`, …). */
export interface FieldReason {
  field: string;
  reason: string;
}

/** A refused run start's field reasons, split into the ones the form's inputs show and the rest. */
export interface PlacedFieldErrors {
  /** For `form.setErrors`: each reason under the form field whose input shows it. */
  inputs: Partial<Record<keyof RunFormValues, string>>;
  /** The reasons no input shows, in the backend's order. */
  unplaced: FieldReason[];
}

/** `ConsoleConfigValidator`'s reasons for a clock, which it counts in milliseconds. */
const MILLIS_RANGE_REASON = /^must be an integer between (\d+) and (\d+)$/;
const MILLIS_MINIMUM_REASON = /^must be an integer of at least (\d+)$/;
const PAIR_REASON = /^must be set together with (\w+)$/;

/**
 * A backend [reason] for a clock, said in the seconds its input takes and in the form's own words. A reason of any
 * other shape keeps its words, and says its numbers are milliseconds when it has any.
 */
function clockReason(reason: string): string {
  const range = MILLIS_RANGE_REASON.exec(reason);
  if (range) return secondsRangeError({ min: Number(range[1]), max: Number(range[2]) });
  const minimum = MILLIS_MINIMUM_REASON.exec(reason);
  if (minimum) return secondsRangeError({ min: Number(minimum[1]), max: null });
  const other = secondsFieldOf(PAIR_REASON.exec(reason)?.[1] ?? '');
  if (other) return pairError(other);
  return /\d/.test(reason) ? `${reason} (in milliseconds)` : reason;
}

/**
 * Puts a refused run start's [fields], keyed by API property, on the [inputFields] the page shows: a clock in
 * milliseconds on its seconds input, any other field on the input of the same name. The rest, such as `presetId`,
 * which no input shows, are set aside for the refusal text.
 */
export function placeFieldErrors(
  fields: Record<string, string>,
  inputFields: readonly (keyof RunFormValues)[],
): PlacedFieldErrors {
  const placed: PlacedFieldErrors = { inputs: {}, unplaced: [] };
  for (const [field, reason] of Object.entries(fields)) {
    const seconds = secondsFieldOf(field);
    const input = inputFields.find((name) => name === (seconds ?? field));
    if (input) placed.inputs[input] = seconds ? clockReason(reason) : reason;
    else placed.unplaced.push({ field, reason });
  }
  return placed;
}

/** How a sentence about a field no input shows names it; any other field keeps its API name. */
const UNPLACED_SUBJECTS: Record<string, string> = { presetId: 'The agreed strategy' };

/** [text] ending as a sentence; backend messages have no closing period. */
function sentence(text: string): string {
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

/**
 * A refused run start in words: the backend's [message], then the reason of each field no input shows, unless the
 * message already names that field and reason, then a pointer to the inputs when [highlighted] of them still show one.
 */
export function refusalText(
  message: string,
  unplaced: readonly FieldReason[],
  highlighted: number,
): string {
  const more = unplaced
    .filter(({ field, reason }) => !(message.includes(field) && message.includes(reason)))
    .map(({ field, reason }) => `${UNPLACED_SUBJECTS[field] ?? field} ${reason}`);
  if (highlighted > 0) more.push(`See the highlighted ${highlighted === 1 ? 'field' : 'fields'}`);
  return more.length === 0 ? message : [message, ...more].map(sentence).join(' ');
}

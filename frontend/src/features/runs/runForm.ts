import type { Config, ConfigLimits, Range, RunStartRequest } from '../../api/types';
import { isSolanaAddress } from '../../lib/base58';
import { trimFractionZeros, validateDecimal } from '../../lib/decimal';
import { bpsToPercent } from '../../lib/percent';

/** The editable integer `SimulationConfig` parameters of the dip-entry strategy. */
export const INTEGER_FIELDS = ['horizonSlots', 'latencySlots', 'maxAgeSlots'] as const;
export type IntegerField = (typeof INTEGER_FIELDS)[number];
export const PERCENT_FIELDS = ['dipPercent', 'takeProfitPercent', 'stopLossPercent'] as const;
export type PercentField = (typeof PERCENT_FIELDS)[number];

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
}

export const FIELD_LABELS: Record<IntegerField | PercentField, string> = {
  dipPercent: "Dip below Decu's first buy",
  takeProfitPercent: 'Take profit',
  stopLossPercent: 'Stop loss',
  horizonSlots: 'Observation window',
  latencySlots: 'Fill delay',
  maxAgeSlots: 'Max price age',
};

export const FIELD_HINTS: Record<
  IntegerField | PercentField | 'notional' | 'minMarketCapUsd' | 'transactionCostSol' | 'quoteMint',
  string
> = {
  notional: 'Quote units spent per entry, as a plain decimal.',
  transactionCostSol:
    'SOL charged per transaction (network + priority fee or tip), on entry and on exit.',
  minMarketCapUsd: 'Minimum market cap in US dollars at the dip, from the dataset’s SOL/USDT rate.',
  dipPercent: 'Buy once a trade is this percent below the wallet’s first price.',
  takeProfitPercent: 'Sell once a trade is this percent above the entry price.',
  stopLossPercent: 'Sell once a trade is this percent below the entry price.',
  horizonSlots: 'Slots after the wallet’s first buy that are watched; 4,500 is about 30 minutes.',
  latencySlots: 'Slots between a trigger and the trade that fills it (1 = next slot).',
  maxAgeSlots: 'How old (by price slot) an observation may be when used.',
  quoteMint: 'Only swaps against this mint create signals. Wrapped SOL by default.',
};

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
  };
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
  if (!isSolanaAddress(values.quoteMint)) errors.quoteMint = 'Must be a 32-byte base58 address';
  return errors;
}

/** The `POST /api/runs` body. Notional, minimum market cap and transaction cost stay strings; integers are JSON numbers. */
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
    },
  };
}

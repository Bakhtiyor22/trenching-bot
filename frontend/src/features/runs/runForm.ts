import type { Config, ConfigLimits, Range, RunStartRequest } from '../../api/types';
import { isSolanaAddress } from '../../lib/base58';
import { validateDecimal } from '../../lib/decimal';

/** The editable integer `SimulationConfig` parameters of the dip-entry strategy. */
export const INTEGER_FIELDS = [
  'dipBps',
  'takeProfitBps',
  'stopLossBps',
  'horizonSlots',
  'latencySlots',
  'maxAgeSlots',
] as const;
export type IntegerField = (typeof INTEGER_FIELDS)[number];

export interface RunFormValues {
  datasetId: string | null;
  /** Kept as the typed text and sent as a string, never as a JS number. */
  notional: string;
  /** Kept as the typed text, like notional. */
  minMarketCapUsd: string;
  /** Kept as the typed text, like notional. */
  transactionCostSol: string;
  maxAgeSlots: number | string;
  dipBps: number | string;
  takeProfitBps: number | string;
  stopLossBps: number | string;
  horizonSlots: number | string;
  latencySlots: number | string;
  quoteMint: string;
}

export const FIELD_LABELS: Record<IntegerField, string> = {
  dipBps: 'Dip below first buy (bps)',
  takeProfitBps: 'Take profit (bps)',
  stopLossBps: 'Stop loss (bps)',
  horizonSlots: 'Horizon (slots)',
  latencySlots: 'Latency (slots)',
  maxAgeSlots: 'Max observation age (slots)',
};

export const FIELD_HINTS: Record<IntegerField | 'notional' | 'minMarketCapUsd' | 'transactionCostSol' | 'quoteMint', string> = {
  notional: 'Quote units spent per entry, as a plain decimal.',
  transactionCostSol: 'SOL charged per transaction (network + priority fee or tip), on entry and on exit.',
  minMarketCapUsd: 'Minimum market cap in US dollars at the dip, from the dataset’s SOL/USDT rate.',
  dipBps: 'Buy once a trade is this far below the wallet’s first price (1500 = 15%).',
  takeProfitBps: 'Sell once a trade is this far above the entry price (3000 = 30%).',
  stopLossBps: 'Sell once a trade is this far below the entry price (1500 = 15%).',
  horizonSlots: 'Slots after the wallet’s first buy that are watched; 4,500 is about 30 minutes.',
  latencySlots: 'Slots between a trigger and the trade that fills it (1 = next slot).',
  maxAgeSlots: 'How old (by price slot) an observation may be when used.',
  quoteMint: 'Only swaps against this mint create signals. Wrapped SOL by default.',
};

export function formValuesFrom(config: Config, datasetId: string | null): RunFormValues {
  return {
    datasetId,
    notional: config.notional,
    minMarketCapUsd: config.minMarketCapUsd,
    transactionCostSol: config.transactionCostSol,
    maxAgeSlots: config.maxAgeSlots,
    dipBps: config.dipBps,
    takeProfitBps: config.takeProfitBps,
    stopLossBps: config.stopLossBps,
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
  if (!/^[0-9]+(\.[0-9]+)?$/.test(value)) return 'Use a plain decimal such as 10000 (no sign, exponent or separators)';
  const [integer = '0', fraction = ''] = value.split('.');
  const whole = BigInt(integer);
  const hasFraction = /[1-9]/.test(fraction);
  if (whole < BigInt(range.min) || (range.max !== null && (whole > BigInt(range.max) || (whole === BigInt(range.max) && hasFraction)))) {
    return range.max === null ? `Must be at least ${range.min}` : `Must be between ${range.min} and ${range.max}`;
  }
  return null;
}

/** The smallest positive amount at [scale] decimals, e.g. "0.000000001" (one lamport) at 9. */
export function smallestUnit(scale: number): string {
  return scale === 0 ? '1' : `0.${'1'.padStart(scale, '0')}`;
}

/** Client-side validation mirroring the backend limits; returns one message per invalid field. */
export function validateRunForm(values: RunFormValues, limits: ConfigLimits): Record<string, string> {
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
      dipBps: Number(values.dipBps),
      takeProfitBps: Number(values.takeProfitBps),
      stopLossBps: Number(values.stopLossBps),
      horizonSlots: Number(values.horizonSlots),
      latencySlots: Number(values.latencySlots),
      quoteMint: values.quoteMint.trim(),
    },
  };
}

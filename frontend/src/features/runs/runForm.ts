import type { Config, ConfigLimits, Range, RunStartRequest } from '../../api/types';
import { isSolanaAddress } from '../../lib/base58';
import { validatePositiveDecimal } from '../../lib/decimal';

/** The editable `SimulationConfig` parameters (plan Domain Rule 5). */
export const INTEGER_FIELDS = ['feeBps', 'slippageBps', 'maxWaitSlots', 'maxAgeSlots', 'holdingSlots'] as const;
export type IntegerField = (typeof INTEGER_FIELDS)[number];

export interface RunFormValues {
  datasetId: string | null;
  /** Kept as the typed text and sent as a string, never as a JS number. */
  notional: string;
  feeBps: number | string;
  slippageBps: number | string;
  maxWaitSlots: number | string;
  maxAgeSlots: number | string;
  holdingSlots: number | string;
  quoteMint: string;
}

export const FIELD_LABELS: Record<IntegerField, string> = {
  feeBps: 'Fee (bps)',
  slippageBps: 'Slippage (bps)',
  maxWaitSlots: 'Max wait (slots)',
  maxAgeSlots: 'Max observation age (slots)',
  holdingSlots: 'Holding period (slots)',
};

export const FIELD_HINTS: Record<IntegerField | 'notional' | 'quoteMint', string> = {
  notional: 'Quote units spent per entry, as a plain decimal.',
  feeBps: 'Fee charged on entry and exit notional.',
  slippageBps: 'Adverse slippage applied to entry and exit prices.',
  maxWaitSlots: 'How many slots after the target an observation may arrive.',
  maxAgeSlots: 'How old (by price slot) an observation may be when used.',
  holdingSlots: 'Slots after the entry fill before the exit is attempted.',
  quoteMint: 'Only swaps against this mint create signals. Wrapped SOL by default.',
};

export function formValuesFrom(config: Config, datasetId: string | null): RunFormValues {
  return {
    datasetId,
    notional: config.notional,
    feeBps: config.feeBps,
    slippageBps: config.slippageBps,
    maxWaitSlots: config.maxWaitSlots,
    maxAgeSlots: config.maxAgeSlots,
    holdingSlots: config.holdingSlots,
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

/** Client-side validation mirroring the backend limits; returns one message per invalid field. */
export function validateRunForm(values: RunFormValues, limits: ConfigLimits): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!values.datasetId) errors.datasetId = 'Choose a dataset';
  const notional = validatePositiveDecimal(values.notional, {
    maxFractionDigits: limits.notionalScale,
    maxIntegerDigits: limits.notionalPrecision - limits.notionalScale,
  });
  if (notional) errors.notional = notional;
  for (const field of INTEGER_FIELDS) {
    const error = integerError(values[field], limits[field]);
    if (error) errors[field] = error;
  }
  if (!isSolanaAddress(values.quoteMint)) errors.quoteMint = 'Must be a 32-byte base58 address';
  return errors;
}

/** The `POST /api/runs` body. Notional stays a string; integers are JSON numbers. */
export function toRunStartRequest(values: RunFormValues): RunStartRequest {
  return {
    datasetId: values.datasetId ?? '',
    config: {
      notional: values.notional.trim(),
      feeBps: Number(values.feeBps),
      slippageBps: Number(values.slippageBps),
      maxWaitSlots: Number(values.maxWaitSlots),
      maxAgeSlots: Number(values.maxAgeSlots),
      holdingSlots: Number(values.holdingSlots),
      quoteMint: values.quoteMint.trim(),
    },
  };
}

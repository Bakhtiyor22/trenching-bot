/**
 * Exact decimal-string helpers. The backend sends money, prices and quantities as decimal strings;
 * the UI must show them exactly. Nothing here converts to a float except {@link toChartNumber},
 * which exists only for plotting.
 */

const PLAIN_DECIMAL = /^[+-]?\d+(\.\d+)?$/;
const UNSIGNED_PLAIN_DECIMAL = /^\d+(\.\d+)?$/;

/** True for a plain decimal such as "-12.50" (no exponent, no grouping, no spaces). */
export function isDecimalString(value: string): boolean {
  return PLAIN_DECIMAL.test(value);
}

/** -1, 0 or 1, decided from the digits of the string (no float conversion, so no rounding). */
export function decimalSign(value: string): -1 | 0 | 1 {
  const trimmed = value.trim();
  if (!isDecimalString(trimmed)) return 0;
  const negative = trimmed.startsWith('-');
  const digits = trimmed.replace(/^[+-]/, '').replace('.', '');
  if (!/[1-9]/.test(digits)) return 0;
  return negative ? -1 : 1;
}

/** Same as {@link decimalSign}; reads better at call sites that compare against zero. */
export function compareToZero(value: string): -1 | 0 | 1 {
  return decimalSign(value);
}

export function isPositiveDecimal(value: string): boolean {
  return decimalSign(value) === 1;
}

/** Inserts "," thousands separators into the integer part only; the digits are unchanged. */
function groupIntegerDigits(integer: string): string {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

export interface FormatDecimalOptions {
  /** Add thousands separators to the integer part. Default true. */
  group?: boolean;
}

/**
 * Formats a decimal string for display without changing a single digit: every fractional digit
 * (including trailing zeros) is kept. A value that is not a plain decimal is returned unchanged.
 */
export function formatDecimal(
  value: string | null | undefined,
  { group = true }: FormatDecimalOptions = {},
): string {
  if (value == null) return '—';
  const trimmed = value.trim();
  if (!isDecimalString(trimmed)) return value;
  if (!group) return trimmed;
  const sign = trimmed.startsWith('-') ? '-' : trimmed.startsWith('+') ? '+' : '';
  const unsigned = trimmed.replace(/^[+-]/, '');
  const [integer = '', fraction] = unsigned.split('.');
  const grouped = groupIntegerDigits(integer);
  return fraction === undefined ? `${sign}${grouped}` : `${sign}${grouped}.${fraction}`;
}

const SUBSCRIPT_DIGITS = '₀₁₂₃₄₅₆₇₈₉';
/** Below this many leading fractional zeros a price is short enough to show as is. */
const COMPACT_MIN_ZEROS = 4;

/**
 * DEX-style short form of a tiny price: the run of leading fractional zeros becomes a subscript
 * count, so "0.00000008643" reads "0.0₇8643". Every significant digit is kept, so it is still
 * exact; values with fewer than four leading zeros, and non-decimals, go through {@link formatDecimal}.
 */
export function compactDecimal(value: string | null | undefined): string {
  if (value == null) return '—';
  const trimmed = value.trim();
  const match = /^([+-]?)0\.(0+)(\d*)$/.exec(trimmed);
  if (!match || match[2]!.length < COMPACT_MIN_ZEROS || !/[1-9]/.test(match[3]!)) return formatDecimal(value);
  const [, sign, zeros, digits] = match;
  const count = String(zeros!.length).replace(/\d/g, (d) => SUBSCRIPT_DIGITS[Number(d)]!);
  return `${sign}0.0${count}${digits}`;
}

/** A plain decimal as a scaled integer: value = units / 10^scale. */
function toScaled(value: string): { units: bigint; scale: number } {
  const trimmed = value.trim();
  const negative = trimmed.startsWith('-');
  const [integer = '0', fraction = ''] = trimmed.replace(/^[+-]/, '').split('.');
  const units = BigInt(integer + fraction);
  return { units: negative ? -units : units, scale: fraction.length };
}

/**
 * Exact sum of plain decimal strings, at the largest scale among them ("26.0895" + "-3.58" =
 * "22.5095"). Used for running totals that are shown, so no float rounding creeps in.
 * Throws on a value that is not a plain decimal.
 */
export function addDecimals(...values: string[]): string {
  for (const v of values) {
    if (!isDecimalString(v.trim())) throw new Error(`Not a plain decimal: ${v}`);
  }
  const parts = values.map(toScaled);
  const scale = Math.max(0, ...parts.map((p) => p.scale));
  const total = parts.reduce((sum, p) => sum + p.units * 10n ** BigInt(scale - p.scale), 0n);
  const digits = (total < 0n ? -total : total).toString().padStart(scale + 1, '0');
  const sign = total < 0n ? '-' : '';
  if (scale === 0) return `${sign}${digits}`;
  return `${sign}${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}

/** The same plain decimal with its sign flipped ("-3.5" → "3.5", "2" → "-2"). */
export function negateDecimal(value: string): string {
  const trimmed = value.trim();
  return trimmed.startsWith('-') ? trimmed.slice(1) : `-${trimmed.replace(/^\+/, '')}`;
}

/** Exact ordering of two plain decimal strings: -1, 0 or 1, for sorting by amount. */
export function compareDecimals(a: string, b: string): -1 | 0 | 1 {
  return decimalSign(addDecimals(a, negateDecimal(b)));
}

/** Drops trailing fractional zeros ("212.34000000" → "212.34"); the value is unchanged. */
export function trimFractionZeros(value: string): string {
  if (!isDecimalString(value.trim()) || !value.includes('.')) return value;
  return value.trim().replace(/0+$/, '').replace(/\.$/, '');
}

export type PnlTone = 'positive' | 'negative' | 'zero';

export function pnlTone(value: string): PnlTone {
  const sign = decimalSign(value);
  return sign > 0 ? 'positive' : sign < 0 ? 'negative' : 'zero';
}

/** Mantine color for a P&L value: green above zero, red below, dimmed at zero. */
export function pnlColor(value: string): 'teal' | 'red' | 'dimmed' {
  const tone = pnlTone(value);
  return tone === 'positive' ? 'teal' : tone === 'negative' ? 'red' : 'dimmed';
}

/**
 * Converts to a JS number for chart coordinates ONLY. Precision may be lost; never display the
 * result or send it back to the server.
 */
export function toChartNumber(value: string | null | undefined): number | null {
  if (value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export interface PlainDecimalRule {
  /** Most fraction digits accepted once trailing zeros are dropped; the backend refuses more rather than rounding. */
  maxFractionDigits: number;
  /** Most integer digits (backend precision - scale). */
  maxIntegerDigits: number;
  /** Inclusive minimum, as a plain decimal. */
  min: string;
  /** Inclusive maximum, as a plain decimal; unbounded when absent. */
  max?: string;
}

/**
 * Validates user input meant to be sent as a non-negative plain decimal string (e.g. notional or transaction cost),
 * exactly on its digits. Returns an error message, or null when valid.
 */
export function validateDecimal(input: string, rule: PlainDecimalRule): string | null {
  const value = input.trim();
  if (value === '') return 'Required';
  if (!UNSIGNED_PLAIN_DECIMAL.test(value)) {
    return 'Use a plain decimal such as 100 or 0.5 (no sign, exponent or separators)';
  }
  const [integer = '', fraction = ''] = value.split('.');
  if (fraction.replace(/0+$/, '').length > rule.maxFractionDigits) {
    return `At most ${rule.maxFractionDigits} decimals`;
  }
  if (integer.replace(/^0+/, '').length > rule.maxIntegerDigits) {
    return `At most ${rule.maxIntegerDigits} digits before the decimal point`;
  }
  const belowMin = compareDecimals(value, rule.min) < 0;
  const aboveMax = rule.max !== undefined && compareDecimals(value, rule.max) > 0;
  if (belowMin || aboveMax) {
    return rule.max === undefined ? `Must be at least ${rule.min}` : `Must be between ${rule.min} and ${rule.max}`;
  }
  return null;
}

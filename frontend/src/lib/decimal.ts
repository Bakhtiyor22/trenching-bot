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
  /** Fraction digits retained by backend HALF_EVEN normalization. */
  maxFractionDigits: number;
  /** Maximum integer digits (backend precision - scale). */
  maxIntegerDigits: number;
}

/**
 * Validates user input meant to be sent as a positive plain decimal string (e.g. notional).
 * Returns an error message, or null when valid.
 */
export function validatePositiveDecimal(input: string, rule: PlainDecimalRule): string | null {
  const value = input.trim();
  if (value === '') return 'Required';
  if (!UNSIGNED_PLAIN_DECIMAL.test(value)) {
    return 'Use a plain positive decimal such as 100 or 0.5 (no sign, exponent or separators)';
  }
  const [integer = '', fraction = ''] = value.split('.');
  const significantInteger = integer.replace(/^0+(?=\d)/, '');
  if (significantInteger.length > rule.maxIntegerDigits) {
    return `At most ${rule.maxIntegerDigits} digits before the decimal point`;
  }
  const retained = fraction.slice(0, rule.maxFractionDigits).padEnd(rule.maxFractionDigits, '0');
  const discarded = fraction.slice(rule.maxFractionDigits);
  let scaled = BigInt(significantInteger + retained);
  const firstDiscarded = discarded[0] ?? '0';
  const aboveHalf =
    firstDiscarded > '5' || (firstDiscarded === '5' && /[1-9]/.test(discarded.slice(1)));
  const oddTie = firstDiscarded === '5' && !/[1-9]/.test(discarded.slice(1)) && scaled % 2n === 1n;
  if (aboveHalf || oddTie) scaled += 1n;
  if (scaled === 0n)
    return `Must be greater than zero after rounding to ${rule.maxFractionDigits} decimals`;
  if (scaled.toString().length > rule.maxIntegerDigits + rule.maxFractionDigits) {
    return `At most ${rule.maxIntegerDigits} digits before the decimal point`;
  }
  return null;
}

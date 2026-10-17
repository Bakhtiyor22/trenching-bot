import { trimFractionZeros } from './decimal';

/** Compatibility for recordings made before the server returned percent strings. */
export function bpsToPercent(bps: number): string {
  const digits = String(Math.abs(bps)).padStart(3, '0');
  return trimFractionZeros(`${bps < 0 ? '-' : ''}${digits.slice(0, -2)}.${digits.slice(-2)}`);
}

export function formatPercent(
  value: string,
  { signed = false }: { signed?: boolean } = {},
): string {
  const text = trimFractionZeros(value);
  return `${text.startsWith('-') ? `−${text.slice(1)}` : signed && /[1-9]/.test(text) ? `+${text}` : text}%`;
}

export function levelPercent(percent: string | undefined, bps: number, signed = false): string {
  return formatPercent(percent ?? bpsToPercent(bps), { signed });
}

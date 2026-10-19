import type { ChartResponse } from '../api/types';
import {
  addDecimals,
  compactDecimal,
  compareDecimals,
  negateDecimal,
  trimFractionZeros,
} from '../lib/decimal';

export type MarketRangePreset = 'cycle' | '5m' | '15m' | '1h' | 'all';
export interface MarketTimeRange {
  from: string;
  to: string;
}
const MINUTE = 60_000;

function scaled(value: string, scale: number): bigint {
  const [integer = '0', fraction = ''] = value.replace(/^[+-]/, '').split('.');
  const amount = BigInt(integer + fraction.padEnd(scale, '0'));
  return value.startsWith('-') ? -amount : amount;
}

export function marketValue(value: string | null, view: 'mcap' | 'price'): string {
  if (value == null) return '—';
  if (view === 'price') return `${compactDecimal(trimFractionZeros(value))} SOL`;
  const magnitude = value.replace(/^-/, '');
  const unit = [
    ['1000000000', 'b'],
    ['1000000', 'm'],
    ['1000', 'k'],
  ].find(([threshold]) => compareDecimals(magnitude, threshold ?? '0') >= 0);
  const sign = value.startsWith('-') ? '−' : '';
  if (!unit) return `${sign}$${trimFractionZeros(magnitude)}`;
  const scale = value.split('.')[1]?.length ?? 0;
  const denominator = BigInt(unit[0] ?? '1') * 10n ** BigInt(scale);
  const tenths = (scaled(magnitude, scale) * 10n + denominator / 2n) / denominator;
  return `${sign}$${tenths / 10n}${tenths % 10n ? `.${tenths % 10n}` : ''}${unit[1]}`;
}

/** Significant digits a short price keeps; the exact value belongs in a tooltip. */
const SHORT_PRICE_DIGITS = 5;

/** A tiny SOL price cut to its first significant digits (not rounded), e.g. "0.0₆19602 SOL". */
export function shortPrice(value: string | null): string {
  if (value == null) return '—';
  const trimmed = trimFractionZeros(value);
  const match = /^([+-]?)0\.(0*)(\d+)$/.exec(trimmed);
  const cut =
    match && match[3]!.length > SHORT_PRICE_DIGITS
      ? `${match[1]}0.${match[2]}${match[3]!.slice(0, SHORT_PRICE_DIGITS)}`
      : trimmed;
  return `${compactDecimal(cut)} SOL`;
}

export function marketChange(first: string, last: string) {
  const absolute = trimFractionZeros(addDecimals(last, negateDecimal(first)));
  const scale = Math.max(first.split('.')[1]?.length ?? 0, last.split('.')[1]?.length ?? 0);
  const initial = scaled(first, scale);
  const difference = scaled(last, scale) - initial;
  const denominator = initial < 0n ? -initial : initial;
  const numerator = (difference < 0n ? -difference : difference) * 10000n;
  const hundredths = denominator === 0n ? null : (numerator + denominator / 2n) / denominator;
  return {
    up: difference >= 0n,
    absolute,
    percent:
      hundredths === null
        ? null
        : `${hundredths / 100n}.${String(hundredths % 100n).padStart(2, '0')}`,
  };
}

function trackedActions(data: ChartResponse) {
  return data.markers.items
    .filter((item) => item.isTrackedWallet)
    .sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.slot - b.slot);
}

/** When the cycle starts: the first buy at [sourceSlot], else the tracked wallet's first buy, else null. */
export function cycleStart(data: ChartResponse, sourceSlot?: number): number | null {
  const actions = trackedActions(data);
  const first =
    actions.find((item) => item.side === 'BUY' && item.slot === sourceSlot) ??
    actions.find((item) => item.side === 'BUY');
  return first ? Date.parse(first.time) : null;
}

export function marketRange(
  data: ChartResponse,
  preset: MarketRangePreset,
  sourceSlot?: number,
): MarketTimeRange | null {
  if (preset === 'all') return null;
  const actions = trackedActions(data);
  const start = cycleStart(data, sourceSlot) ?? Date.parse(data.range.from);
  if (preset === 'cycle') {
    const end = Math.max(start, Date.parse(actions.at(-1)?.time ?? data.range.to));
    return {
      from: new Date(start - 3 * MINUTE).toISOString(),
      to: new Date(end + 3 * MINUTE).toISOString(),
    };
  }
  const duration = preset === '5m' ? 5 : preset === '15m' ? 15 : 60;
  return {
    from: new Date(start - MINUTE).toISOString(),
    to: new Date(start - MINUTE + duration * MINUTE).toISOString(),
  };
}

export function timeInRange(time: string, range: MarketTimeRange | null): boolean {
  return (
    range === null ||
    (Date.parse(time) >= Date.parse(range.from) && Date.parse(time) <= Date.parse(range.to))
  );
}

export function marketTime(time: string | number): string {
  return `${new Date(time).toISOString().slice(11, 19)} UTC`;
}

/** Whole seconds from [origin] to [time], signed, e.g. `-30s`, `0s`, `+42s`. */
export function secondsFrom(time: number, origin: number): string {
  const seconds = Math.round((time - origin) / 1000);
  return seconds > 0 ? `+${seconds}s` : `${seconds}s`;
}

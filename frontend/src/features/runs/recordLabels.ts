import { humanizeCode } from '../../lib/format';

/** Known recording sources in words; the raw source stays in a tooltip where it is shown. */
const SOURCE_LABELS: Record<string, string> = {
  'pumpfun-bonding-curve-trade': 'Pump.fun curve trade',
  'pumpfun-bonding-curve-buy': 'Pump.fun curve buy',
  'curated-fixture': 'Curated fixture',
  'pumpfun-standard-supply': 'Pump.fun standard supply',
};

const INTERVAL_UNITS: Record<string, string> = { s: 'second', m: 'minute', h: 'hour', d: 'day' };
const RATE_ORIGINS: Record<string, string> = { binance: 'Binance', recorded: 'Recorded' };

/** "1m" → "1-minute"; anything else unchanged. */
export function intervalLabel(interval: string | null | undefined): string {
  if (!interval) return '—';
  const match = /^(\d+)([smhd])$/.exec(interval);
  return match ? `${match[1]}-${INTERVAL_UNITS[match[2]!]}` : interval;
}

/** "recorded:SOLUSDT:1m:open" → "Recorded SOLUSDT 1-minute open"; other sources go through {@link sourceLabel}. */
export function rateSourceLabel(source: string | null | undefined): string {
  if (!source) return '—';
  const [origin, pair, interval, field, ...rest] = source.split(':');
  if (!origin || !pair || rest.length > 0) return sourceLabel(source);
  const words = [RATE_ORIGINS[origin] ?? humanizeCode(origin), pair];
  if (interval) words.push(intervalLabel(interval));
  if (field) words.push(field.toLowerCase());
  return words.join(' ');
}

/** "pumpfun-bonding-curve-trade/v1" → "Pump.fun curve trade (v1)"; an unknown source is humanized. */
export function sourceLabel(source: string | null | undefined): string {
  if (!source) return '—';
  const [name = source, version] = source.split('/');
  const words = SOURCE_LABELS[name] ?? humanizeCode(name.replaceAll('-', '_').replaceAll(':', ' '));
  return version ? `${words} (${version})` : words;
}

/** "CONFIRMED" → "Confirmed". */
export function commitmentLabel(commitment: string | null | undefined): string {
  return humanizeCode(commitment);
}

/** A wallet or market side in words, e.g. "BUY" → "Buy". */
export function sideLabel(side: 'BUY' | 'SELL' | null | undefined): string {
  return side === 'SELL' ? 'Sell' : side === 'BUY' ? 'Buy' : '—';
}

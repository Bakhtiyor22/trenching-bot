/** Display helpers for ids, mints, times and telemetry. Money is formatted in `decimal.ts`. */

/** Wrapped SOL mint; shown as "SOL". */
export const WRAPPED_SOL_MINT = 'So11111111111111111111111111111111111111112';

/** First 4 … last 4 characters of an address, hash or id. */
export function shortId(value: string, head = 4, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

/** Friendly label for a quote/base mint: "SOL" for wrapped SOL, otherwise the shortened address. */
export function mintLabel(mint: string): string {
  return mint === WRAPPED_SOL_MINT ? 'SOL' : shortId(mint);
}

const localDateTime = new Intl.DateTimeFormat(undefined, {
  year: 'numeric',
  month: 'short',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

/** Local time for display; the original UTC ISO string is shown in a tooltip by `<Timestamp>`. */
export function formatLocalTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : localDateTime.format(date);
}

/** Slots are exact integers; group digits for readability. */
export function formatSlot(slot: number | null | undefined): string {
  if (slot == null) return '—';
  return slot.toLocaleString('en-US');
}

export function formatCount(value: number | null | undefined): string {
  if (value == null) return '—';
  return value.toLocaleString('en-US');
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '—';
  const totalSeconds = Math.floor(ms / 1000);
  const days = Math.floor(totalSeconds / 86_400);
  const hours = Math.floor((totalSeconds % 86_400) / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function formatPercent(ratio: number | null | undefined): string {
  if (ratio == null) return '—';
  return `${(ratio * 100).toFixed(1)}%`;
}

/** "MISSING_OBSERVATION" → "Missing observation". */
export function humanizeCode(code: string | null | undefined): string {
  if (!code) return '—';
  const words = code.toLowerCase().split(/[_\s]+/).filter(Boolean);
  const sentence = words.join(' ');
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}

/** "first – last" slot range, or a dash for an empty dataset. */
export function slotRange(range: { firstSlot: number | null; lastSlot: number | null }): string {
  if (range.firstSlot == null || range.lastSlot == null) return '—';
  return `${formatSlot(range.firstSlot)} – ${formatSlot(range.lastSlot)}`;
}

/** Where a USDT value came from: the SOL/USDT rate and the UTC minute whose Binance 1-minute open it is. */
export function usdtRateNote(rate: string | null | undefined, minute: string | null | undefined): string | null {
  if (!rate || !minute) return null;
  return `At ${rate} USDT per SOL, Binance 1-minute open of ${minute.slice(0, 16).replace('T', ' ')} UTC`;
}

/** Basis points with an explicit sign, e.g. "+19 bps" for a buy's price impact and "-22 bps" for a sale's. */
export function signedBps(bps: number): string {
  return `${bps > 0 ? '+' : ''}${bps} bps`;
}

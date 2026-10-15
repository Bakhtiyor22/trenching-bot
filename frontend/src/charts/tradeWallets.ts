import { isSolanaAddress } from '../lib/base58';

export const WATCHLIST_KEY = 'trenching.chart.watchlist';
export const WATCHLIST_LIMIT = 19;

export function readWatchlist(): string[] {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(WATCHLIST_KEY) ?? '[]');
    return Array.isArray(stored)
      ? [...new Set(stored.filter((v): v is string => typeof v === 'string' && isSolanaAddress(v)))].slice(0, WATCHLIST_LIMIT)
      : [];
  } catch {
    // Corrupt or unavailable browser storage must not prevent chart access.
    return [];
  }
}

export function exactUnits(raw: string, decimals: number): string {
  if (!/^\d+$/.test(raw)) return raw;
  const digits = raw.padStart(decimals + 1, '0');
  return `${digits.slice(0, -decimals)}.${digits.slice(-decimals)}`;
}

export function walletPattern(address: string): boolean[] {
  let hash = 2166136261;
  for (const char of address) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
  return Array.from({ length: 25 }, (_, index) => {
    const column = index % 5;
    const bit = Math.floor(index / 5) * 3 + Math.min(column, 4 - column);
    return ((hash >>> bit) & 1) === 1;
  });
}

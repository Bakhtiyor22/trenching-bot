import { describe, expect, it } from 'vitest';
import { exactUnits, readWatchlist, walletPattern } from './tradeWallets';

describe('wallet chart helpers', () => {
  it('scales raw units without losing digits above safe integer precision', () => {
    expect(exactUnits('18446744073709551615', 9)).toBe('18446744073.709551615');
    expect(exactUnits('12', 6)).toBe('0.000012');
    expect(exactUnits('1000000000', 9)).toBe('1.000000000');
  });
  it('loads only valid unique addresses and bounds the watchlist', () => {
    localStorage.setItem('trenching.chart.watchlist', JSON.stringify(['bad', '1'.repeat(32), '1'.repeat(32)]));
    expect(readWatchlist()).toEqual(['1'.repeat(32)]);
    localStorage.setItem('trenching.chart.watchlist', '{');
    expect(readWatchlist()).toEqual([]);
    localStorage.removeItem('trenching.chart.watchlist');
  });
  it('gives each address a repeatable mirrored identicon', () => {
    const pattern = walletPattern('1'.repeat(32));
    expect(pattern).toEqual(walletPattern('1'.repeat(32)));
    expect(pattern).toHaveLength(25);
    for (let row = 0; row < 5; row += 1) {
      expect(pattern[row * 5]).toBe(pattern[row * 5 + 4]);
      expect(pattern[row * 5 + 1]).toBe(pattern[row * 5 + 3]);
    }
  });
});

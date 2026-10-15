import { describe, expect, it } from 'vitest';
import { marketChange, marketRange, marketValue } from './marketSummary';
import type { ChartResponse } from '../api/types';

describe('market summary', () => {
  it('formats compact Mcap and tiny SOL prices without floating point conversion', () => {
    expect(marketValue('15500', 'mcap')).toBe('$15.5k');
    expect(marketValue('3400000000', 'mcap')).toBe('$3.4b');
    expect(marketValue('0.0000000775', 'price')).toBe('0.0₇775 SOL');
  });
  it('computes exact change and rounded percent from the first valid value', () => {
    expect(marketChange('100', '104.12')).toEqual({ up: true, absolute: '4.12', percent: '4.12' });
    expect(marketChange('18446744073709551615', '18446744073709551616')).toEqual({ up: true, absolute: '1', percent: '0.00' });
    expect(marketChange('0', '1').percent).toBeNull();
    expect(marketChange('100', '95').up).toBe(false);
  });
  it('derives Cycle and duration ranges from the first buy and last Decu action', () => {
    const data = {
      range: { from: '2026-10-15T15:20:00Z', to: '2026-10-15T16:00:00Z' },
      markers: { items: [
        { isTrackedWallet: true, side: 'BUY', slot: 10, time: '2026-10-15T15:23:00Z' },
        { isTrackedWallet: true, side: 'SELL', slot: 20, time: '2026-10-15T15:30:00Z' },
        { isTrackedWallet: false, side: 'BUY', slot: 30, time: '2026-10-15T15:50:00Z' },
      ] },
    } as ChartResponse;
    expect(marketRange(data, 'cycle')).toEqual({ from: '2026-10-15T15:20:00.000Z', to: '2026-10-15T15:33:00.000Z' });
    expect(marketRange(data, '5m')).toEqual({ from: '2026-10-15T15:22:00.000Z', to: '2026-10-15T15:27:00.000Z' });
    expect(marketRange(data, 'all')).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import { commitmentLabel, intervalLabel, rateSourceLabel, sideLabel, sourceLabel } from './recordLabels';

describe('record labels', () => {
  it('puts recording sources, commitments and sides in words', () => {
    expect(sourceLabel('pumpfun-bonding-curve-trade/v1')).toBe('Pump.fun curve trade (v1)');
    expect(sourceLabel('some-new-source')).toBe('Some new source');
    expect(sourceLabel(null)).toBe('—');
    expect(commitmentLabel('CONFIRMED')).toBe('Confirmed');
    expect(sideLabel('SELL')).toBe('Sell');
    expect(sideLabel(null)).toBe('—');
  });

  it('reads a rate source and its interval', () => {
    expect(rateSourceLabel('recorded:SOLUSDT:1m:open')).toBe('Recorded SOLUSDT 1-minute open');
    expect(rateSourceLabel('binance:SOLUSDT:ticker')).toBe('Binance SOLUSDT ticker');
    expect(intervalLabel('1m')).toBe('1-minute');
    expect(intervalLabel('weekly')).toBe('weekly');
  });
});

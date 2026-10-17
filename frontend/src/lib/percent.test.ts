import { describe, expect, it } from 'vitest';
import { bpsToPercent, formatPercent } from './percent';

describe('exact percent display', () => {
  it.each([
    [1, '0.01'],
    [125, '1.25'],
    [1550, '15.5'],
    [9999, '99.99'],
    [-11, '-0.11'],
  ])('formats %s basis points', (bps, percent) => {
    expect(bpsToPercent(bps)).toBe(percent);
  });
  it('uses readable signs and trims only insignificant zeros', () => {
    expect(formatPercent('20.00')).toBe('20%');
    expect(formatPercent('-0.11', { signed: true })).toBe('−0.11%');
    expect(formatPercent('1.25', { signed: true })).toBe('+1.25%');
  });
});

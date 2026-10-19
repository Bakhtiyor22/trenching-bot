import { describe, expect, it } from 'vitest';
import { formatSettingValue } from './configLabels';

describe('agreed setting values', () => {
  it('formats percentages, exact SOL, grouped dollars, slots and quote mints', () => {
    expect(formatSettingValue('stopLossPercent', '40')).toBe('40%');
    expect(formatSettingValue('notional', '0.100000000000')).toBe('0.1 SOL');
    expect(formatSettingValue('minMarketCapUsd', '10000.000000000000')).toBe('$10,000');
    expect(formatSettingValue('horizonSlots', 4500)).toBe('4,500 slots');
    expect(formatSettingValue('quoteMint', 'So11111111111111111111111111111111111111112')).toBe(
      'SOL',
    );
  });
});

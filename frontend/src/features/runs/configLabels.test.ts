import { describe, expect, it } from 'vitest';
import { clockLabel, formatSettingValue, horizonLabel } from './configLabels';

describe('horizon label', () => {
  it('converts slots to time at the recorded pace, else at the nominal one and says so', () => {
    expect(horizonLabel(4500, 217.6)).toBe(
      '4,500 slots (about 16 min at the recorded 217.6 ms per slot)',
    );
    expect(horizonLabel(4500, null)).toBe(
      '4,500 slots (about 30 min at the nominal 400 ms per slot)',
    );
    expect(horizonLabel(450, 217.6)).toBe(
      '450 slots (about 98 s at the recorded 217.6 ms per slot)',
    );
  });
});

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

describe('clock labels', () => {
  it('shows a clock in seconds, or Off when the run is untimed', () => {
    expect(formatSettingValue('dipWindowSeconds', '30')).toBe('30 s');
    expect(formatSettingValue('maxHoldSeconds', '')).toBe('Off');
    expect(clockLabel(240000)).toBe('240 s');
    expect(clockLabel(null)).toBe('Off');
    expect(clockLabel(undefined)).toBe('Off');
  });
});

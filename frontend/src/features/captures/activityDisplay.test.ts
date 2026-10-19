import { describe, expect, it } from 'vitest';
import { solFromLamports } from './activityDisplay';

describe('activity SOL amounts', () => {
  it.each([
    ['1900000000', '1.9', '1.9'],
    ['2400000001', '2.4', '2.400000001'],
    ['0', '0', '0'],
    ['1', '0', '0.000000001'],
    ['18446744073709551615', '18446744073.709', '18446744073.709551615'],
  ])('formats %s without floating-point conversion', (lamports, display, full) => {
    expect(solFromLamports(lamports)).toEqual({ display, full });
  });
});

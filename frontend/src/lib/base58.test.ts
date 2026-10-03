import { describe, expect, it } from 'vitest';
import { isSolanaAddress } from './base58';
import { WRAPPED_SOL_MINT } from './format';

describe('isSolanaAddress', () => {
  it('accepts 32-byte base58 addresses', () => {
    expect(isSolanaAddress(WRAPPED_SOL_MINT)).toBe(true);
    expect(isSolanaAddress('11111111111111111111111111111111')).toBe(true);
    expect(isSolanaAddress('11111111111111111111111111111112')).toBe(true);
  });

  it('rejects non-base58 characters and wrong lengths', () => {
    expect(isSolanaAddress('0OIl1111111111111111111111111111')).toBe(false);
    expect(isSolanaAddress('abc')).toBe(false);
    expect(isSolanaAddress('')).toBe(false);
    expect(isSolanaAddress('1'.repeat(64))).toBe(false);
  });
});

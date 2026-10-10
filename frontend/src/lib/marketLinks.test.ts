import { describe, expect, it } from 'vitest';
import { recordedEventSignature, recordedTradeSignature, tokenLinks, transactionLinks } from './marketLinks';

const BUY = '35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA';
const TRADE = '2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM';
const MINT = '5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs';

describe('marketLinks', () => {
  it('links a transaction to Solscan and Solana Explorer', () => {
    expect(transactionLinks(TRADE)).toEqual([
      { label: 'Solscan', href: `https://solscan.io/tx/${TRADE}` },
      { label: 'Solana Explorer', href: `https://explorer.solana.com/tx/${TRADE}` },
    ]);
  });

  it('links a token to pump.fun and DexScreener', () => {
    expect(tokenLinks(MINT)).toEqual([
      { label: 'pump.fun', href: `https://pump.fun/coin/${MINT}` },
      { label: 'DexScreener', href: `https://dexscreener.com/solana/${MINT}` },
    ]);
  });

  it('builds no link for a value that is not a 64-byte signature or 32-byte mint', () => {
    expect(transactionLinks(MINT)).toEqual([]);
    expect(transactionLinks('not/base58')).toEqual([]);
    expect(tokenLinks(TRADE)).toEqual([]);
  });

  it('reads the transaction of a recorded Pump.fun trade observation only', () => {
    expect(recordedTradeSignature(`pumpfun-bonding-curve-trade/v1:${TRADE}:4:15:${MINT}`)).toBe(TRADE);
    expect(recordedTradeSignature('observation-101')).toBeNull();
    expect(recordedTradeSignature(`curated-fixture:${TRADE}:1:2:${MINT}`)).toBeNull();
    expect(recordedTradeSignature(`pumpfun-bonding-curve-trade/v1:${'1'.repeat(10)}:1:2:${MINT}`)).toBeNull();
  });

  it('reads the transaction of a recorded wallet buy only, never a synthetic fixture event', () => {
    expect(recordedEventSignature({ source: 'pumpfun-bonding-curve-buy/v1', signature: BUY })).toBe(BUY);
    expect(recordedEventSignature({ source: 'curated-fixture', signature: '1'.repeat(64) })).toBeNull();
  });
});

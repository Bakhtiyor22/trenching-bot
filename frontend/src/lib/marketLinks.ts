import { base58ByteLength, isSolanaAddress } from './base58';

/**
 * Outside market and explorer links for records of recorded mainnet captures. Only those sources get links: they come
 * from captures verified against the mainnet genesis, so a link never points at a synthetic or devnet record.
 */
export interface MarketLink {
  label: string;
  href: string;
}

const RECORDED_BUY_SOURCE = 'pumpfun-bonding-curve-buy/v1';
const RECORDED_TRADE_SOURCE = 'pumpfun-bonding-curve-trade/v1';
const SIGNATURE_BYTES = 64;

function isSignature(value: string): boolean {
  return value.length >= 64 && value.length <= 88 && base58ByteLength(value) === SIGNATURE_BYTES;
}

/** Solscan and Solana Explorer pages of a transaction; none for a value that is not a signature. */
export function transactionLinks(signature: string): MarketLink[] {
  if (!isSignature(signature)) return [];
  const value = encodeURIComponent(signature);
  return [
    { label: 'Solscan', href: `https://solscan.io/tx/${value}` },
    { label: 'Solana Explorer', href: `https://explorer.solana.com/tx/${value}` },
  ];
}

/** pump.fun and DexScreener pages of a token; none for a value that is not a mint address. */
export function tokenLinks(mint: string): MarketLink[] {
  if (!isSolanaAddress(mint)) return [];
  const value = encodeURIComponent(mint);
  return [
    { label: 'pump.fun', href: `https://pump.fun/coin/${value}` },
    { label: 'DexScreener', href: `https://dexscreener.com/solana/${value}` },
  ];
}

/**
 * The transaction of a recorded Pump.fun trade observation, from its id
 * `pumpfun-bonding-curve-trade/v1:<signature>:<top-level index>:<inner index>:<mint>`; null for any other observation.
 */
export function recordedTradeSignature(observationId: string): string | null {
  const [source, signature] = observationId.split(':');
  return source === RECORDED_TRADE_SOURCE && signature && isSignature(signature) ? signature : null;
}

/** The transaction of a recorded wallet buy; null for any other wallet event. */
export function recordedEventSignature(event: { source: string; signature: string }): string | null {
  return event.source === RECORDED_BUY_SOURCE && isSignature(event.signature) ? event.signature : null;
}

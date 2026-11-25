import type { SolUsdtPrice } from '../api/types';
import { codeLabel } from './codes';
import { multiplyDecimals, trimFractionZeros } from './decimal';

/**
 * A SOL amount's value now, at the live SOL/USDT rate (`GET /api/market/sol-usdt`, the header's own query): an exact
 * product, never rounded; its display is cut to {@link USDT_PLACES}. It is today's worth of a SOL result, not a realized
 * USDT P&L: the run's page converts each trade at its own minute.
 */

/** USDT values are shown to this many places; the exact product goes in a tooltip. */
export const USDT_PLACES = 2;

/** The rate's own time, "20:51:30", on the viewer's clock; null without one. */
export function rateTime(asOf: string | null | undefined): string | null {
  return asOf ? new Date(asOf).toLocaleTimeString('en-GB') : null;
}

/** `sol` × the live rate, exactly; null without a rate. */
export function usdtNow(sol: string, price: SolUsdtPrice | null | undefined): string | null {
  return price?.rate ? multiplyDecimals(sol, price.rate) : null;
}

/** The rate in use, said visibly ("USDT at 1 SOL = 69.08 USDT (now, 20:51:30)"), or why there is none; null while loading. */
export function usdtHint(price: SolUsdtPrice | null | undefined): string | null {
  if (!price) return null;
  if (price.rate) {
    const time = rateTime(price.asOf);
    return `USDT at 1 SOL = ${trimFractionZeros(price.rate)} USDT (now${time ? `, ${time}` : ''})`;
  }
  return price.unavailableReason ? `USDT unavailable: ${codeLabel(price.unavailableReason)}` : null;
}

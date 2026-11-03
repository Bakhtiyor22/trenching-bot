import type { Config, Scenario } from '../../api/types';
import { formatSlot } from '../../lib/format';
import { NOMINAL_SLOT_MS } from './configLabels';
import { secondsText } from './runForm';

/**
 * Where a cycle's dip watch ended: at the dip, at Decu's sell, when the token migrated, when the dip window closed, at
 * the horizon, or never started (the cycle was decided at Decu's buy).
 */
export type WatchEnd =
  | { kind: 'NOT_WATCHED' }
  | { kind: 'DIP'; slot: number }
  | { kind: 'WALLET_SOLD'; slot: number }
  | { kind: 'MIGRATED' }
  | { kind: 'DIP_WINDOW'; ms: number }
  | { kind: 'HORIZON'; slot: number };

/** Watch outcomes decided at Decu's buy, before any later trade is read. */
const DECIDED_AT_BUY = new Set([
  'REFERENCE_UNAVAILABLE',
  'REFERENCE_BELOW_MIN_MARKET_CAP',
  'UNSUPPORTED_SUPPLY',
  'SIGNAL_BELOW_MIN_SIZE',
  'SIGNAL_SIZE_UNAVAILABLE',
]);

export type WatchConfig = Pick<Config, 'dipWindowMs'>;

export function watchEnd(s: Scenario, config?: WatchConfig): WatchEnd {
  if (s.trigger) return { kind: 'DIP', slot: s.trigger.slot };
  if (s.walletSell) return { kind: 'WALLET_SOLD', slot: s.walletSell.slot };
  if (DECIDED_AT_BUY.has(s.watch)) return { kind: 'NOT_WATCHED' };
  if (s.watch === 'MIGRATED_BEFORE_ENTRY') return { kind: 'MIGRATED' };
  if (config?.dipWindowMs != null) return { kind: 'DIP_WINDOW', ms: config.dipWindowMs };
  return { kind: 'HORIZON', slot: s.horizonEndSlot };
}

/** The watch's end for the cycle card header, e.g. "watched for the 30 s dip window"; null when nothing was watched. */
export function watchEndText(end: WatchEnd): string | null {
  switch (end.kind) {
    case 'NOT_WATCHED':
      return null;
    case 'DIP':
      return `watched until the dip at slot ${formatSlot(end.slot)}`;
    case 'WALLET_SOLD':
      return `watched until Decu sold at slot ${formatSlot(end.slot)}`;
    case 'MIGRATED':
      return 'watched until the token migrated';
    case 'DIP_WINDOW':
      return `watched for the ${secondsText(end.ms)} s dip window`;
    case 'HORIZON':
      return `watched through slot ${formatSlot(end.slot)}`;
  }
}

/**
 * How many slots after the first buy the watch ran. A dip window converts at the recorded pace (or the nominal 400 ms),
 * capped at the horizon; a migration, whose slot is not reported, counts to that same bound.
 */
export function watchEndOffset(
  s: Scenario,
  config?: WatchConfig,
  millisPerSlot?: number | null,
): number {
  const horizon = s.horizonEndSlot - s.sourceSlot;
  const windowSlots =
    config?.dipWindowMs != null
      ? Math.min(horizon, Math.round(config.dipWindowMs / (millisPerSlot || NOMINAL_SLOT_MS)))
      : horizon;
  const end = watchEnd(s, config);
  switch (end.kind) {
    case 'NOT_WATCHED':
      return 0;
    case 'DIP':
    case 'WALLET_SOLD':
      return end.slot - s.sourceSlot;
    case 'MIGRATED':
    case 'DIP_WINDOW':
      return windowSlots;
    case 'HORIZON':
      return horizon;
  }
}

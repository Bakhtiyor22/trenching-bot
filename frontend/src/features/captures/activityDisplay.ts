import type { WalletAction } from '../../api/setup';
import { trimFractionZeros } from '../../lib/decimal';

export function solFromLamports(lamports: string) {
  const digits = BigInt(lamports).toString().padStart(10, '0');
  const full = trimFractionZeros(`${digits.slice(0, -9)}.${digits.slice(-9)}`);
  const [whole, fraction] = full.split('.');
  return {
    full,
    display: fraction ? trimFractionZeros(`${whole}.${fraction.slice(0, 3)}`) : full,
  };
}

export function activityNote(action: WalletAction) {
  if (action.side === 'SELL') return 'Sells cannot start a capture';
  if (action.capturedIn.length) return 'captured';
  if (action.earlierActionInPage) return 'An earlier action on this token is in the list';
  if (!action.overSignalThreshold) return 'At or under 1.9 SOL: the agreed strategy skips it';
  return 'No earlier action in this list (proof comes in the wallet step)';
}

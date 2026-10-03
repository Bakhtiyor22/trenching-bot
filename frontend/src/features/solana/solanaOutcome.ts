import type { AddressCheckOutcome, SolanaCheckOutcome } from '../../api/types';

export type OutcomeColor = 'teal' | 'yellow' | 'red' | 'gray';

/** OK green; rate limited / timeout yellow; errors red; not configured neutral (never red). */
export function outcomeColor(outcome: SolanaCheckOutcome | AddressCheckOutcome | string): OutcomeColor {
  switch (outcome) {
    case 'OK':
    case 'CHECKED':
      return 'teal';
    case 'RATE_LIMITED':
    case 'TIMEOUT':
    case 'NO_SUCCESSFUL_TRANSACTION':
      return 'yellow';
    case 'RPC_ERROR':
    case 'UNAVAILABLE':
      return 'red';
    default:
      return 'gray';
  }
}

const LABELS: Record<string, string> = {
  OK: 'OK',
  CHECKED: 'Checked',
  NOT_CONFIGURED: 'Not configured',
  RATE_LIMITED: 'Rate limited',
  TIMEOUT: 'Timed out',
  RPC_ERROR: 'RPC error',
  UNAVAILABLE: 'Unavailable',
  NO_SUCCESSFUL_TRANSACTION: 'No successful transaction',
};

export function outcomeLabel(outcome: string): string {
  return LABELS[outcome] ?? outcome;
}

export function clusterColor(cluster: string | null | undefined): string {
  switch (cluster) {
    case 'mainnet-beta':
      return 'orange';
    case 'devnet':
      return 'blue';
    case 'testnet':
      return 'cyan';
    default:
      return 'gray';
  }
}

import type { AddressCheckOutcome, SolanaCheckOutcome } from '../../api/types';
import type { StatusTone } from '../../components/StatusChip';
import { humanizeCode } from '../../lib/format';

/** OK success; rate limited / timeout / nothing to inspect need attention; errors fail; not configured is neutral (never red). */
export function outcomeTone(outcome: SolanaCheckOutcome | AddressCheckOutcome | string): StatusTone {
  switch (outcome) {
    case 'OK':
    case 'CHECKED':
      return 'success';
    case 'RATE_LIMITED':
    case 'TIMEOUT':
    case 'NO_SUCCESSFUL_TRANSACTION':
      return 'attention';
    case 'RPC_ERROR':
    case 'UNAVAILABLE':
      return 'failure';
    default:
      return 'neutral';
  }
}

const TONE_GLYPHS: Partial<Record<StatusTone, string>> = {
  success: '✓',
  failure: '✕',
  attention: '▲',
  neutral: '–',
};

/** The chip glyph for a tone, so a status never relies on colour alone. */
export function toneGlyph(tone: StatusTone): string | undefined {
  return TONE_GLYPHS[tone];
}

export type OutcomeColor = 'teal' | 'orange' | 'red' | 'gray';

/** The Mantine colour token of an outcome (used by the dashboard health line). */
export function outcomeColor(outcome: SolanaCheckOutcome | AddressCheckOutcome | string): OutcomeColor {
  const tone = outcomeTone(outcome);
  return tone === 'success' ? 'teal' : tone === 'failure' ? 'red' : tone === 'attention' ? 'orange' : 'gray';
}

/** The Mantine colour of a message box beside an outcome: grey unless it needs attention or failed. */
export function outcomeAlertColor(outcome: string): 'gray' | 'orange' | 'red' {
  const color = outcomeColor(outcome);
  return color === 'teal' ? 'gray' : color;
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

/** Whether an outcome has a written label; an unknown one is shown in words with its code beside it. */
export function isKnownOutcome(outcome: string): boolean {
  return Object.hasOwn(LABELS, outcome);
}

export function outcomeLabel(outcome: string): string {
  return LABELS[outcome] ?? humanizeCode(outcome);
}

const CLUSTER_LABELS: Record<string, string> = {
  'mainnet-beta': 'Mainnet',
  devnet: 'Devnet',
  testnet: 'Testnet',
  unknown: 'Unknown cluster',
};

/** "mainnet-beta" → "Mainnet"; an unexpected name is turned into words. */
export function clusterLabel(cluster: string): string {
  return CLUSTER_LABELS[cluster] ?? humanizeCode(cluster.replace(/-/g, '_'));
}

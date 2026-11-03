import type { CaptureStep } from '../../api/captures';
import type { StatusTone } from '../../components/StatusChip';
import { humanizeCode } from '../../lib/format';

/** Words for each operation step, for titles and tables (never the raw step code). */
export const operationStepLabels: Record<CaptureStep, string> = {
  WALLET: 'Wallet',
  MARKET: 'Markets',
  RATES: 'Rates',
  INSPECT: 'Saved-file check',
  EXPORT: 'Dataset export',
  CALIBRATE: 'Calibration',
  QUALIFY: 'Qualification',
};

/** An operation or stage state in lower-case words: "STOPPED_BUDGET" → "stopped budget". */
export function operationStateLabel(state: string): string {
  return humanizeCode(state).toLowerCase();
}

const stageStates: Record<string, { label: string; tone: StatusTone; glyph?: string }> = {
  READY: { label: 'Ready', tone: 'success', glyph: '✓' },
  SUCCEEDED: { label: 'Succeeded', tone: 'success', glyph: '✓' },
  FAILED: { label: 'Failed', tone: 'failure', glyph: '✕' },
  INVALID: { label: 'Invalid', tone: 'failure', glyph: '✕' },
  NOT_STARTED: { label: 'Not started', tone: 'neutral', glyph: '–' },
  RUNNING: { label: 'Running', tone: 'running' },
  WAITING_RETRY: { label: 'Waiting to retry', tone: 'running' },
};

/** The chip of a stage, case or operation state: a word and a tone; anything unknown reads as needing a look. */
export function stageStateChip(state: string): { label: string; tone: StatusTone; glyph?: string } {
  return stageStates[state] ?? { label: humanizeCode(state), tone: 'attention', glyph: '▲' };
}

const summaryKeys: Record<string, string> = {
  selected: 'Transactions selected',
  requestsUsed: 'Requests used',
  bytesUsed: 'Bytes downloaded',
  scannedSignatures: 'Signatures scanned',
  skippedFailed: 'Failed transactions skipped',
  buys: 'Buys found',
  decoder: 'Decoder',
  windows: 'Market windows',
  scanPages: 'Scan pages per window',
  maxTransactions: 'Transaction capacity',
  observations: 'Observations',
  extractor: 'Extractor',
  minutes: 'Rate minutes needed',
  recorded: 'Rate minutes recorded',
  mint: 'Mint',
  fromSlot: 'From slot',
  toSlot: 'To slot',
  coverage: 'Coverage',
  scanned: 'Transactions scanned',
  detailGaps: 'Detail gaps',
  cutoffThroughSlot: 'Cut off through slot',
  cutoffOutcome: 'Cutoff outcome',
  observedThrough: 'Observed through slot',
};
const summaryGroups: Record<string, string> = {
  outcome: 'Download outcome',
  decode: 'Decode outcome',
  rejection: 'Rejected',
  instruction: 'Rejected instruction',
  exclusion: 'Excluded',
  unavailable: 'Rate unavailable',
};

/** A camelCase key in sentence-case words: "medianErrorBps" → "Median error bps". */
export function camelCaseWords(key: string): string {
  return humanizeCode(key.replace(/([a-z\d])([A-Z])/g, '$1_$2'));
}

/** "requestsUsed" → "Requests used"; the camelCase words for any key not listed. */
function keyWords(key: string): string {
  return summaryKeys[key] ?? camelCaseWords(key);
}

/**
 * A label for one key of an inspection summary. Keys are flat ("buys"), grouped by a reason code
 * ("outcome.SAVED") or per market window ("window.0.mint"); each part is put into words.
 */
export function summaryKeyLabel(key: string): string {
  const [head, ...rest] = key.split('.');
  if (!head) return key;
  if (head === 'window' && rest.length >= 2 && /^\d+$/.test(rest[0] ?? '')) {
    return `Window ${Number(rest[0]) + 1} · ${keyWords(rest.slice(1).join('.')).toLowerCase()}`;
  }
  if (rest.length > 0) {
    const group = summaryGroups[head] ?? keyWords(head);
    return `${group} · ${humanizeCode(rest.join('_')).toLowerCase()}`;
  }
  return keyWords(head);
}

/** Calibration latency check outcomes in words. */
export const latencyStatusLabels: Record<string, string> = {
  CHECKED: 'Checked',
  NO_PRIOR_STATE: 'No prior state',
  UNPREDICTABLE: 'Unpredictable',
};

/** Calibration report summary fields in words, in the order shown. */
export const calibrationSummaryLabels: Record<string, string> = {
  buys: 'Buys',
  mathExact: 'Exact math',
  mathMismatches: 'Math mismatches',
  latencyChecked: 'Latency checked',
  noPriorState: 'No prior state',
  unpredictable: 'Unpredictable',
  medianErrorBps: 'Median error (bps)',
  maxErrorBps: 'Largest error (bps)',
  medianTransactionFeeLamports: 'Median transaction fee (lamports)',
  medianOtherLamports: 'Median other lamports',
  instructions: 'Instructions',
};

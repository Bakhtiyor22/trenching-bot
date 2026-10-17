import type { CaptureSummaryStatus, NextActionKind } from '../api/captures';
import type { PresetFieldStatus, UsagePurpose, UsageTask } from '../api/setup';
import { reasonText } from '../features/captures/captureReasons';
import { humanizeCode } from './format';

export const statusLabels: Record<CaptureSummaryStatus, string> = {
  NOT_STARTED: 'Ready for the next step',
  RUNNING: 'Downloading',
  WAITING_RETRY: 'Waiting to retry',
  PAUSED: 'Paused',
  STOPPED_LIMIT: 'Request or data limit reached',
  STOPPED_DEADLINE: 'Time limit reached',
  NEEDS_ATTENTION: 'Needs attention',
  READY_TO_REPLAY: 'Ready to prepare a dataset',
  DONE: 'Dataset ready',
  CANCELLED: 'Cancelled',
};
export const actionLabels: Record<NextActionKind, string> = {
  START_WALLET: 'Start wallet step',
  RESUME_WALLET: 'Resume wallet step',
  RETRY_WALLET: 'Retry wallet step',
  START_MARKETS: 'Start market step',
  RESUME_MARKETS: 'Resume market step',
  CAPTURE_RATES: 'Capture rates',
  REGISTER_DATASET: 'Prepare dataset',
  START_RUN: 'Start run',
  VIEW_RESULTS: 'View results',
  INSPECT: 'Inspect evidence',
  WAIT: 'Waiting for progress',
  NONE: 'No action needed',
};
export const purposeLabels: Record<UsagePurpose, string> = {
  READ_TRANSACTION: 'Read full transactions',
  LIST_SIGNATURES: 'Find transaction signatures',
  MAINNET_CHECK: 'Check the Solana network',
  MINT_PROGRAM_CHECK: 'Check the token program',
  CONNECTION_CHECK: 'Check the connection',
  MINUTE_RATES: 'Record minute prices',
  LIVE_RATE: 'Read the current SOL price',
  UNKNOWN: 'Other provider call',
};
export const taskLabels: Record<UsageTask, string> = {
  WALLET_STEP: 'Wallet step',
  MARKET_STEP: 'Market step',
  RATES_STEP: 'Rates step',
  CASE_RATES: 'Token reference rates',
  WALLET_ACTIVITY: 'Recent wallet activity',
  CONNECTION_CHECK: 'Connection check',
  LIVE_PRICE: 'Current SOL price',
  UNATTRIBUTED: 'Other activity',
};
export const presetStatusLabels: Record<PresetFieldStatus, string> = {
  AGREED: 'Agreed',
  AGREED_MEASURED_DIFFERENTLY: 'Agreed, measured differently',
  ASSUMPTION: 'Assumption',
  ENGINE_DEFAULT: 'Engine default',
  MISSING: 'Missing in agreed strategy',
};
export const recommendationCopy: Record<string, { title: string; why: string }> = {
  SCOPE_WALLET_TOKEN: {
    title: 'Focus on one wallet and token',
    why: 'Verify this wallet’s actual first supported buy before recording the token’s market.',
  },
  CUTOFF_DECIDED: {
    title: 'Stop when the legacy outcome is decided',
    why: 'Save requests by stopping each market window once the legacy strategy’s outcome is known.',
  },
  ENABLE_RECOVERY: {
    title: 'Recover from temporary provider stops',
    why: 'Retry within a fixed total request, data and attempt allowance.',
  },
  DECU_PUMPFUN_SCOPE: {
    title: 'Use the wallet’s Pump.fun buys',
    why: 'Wallet references can include trades made by other people.',
  },
  USE_AGREED_STRATEGY: {
    title: 'Use the agreed strategy',
    why: 'Start from the documented strategy values, with the engine’s gaps shown explicitly.',
  },
  ADD_LEGACY_COMPARISON: {
    title: 'Compare with the legacy defaults',
    why: 'Run the same dataset with the earlier settings and compare the results.',
  },
  SINGLE_TOKEN_DATASET: {
    title: 'This dataset follows one token',
    why: 'Its result describes this recorded token; it is not a broader strategy evaluation.',
  },
};
const explanations: Record<string, string> = {
  CUTOFF_USES_LEGACY_DEFAULTS:
    'This cutoff uses the legacy 15% dip, +30% take profit and −15% stop loss. A run with other settings may need a longer window.',
  RPC_NOT_CONFIGURED: 'Configure SOLANA_RPC_URL in the backend process to load wallet activity.',
  PROVIDER_RATE_LIMITED: 'The provider asked for a pause. Wait before refreshing.',
  PROVIDER_UNAVAILABLE: 'The provider is unavailable. Your previous results are kept below.',
  NOT_MAINNET: 'Wallet activity requires a Solana mainnet connection.',
  NAME_TAKEN: 'This capture name is already in use. Choose the suggested name or enter another.',
  INVALID_PERCENT: 'Use a percent between 0.01 and 99.99, with at most two decimal places.',
  CONFLICTING_FIELDS: 'The percent and basis point values disagree.',
  FIRST_EVER_BUY: 'First-ever buy proof',
  SIGNAL_OVER_1_9_SOL: 'Signal larger than 1.9 SOL',
  MCAP_AT_REFERENCE_BUY: 'Market cap at the reference buy',
  DIP_WINDOW_30_SECONDS: 'A 30-second dip window',
  ENTRY_SLIPPAGE_15_PERCENT: 'A 15% entry slippage limit',
  TIMED_EXIT: 'Timed exit',
  EXIT_RETRIES_AND_UNRESOLVED: 'Exit retries and unresolved outcomes',
  ONE_POSITION_INCLUDING_IN_FLIGHT: 'One position including pending entries',
  CURVE_PAYMENT_FEES_ON_TOP: 'Curve payment, with trading fees added on top.',
  NETWORK_FEE_NOT_ADDED: 'The separate network fee is not added by this preset.',
  MEASURED_AT_DIP_INCLUSIVE:
    'Checked at the dip with an inclusive minimum; the spec checks the reference buy with a strict minimum.',
  REPLAY_LATENCY_ASSUMED: 'One slot of replay latency is an assumption.',
  NO_SINGLE_WINDOW_IN_SPEC:
    'The spec has separate entry and exit clocks, so it defines no equivalent single observation window.',
};
export function codeLabel(code: string): string {
  return (
    (statusLabels as Record<string, string>)[code] ??
    (actionLabels as Record<string, string>)[code] ??
    explanations[code] ??
    reasonText(code) ??
    humanizeCode(code)
  );
}

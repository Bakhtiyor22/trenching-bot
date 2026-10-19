import type { CaptureSummaryStatus, NextActionKind } from '../api/captures';
import type { PresetFieldStatus, UsageProvider, UsagePurpose, UsageTask } from '../api/setup';
import { reasonText } from '../features/captures/captureReasons';
import { humanizeCode } from './format';

export const statusLabels: Record<CaptureSummaryStatus, string> = {
  NOT_STARTED: 'Ready to start',
  RUNNING: 'Running',
  WAITING_RETRY: 'Waiting to retry',
  PAUSED: 'Paused',
  STOPPED_LIMIT: 'Stopped · request limit',
  STOPPED_DEADLINE: 'Stopped · time limit',
  NEEDS_ATTENTION: 'Needs attention',
  READY_TO_REPLAY: 'Ready to replay',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
};
export const statusTones = {
  NOT_STARTED: 'idle',
  RUNNING: 'running',
  WAITING_RETRY: 'running',
  PAUSED: 'stopped',
  STOPPED_LIMIT: 'stopped',
  STOPPED_DEADLINE: 'stopped',
  NEEDS_ATTENTION: 'attention',
  READY_TO_REPLAY: 'ready',
  DONE: 'done',
  CANCELLED: 'done',
} as const satisfies Record<CaptureSummaryStatus, string>;
export const statusColors: Record<CaptureSummaryStatus, string> = {
  NOT_STARTED: 'violet',
  RUNNING: 'blue',
  WAITING_RETRY: 'blue',
  PAUSED: 'amber',
  STOPPED_LIMIT: 'amber',
  STOPPED_DEADLINE: 'amber',
  NEEDS_ATTENTION: 'red',
  READY_TO_REPLAY: 'teal',
  DONE: 'gray',
  CANCELLED: 'gray',
};
export const stageLabels = {
  WALLET: 'Wallet',
  MARKETS: 'Markets',
  RATES: 'Rates',
  DATASET: 'Dataset',
};
export const providerLabels: Record<UsageProvider, string> = {
  SOLANA_RPC: 'Solana RPC',
  BINANCE: 'Binance',
  TELEGRAM: 'Telegram',
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
  READ_TRANSACTION:
    'Download one full transaction: wallet buys, market trades and first-buy checks.',
  LIST_SIGNATURES: "List a wallet's or token's transactions, up to 1,000 per page.",
  MAINNET_CHECK: 'Confirm the provider is on Solana mainnet before a capture step.',
  MINT_PROGRAM_CHECK: 'Check which token program owns a token, for decoding.',
  CONNECTION_CHECK: 'Connection checks from the Solana page.',
  MINUTE_RATES: 'SOL/USDT price for every minute of a dataset (rates step).',
  LIVE_RATE: 'Live SOL/USDT price in the top bar and the USDT view of runs.',
  UNKNOWN: 'Another call to this provider.',
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
    title: 'Capture one token at a time',
    why: 'Each buy adds its own market window, and a busy token alone can need thousands of requests. One token gets you a replayable case sooner.',
  },
  CUTOFF_DECIDED: {
    title: 'Stop each window once the result is known',
    why: "Trades after the exit don't change the outcome, so the market step usually needs far fewer requests than a full 30-minute window.",
  },
  ENABLE_RECOVERY: {
    title: 'Resume automatically after a request limit',
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
    title: 'Also run the old defaults for comparison',
    why: 'Run the same dataset with the earlier settings and compare the results.',
  },
  SINGLE_TOKEN_DATASET: {
    title: 'This dataset has one token',
    why: "A single trade says little about the strategy. Results are judged on all signals together, including skips. Capture more of Decu's buys first.",
  },
};
const explanations: Record<string, string> = {
  STRATEGY_PRESET_UNAVAILABLE:
    'The agreed strategy could not be loaded. Retry, or choose to continue with this simulator’s defaults. Those defaults are not the agreed strategy.',
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

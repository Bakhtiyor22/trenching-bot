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
  SLOT_PACE:
    "Measure the chain's recent slot time, to time the retry of a market window that has not ended yet.",
  MINUTE_RATES: 'SOL/USDT price for every minute of a dataset (rates step).',
  LIVE_RATE: 'Live SOL/USDT price in the top bar and the USDT view of runs.',
  BLOCK_TIME: "Read the block time where a market window's recording ends, to prove a time exit.",
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
    why: "Each token is recorded until the agreed strategy's outcome is decided, and at least 4 minutes after Decu's buy, so the market step usually needs far fewer requests than recording every window in full.",
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
/** Run start refusal: the dataset's market recording ends before the outcome of these settings is decided. */
export const MARKET_COVERAGE_ENDED = 'MARKET_COVERAGE_ENDED';

/** The minimum recording a DECIDED capture keeps after Decu's buy when a recommendation carries no number. */
export const DEFAULT_MINIMUM_RECORDING_SECONDS = 240;

/** The CUTOFF_USES_AGREED_STRATEGY caveat for a capture that keeps [seconds] after Decu's buy. */
export function cutoffUsesAgreedStrategy(seconds: number): string {
  return `Windows are cut by the agreed strategy and kept at least ${seconds} s after Decu's buy. A run with other settings may be refused for coverage; use FULL for those.`;
}

/**
 * How a sale against the standing curve came about, which depends on the close's reason: a time exit sold at its
 * exit start; a take profit or stop loss found no trade within 60 s of its trigger.
 */
export function standingCurveLabel(reason: string): string {
  return reason === 'TIME_EXIT'
    ? 'Sold against the curve at the exit time'
    : 'No trade within 60 s — sold against the curve at the trigger';
}
const explanations: Record<string, string> = {
  STRATEGY_PRESET_UNAVAILABLE:
    'The agreed strategy could not be loaded. Retry, or choose to continue with this simulator’s defaults. Those defaults are not the agreed strategy.',
  CUTOFF_USES_AGREED_STRATEGY: cutoffUsesAgreedStrategy(DEFAULT_MINIMUM_RECORDING_SECONDS),
  COMPARISON_MAY_NEED_FULL_CAPTURE:
    'The legacy settings may need a longer recording than a DECIDED capture keeps; capture with FULL to compare.',
  RPC_NOT_CONFIGURED: 'Configure SOLANA_RPC_URL in the backend process to load wallet activity.',
  PROVIDER_RATE_LIMITED: 'The provider asked for a pause. Wait before refreshing.',
  PROVIDER_UNAVAILABLE: 'The provider is unavailable. Your previous results are kept below.',
  NOT_MAINNET: 'Wallet activity requires a Solana mainnet connection.',
  NAME_TAKEN: 'This capture name is already in use. Choose the suggested name or enter another.',
  INVALID_PERCENT: 'Use a percent between 0.01 and 99.99, with at most two decimal places.',
  CONFLICTING_FIELDS: 'The percent and basis point values disagree.',
  [MARKET_COVERAGE_ENDED]:
    'The dataset’s market recording ends before the outcome of these settings is decided.',
  FIRST_EVER_BUY: 'First-ever buy proof',
  SIGNAL_OVER_1_9_SOL: 'Signal larger than 1.9 SOL',
  POST_TRADE_PRICE: 'Post-trade prices for every comparison',
  ENTRY_SLIPPAGE_15_PERCENT: 'A 15% entry slippage limit',
  EXIT_RETRY_ECONOMICS: 'Exit retries, slippage steps and fee escalation',
  ONE_POSITION_INCLUDING_IN_FLIGHT: 'One position including pending entries',
  CURVE_PAYMENT_FEES_ON_TOP: 'Curve payment, with trading fees added on top.',
  NETWORK_FEE_NOT_ADDED: 'The separate network fee is not added by this preset.',
  REPLAY_LATENCY_ASSUMED: 'One slot of replay latency is an assumption.',
  RECORDING_BOUND_ONLY: 'Only bounds how far the market is recorded; the clocks end each cycle.',
  NEXT_TRADE: 'Sold into the next trade',
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

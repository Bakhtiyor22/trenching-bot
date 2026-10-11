import type {
  ActualFirstBuy,
  CalibrationReport,
  CaptureConfig,
  CaptureInspection,
  CaptureOperation,
  CaptureSession,
  CaptureStage,
  TargetToken,
  Verdict,
  VerificationBound,
} from '../api/captures';
import { BASE_MINT, ENGINE_VERSION, SAMPLE_DATASET_ID, SAMPLE_PNL, SIGNATURE, TAKE_PROFIT_ENTRY_PRICE, WALLET } from './fixtures';

export const CAPTURE_ID = 'capture-demo';
const CREATED_AT = '2026-10-10T09:00:00Z';

/** Mirrors the backend defaults and bounds in CaptureController/CaptureValidation. */
export const captureConfig: CaptureConfig = {
  rpcConfigured: true,
  ratesConfigured: true,
  walletDefaults: { wallet: '', scope: 'pumpfun-user-buys', limit: 1, scanPages: 20 },
  walletTokenDefaults: { wallet: '', scope: 'wallet-token', limit: 100, scanPages: 5, mint: '' },
  walletTokenBudgetDefaults: { requestBudget: 1000, byteBudget: 104857600, operationTimeoutMinutes: 10 },
  marketDefaults: { scanPages: 50, maxTransactions: 50000 },
  budgetDefaults: { requestBudget: 400, byteBudget: 104857600, operationTimeoutMinutes: 10 },
  bounds: {
    walletLimitMax: 100,
    walletPagesMax: 50,
    walletRequestsMax: 1000,
    walletBytesMax: 524288000,
    marketPagesMax: 500,
    marketTransactionsMax: 50000,
    marketRequestsMax: 60000,
    marketBytesMax: 4294967296,
    operationTimeoutMinutesMax: 30,
  },
  windowSlots: 4500,
};

const ready: CaptureStage = { state: 'READY', reason: null, canStart: false, canResume: false, requiresNewAttempt: false };

export const readyInspection: CaptureInspection = {
  wallet: ready,
  market: ready,
  rates: ready,
  canCalibrate: true,
  canExport: true,
  walletSummary: { selected: '1', buys: '1', requestsUsed: '7', bytesUsed: '48211', decoder: 'PUMPFUN_BONDING_CURVE_BUY' },
  marketSummary: { windows: '1', observations: '42', requestsUsed: '61', bytesUsed: '903114', scanPages: '50', maxTransactions: '50000' },
  ratesSummary: { minutes: '9', recorded: '9' },
  marketWindows: [
    { mint: BASE_MINT, fromSlot: 1000, toSlot: 5500, coverage: 'COMPLETE', scanned: 120, selected: 42, detailGaps: 0, observations: 42 },
  ],
};

export const calibrationReport: CalibrationReport = {
  wallet: WALLET,
  decoder: 'PUMPFUN_BONDING_CURVE_BUY',
  extractor: 'PUMPFUN_LAST_TRADE',
  summary: {
    buys: 1,
    mathExact: 1,
    mathMismatches: 0,
    latencyChecked: 1,
    noPriorState: 0,
    unpredictable: 0,
    medianErrorBps: '1234.5',
    maxErrorBps: '1234.5',
    medianTransactionFeeLamports: '5000',
    medianOtherLamports: '5000.5',
    instructions: { BUY_EXACT_QUOTE_IN_V3: 1 },
  },
  mathMismatches: [],
  buys: [
    {
      signature: SIGNATURE,
      slot: 1000,
      mint: BASE_MINT,
      solAmount: '0.1',
      instruction: 'BUY_EXACT_QUOTE_IN_V3',
      math: {
        exact: true,
        tokenAmount: '3512345678901',
        computedTokenAmount: '3512345678901',
        feeLamports: '950000',
        computedFeeLamports: '950000',
        creatorFeeLamports: '50000',
        computedCreatorFeeLamports: '50000',
      },
      latency: { status: 'CHECKED', observationSlot: 999, predictedTokenAmount: '3555700000000', errorBps: '-1234.5' },
      costs: { transactionFeeLamports: '5000', otherLamports: '5000.5' },
    },
  ],
};

export const captureOperation: CaptureOperation = {
  id: 'operation-export',
  requestId: 'operation-export',
  step: 'EXPORT',
  attemptId: 'market-1',
  state: 'SUCCEEDED',
  startedAt: '2026-10-10T09:10:00Z',
  updatedAt: '2026-10-10T09:10:02Z',
  endedAt: '2026-10-10T09:10:02Z',
  budget: { requestBudget: 400, byteBudget: 104857600, operationTimeoutMinutes: 10 },
  scanPages: null,
  progress: null,
  code: null,
  message: null,
};

export const captureSession: CaptureSession = {
  id: CAPTURE_ID,
  name: 'First wallet sample',
  createdAt: CREATED_AT,
  wallet: { wallet: WALLET, scope: 'pumpfun-user-buys', limit: 1, scanPages: 20 },
  walletAttemptId: 'wallet-1',
  marketAttempt: {
    id: 'market-1',
    stage: 'MARKET',
    createdAt: CREATED_AT,
    attached: false,
    market: { scanPages: 50, maxTransactions: 50000 },
    walletAttemptId: 'wallet-1',
    marketAttemptId: null,
  },
  ratesAttempt: {
    id: 'rates-1',
    stage: 'RATES',
    createdAt: CREATED_AT,
    attached: false,
    market: null,
    walletAttemptId: 'wallet-1',
    marketAttemptId: 'market-1',
  },
  inspection: readyInspection,
  operation: captureOperation,
  datasetReady: true,
  calibrationReady: true,
  datasetId: null,
  calibration: calibrationReport,
};

/** The dataset an import of the demo capture registers: the existing sample, so its links resolve. */
export const CAPTURE_DATASET_ID = SAMPLE_DATASET_ID;

/*
 * Wallet + token sessions, one per verdict the console must tell apart: a qualifying take-profit, a NO_DIP negative
 * case, a capped (VERIFICATION_BOUND) unproven first buy, a first buy outside the token account, a replay failure on a
 * complete capture, and an inspection-only incomplete capture.
 */
const TOKEN_2022_PROGRAM = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb';
export const TOKEN_ACCOUNT = '11111111111111111111111111111115';
export const FIRST_BUY_SIGNATURE = '2'.repeat(64);
export const CREATION_SIGNATURE = '3'.repeat(64);
export const OUTSIDE_SIGNATURE = '4'.repeat(64);
export const SAME_SLOT_BUY_SIGNATURE = '5'.repeat(64);
const FIRST_BUY_SLOT = 1000;
const CREATION_SLOT = 940;
/** The backend's coverage claim for a proven first buy, shown verbatim. */
export const PROVEN_CLAIM = `Actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC (creation ${CREATION_SIGNATURE} at slot ${CREATION_SLOT}; scanned Pump and PumpSwap accumulators to below ${CREATION_SLOT})`;
export const TRIGGER_PRICE = '0.0000000765';
export const TRIGGER_MARKET_CAP_USD = '11651.475000';
export const REPLAY_FAILURE_CATEGORY = 'DATASET_CHECKSUM';

const provenFirstBuy: ActualFirstBuy = {
  state: 'PROVEN',
  signature: FIRST_BUY_SIGNATURE,
  slot: FIRST_BUY_SLOT,
  creationSignature: CREATION_SIGNATURE,
  creationSlot: CREATION_SLOT,
  creationTime: '2026-09-30T12:00:00Z',
  reason: null,
  blocking: [],
  claim: PROVEN_CLAIM,
  bound: null,
};

export const provenTarget: TargetToken = {
  mint: BASE_MINT,
  tokenProgram: TOKEN_2022_PROGRAM,
  tokenAccount: TOKEN_ACCOUNT,
  pumpAccumulator: '11111111111111111111111111111116',
  pumpSwapAccumulator: '11111111111111111111111111111117',
  tokenAccountFirstBuy: { state: 'FOUND', signature: FIRST_BUY_SIGNATURE, slot: FIRST_BUY_SLOT, reason: null, blocking: [] },
  actualFirstBuy: provenFirstBuy,
};

/** The Pump accumulator listing hit its 50-page cap before reaching below the creation slot. */
export const verificationBound: VerificationBound = {
  limit: 'PUMP_ACCUMULATOR_PAGES',
  pages: { MINT: 2, PUMP_ACCUMULATOR: 50, PUMPSWAP_ACCUMULATOR: 1 },
  entries: { MINT: 1873, PUMP_ACCUMULATOR: 50000, PUMPSWAP_ACCUMULATOR: 3 },
  fetches: 212,
  oldestSlot: { MINT: 940, PUMP_ACCUMULATOR: 977, PUMPSWAP_ACCUMULATOR: null },
};

const ranProvenance: Verdict['provenance'] = {
  datasetChecksum: SAMPLE_DATASET_ID,
  runId: '7d1e2f3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6',
  configurationHash: 'c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00',
  engineVersion: ENGINE_VERSION,
  referencePrice: '0.0000000850',
  firstBuySignature: FIRST_BUY_SIGNATURE,
  firstBuySlot: FIRST_BUY_SLOT,
};
const complete: Verdict['capture'] = { state: 'COMPLETE', stage: null, reason: null };
const ran: Verdict['replay'] = { state: 'RAN', category: null };
const notRun: Verdict['replay'] = { state: 'NOT_RUN', category: null };
const notEvaluated = (reason: string | null): Verdict['dip'] => ({ state: 'NOT_EVALUATED', reason });

export const qualifyingVerdict: Verdict = {
  classification: 'QUALIFYING_DIP',
  capture: complete,
  dip: { state: 'QUALIFIED', reason: null, triggerSlot: 1002, triggerPrice: TRIGGER_PRICE, marketCapUsd: TRIGGER_MARKET_CAP_USD, rate: '152.31', observationId: 'observation-1002' },
  entry: { state: 'FILLED', reason: null, slot: 1003, executionPrice: TAKE_PROFIT_ENTRY_PRICE, observationId: 'observation-1003' },
  exit: { state: 'CLOSED', reason: 'TAKE_PROFIT', slot: 1006, realizedPnl: SAMPLE_PNL },
  replay: ran,
  provenance: ranProvenance,
};
const noDipVerdict: Verdict = {
  classification: 'NEGATIVE_CASE',
  capture: complete,
  dip: { state: 'NOT_QUALIFIED', reason: 'NO_DIP' },
  entry: { state: 'NOT_FILLED', reason: 'NO_DIP' },
  exit: null,
  replay: ran,
  provenance: ranProvenance,
};
/** Before export the verdict comes from inspection alone: replay NOT_RUN and no strategy outcome. */
const inspectionOnly = (classification: Verdict['classification'], capture: Verdict['capture']): Verdict => ({
  classification,
  capture,
  dip: notEvaluated(capture.reason ?? null),
  entry: null,
  exit: null,
  replay: notRun,
  provenance: {},
});
const replayFailedVerdict: Verdict = {
  classification: 'REPLAY_FAILED',
  capture: complete,
  dip: notEvaluated(null),
  entry: null,
  exit: null,
  replay: { state: 'FAILED', category: REPLAY_FAILURE_CATEGORY },
  provenance: { ...ranProvenance, runId: null, configurationHash: null },
};

const notStarted = (reason: string): CaptureStage => ({ state: 'NOT_STARTED', reason, canStart: false, canResume: false, requiresNewAttempt: false });
/** The wallet stage is unusable with the verdict's reason; nothing later can start. */
const blockedInspection = (reason: string, actualFirstBuy: ActualFirstBuy): CaptureInspection => ({
  ...readyInspection,
  targetToken: { ...provenTarget, actualFirstBuy },
  wallet: { state: 'UNUSABLE', reason, canStart: false, canResume: false, requiresNewAttempt: false },
  market: notStarted('WALLET_CAPTURE_NOT_READY'),
  rates: notStarted('MARKET_CAPTURE_NOT_READY'),
  canExport: false,
  canCalibrate: false,
  marketWindows: [],
});
/** Wallet verified and every stage complete, as after a successful export. */
const verifiedInspection: CaptureInspection = { ...readyInspection, targetToken: provenTarget };

const SAME_SLOT_BUY_REASON = 'SAME_SLOT_BUY';
const FIRST_BUY_NOT_LISTED_REASON = 'FIRST_BUY_NOT_LISTED';
const sameSlotBuyTarget: TargetToken = {
  ...provenTarget,
  tokenAccountFirstBuy: { state: 'NOT_ESTABLISHED', reason: SAME_SLOT_BUY_REASON, blocking: [SAME_SLOT_BUY_SIGNATURE] },
  actualFirstBuy: { state: 'NOT_VERIFIED', blocking: [] },
};
const sameSlotBuyInspection: CaptureInspection = {
  ...blockedInspection(SAME_SLOT_BUY_REASON, sameSlotBuyTarget.actualFirstBuy),
  targetToken: sameSlotBuyTarget,
};

function tokenSession(id: string, name: string, inspection: CaptureInspection, qualification: Verdict, datasetReady: boolean): CaptureSession {
  return {
    ...captureSession,
    id,
    name,
    wallet: { ...captureConfig.walletTokenDefaults, wallet: WALLET, mint: BASE_MINT },
    inspection,
    qualification,
    datasetReady,
    calibrationReady: false,
    calibration: null,
  };
}

export const walletTokenSessions = {
  qualifying: tokenSession('capture-token-qualifying', 'Wallet + token: take profit', verifiedInspection, qualifyingVerdict, true),
  noDip: tokenSession('capture-token-no-dip', 'Wallet + token: no dip', verifiedInspection, noDipVerdict, true),
  unproven: tokenSession(
    'capture-token-unproven',
    'Wallet + token: verification capped',
    blockedInspection('VERIFICATION_BOUND', { state: 'UNPROVEN', reason: 'VERIFICATION_BOUND', blocking: [], bound: verificationBound }),
    inspectionOnly('FIRST_BUY_UNPROVEN', { state: 'UNUSABLE', stage: 'WALLET', reason: 'VERIFICATION_BOUND' }),
    false,
  ),
  outside: tokenSession(
    'capture-token-outside',
    'Wallet + token: first buy elsewhere',
    blockedInspection('FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT', { state: 'OUTSIDE_TOKEN_ACCOUNT', signature: OUTSIDE_SIGNATURE, slot: 978, blocking: [] }),
    inspectionOnly('FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT', { state: 'UNUSABLE', stage: 'WALLET', reason: 'FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT' }),
    false,
  ),
  replayFailed: tokenSession('capture-token-replay-failed', 'Wallet + token: replay failed', verifiedInspection, replayFailedVerdict, true),
  incomplete: tokenSession(
    'capture-token-incomplete',
    'Wallet + token: market incomplete',
    {
      ...verifiedInspection,
      market: { state: 'INCOMPLETE', reason: 'MARKET_CAPTURE_INCOMPLETE', canStart: false, canResume: true, requiresNewAttempt: false },
      rates: notStarted('MARKET_CAPTURE_NOT_READY'),
      canExport: false,
    },
    inspectionOnly('INCOMPLETE_CAPTURE', { state: 'INCOMPLETE', stage: 'MARKET', reason: 'MARKET_CAPTURE_INCOMPLETE' }),
    false,
  ),
  pendingQualification: tokenSession(
    'capture-token-pending-qualification',
    'Wallet + token: qualification pending',
    verifiedInspection,
    inspectionOnly('INCOMPLETE_CAPTURE', { state: 'COMPLETE', stage: 'EXPORT', reason: 'QUALIFICATION_PENDING' }),
    true,
  ),
  sameSlotBuy: tokenSession(
    'capture-token-same-slot-buy',
    'Wallet + token: same-slot buys',
    sameSlotBuyInspection,
    inspectionOnly('FIRST_BUY_UNPROVEN', { state: 'UNUSABLE', stage: 'WALLET', reason: SAME_SLOT_BUY_REASON }),
    false,
  ),
  firstBuyNotListed: tokenSession(
    'capture-token-first-buy-not-listed',
    'Wallet + token: known buy missing from listing',
    blockedInspection(FIRST_BUY_NOT_LISTED_REASON, { state: 'UNPROVEN', reason: FIRST_BUY_NOT_LISTED_REASON, blocking: [FIRST_BUY_SIGNATURE] }),
    inspectionOnly('FIRST_BUY_UNPROVEN', { state: 'UNUSABLE', stage: 'WALLET', reason: FIRST_BUY_NOT_LISTED_REASON }),
    false,
  ),
} satisfies Record<string, CaptureSession>;

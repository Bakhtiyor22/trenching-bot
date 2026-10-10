import type {
  CalibrationReport,
  CaptureConfig,
  CaptureInspection,
  CaptureOperation,
  CaptureSession,
  CaptureStage,
} from '../api/captures';
import { BASE_MINT, SAMPLE_DATASET_ID, SIGNATURE, WALLET } from './fixtures';

export const CAPTURE_ID = 'capture-demo';
const CREATED_AT = '2026-10-10T09:00:00Z';

/** Mirrors the backend defaults and bounds in CaptureController/CaptureValidation. */
export const captureConfig: CaptureConfig = {
  rpcConfigured: true,
  ratesConfigured: true,
  walletDefaults: { wallet: '', scope: 'pumpfun-user-buys', limit: 1, scanPages: 20 },
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

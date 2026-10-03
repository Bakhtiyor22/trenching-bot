/**
 * Mock data mirroring the backend sample run (ADR 0001 hand-calculated example): a confirmed buy at
 * slot 100, entries at 101/102/103 (price 10 → execution 10.05), exits at 106/107/108 (price 11 →
 * execution 10.945), realized P&L 8.696567164180 per scenario, 26.089701492540 in total.
 * A second "edge" dataset/run covers rejections and an open position with no exit.
 */
import type {
  Close,
  Config,
  ConfigDefaults,
  Dashboard,
  Dataset,
  Fill,
  Observation,
  Pair,
  RunDetail,
  RunEvent,
  RunOrder,
  RunPosition,
  RunSummary,
  Scenario,
  SolanaStatus,
} from '../api/types';

export const SOL_MINT = 'So11111111111111111111111111111111111111112';
export const BASE_MINT = '11111111111111111111111111111112';
export const WALLET = '11111111111111111111111111111111';
export const SIGNATURE = '1'.repeat(64);
export const PAIR: Pair = { base: BASE_MINT, quote: SOL_MINT };

export const SAMPLE_DATASET_ID = '7da78ada00b144606af1578d5d72de54f879149f906e785833d6bab9c9e862f6';
export const EDGE_DATASET_ID = 'b3f1c2d4e5a6978812ab34cd56ef7890a1b2c3d4e5f60718293a4b5c6d7e8f90';
export const SAMPLE_RUN_ID = '4f0c9a7e2b1d3c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6';
export const EDGE_RUN_ID = '9a8b7c6d5e4f30211203f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f';
export const SAMPLE_PNL = '8.696567164180';
export const SAMPLE_TOTAL_PNL = '26.089701492540';
export const ENGINE_VERSION = 'fixture-paper-v2';
export const CONFIG_HASH = 'c0ffee00112233445566778899aabbccddeeff00112233445566778899aabbcc';

const CREATED_AT = '2026-10-02T09:15:30Z';
const OBSERVED_AT = '2026-09-23T00:00:00Z';

export const sampleDataset: Dataset = {
  id: SAMPLE_DATASET_ID,
  name: 'weekly-buy-v1',
  trackedWallet: WALLET,
  schemaVersion: 1,
  transactionCount: 4,
  observationCount: 6,
  firstSlot: 100,
  lastSlot: 111,
  pairs: [PAIR],
  createdAt: CREATED_AT,
};

export const edgeDataset: Dataset = {
  id: EDGE_DATASET_ID,
  name: 'edge-cases',
  trackedWallet: WALLET,
  schemaVersion: 1,
  transactionCount: 1,
  observationCount: 2,
  firstSlot: 200,
  lastSlot: 201,
  pairs: [PAIR],
  createdAt: '2026-10-01T18:02:11Z',
};

function observation(slot: number, price: string, quality = 'LAST_TRADE'): Observation {
  return {
    id: `observation-${slot}`,
    pair: PAIR,
    slot,
    priceSlot: slot,
    observedTime: OBSERVED_AT,
    price,
    liquidityQuote: '10000',
    source: 'curated-fixture',
    sourceEventId: `market-${slot}`,
    commitment: 'CONFIRMED',
    quality,
  };
}

export const sampleObservations: Observation[] = [
  observation(101, '10'),
  observation(102, '10'),
  observation(103, '10'),
  observation(106, '11'),
  observation(107, '11'),
  observation(108, '11'),
];

export const edgeObservations: Observation[] = [
  observation(201, '0.00000000001234'),
  { ...observation(203, '0.00000000001301', 'INVALID'), priceSlot: 199 },
];

export const configDefaults: ConfigDefaults = {
  defaults: {
    strategyName: 'confirmed-buy',
    strategyVersion: '1',
    notional: '100',
    feeBps: 10,
    slippageBps: 50,
    maxWaitSlots: 2,
    maxAgeSlots: 2,
    holdingSlots: 5,
    quoteMint: SOL_MINT,
  },
  limits: {
    notionalScale: 12,
    notionalPrecision: 38,
    feeBps: { min: 0, max: 9999 },
    slippageBps: { min: 0, max: 9999 },
    maxWaitSlots: { min: 0, max: 1000 },
    maxAgeSlots: { min: 0, max: 1000 },
    holdingSlots: { min: 1, max: null },
  },
};

const mvpConfig: Config = configDefaults.defaults;

function sampleFill(offset: number): Fill {
  const slot = 100 + offset;
  return {
    orderId: `order-sample-${offset}`,
    targetSlot: slot,
    actualSlot: slot,
    observationId: `observation-${slot}`,
    executionPrice: '10.05',
    quantity: '9.950248756219',
    fee: '0.100000000000',
    costBasis: '100.100000000000',
    feeBps: 10,
    slippageBps: 50,
    priceSource: 'curated-fixture',
    priceAssumption: 'LAST_TRADE',
    priorityFee: '0.000000000000',
    priceImpactBps: 0,
  };
}

function sampleClose(offset: number): Close {
  const slot = 105 + offset;
  return {
    slot,
    observationId: `observation-${slot}`,
    executionPrice: '10.945',
    grossProceeds: '108.905472636817',
    fee: '0.108905472637',
    netProceeds: '108.796567164180',
    realizedPnl: SAMPLE_PNL,
    reason: 'MAX_HOLDING_SLOTS',
  };
}

const OFFSETS = [1, 2, 3];

export const sampleScenarios: Scenario[] = OFFSETS.map((offset) => ({
  intentId: `intent-sample-${offset}`,
  sourceEventId: 'event-sample-buy-100',
  sourceSlot: 100,
  entryOffset: offset,
  targetSlot: 100 + offset,
  pair: PAIR,
  notional: '100.000000000000',
  status: 'FILLED',
  rejectionReason: null,
  fill: sampleFill(offset),
  selectedObservation: sampleObservations[offset - 1] ?? null,
  position: { id: `position-sample-${offset}`, state: 'CLOSED', close: sampleClose(offset) },
  exitUnavailableReason: null,
}));

export const sampleRun: RunSummary = {
  id: SAMPLE_RUN_ID,
  datasetId: SAMPLE_DATASET_ID,
  engineVersion: ENGINE_VERSION,
  configurationHash: CONFIG_HASH,
  resultChecksum: 'aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899',
  quoteMint: SOL_MINT,
  scenarioCount: 3,
  filledCount: 3,
  rejectedCount: 0,
  openCount: 0,
  closedCount: 3,
  realizedPnl: SAMPLE_TOTAL_PNL,
  createdAt: '2026-10-02T09:20:00Z',
};

export const sampleRunDetail: RunDetail = {
  run: sampleRun,
  config: mvpConfig,
  scenarios: sampleScenarios,
};

const edgeFill: Fill = {
  ...sampleFill(1),
  orderId: 'order-edge-1',
  targetSlot: 201,
  actualSlot: 201,
  observationId: 'observation-201',
  executionPrice: '0.0000000000124017',
  quantity: '8063409210.959469',
  fee: '0.100000000000',
  costBasis: '100.100000000000',
};

export const edgeScenarios: Scenario[] = [
  {
    intentId: 'intent-edge-1',
    sourceEventId: 'event-edge-buy-200',
    sourceSlot: 200,
    entryOffset: 1,
    targetSlot: 201,
    pair: PAIR,
    notional: '100.000000000000',
    status: 'FILLED',
    rejectionReason: null,
    fill: edgeFill,
    selectedObservation: edgeObservations[0] ?? null,
    position: { id: 'position-edge-1', state: 'OPEN', close: null },
    exitUnavailableReason: 'MISSING_OBSERVATION',
  },
  {
    intentId: 'intent-edge-2',
    sourceEventId: 'event-edge-buy-200',
    sourceSlot: 200,
    entryOffset: 2,
    targetSlot: 202,
    pair: PAIR,
    notional: '100.000000000000',
    status: 'REJECTED',
    rejectionReason: 'STALE_OBSERVATION',
    fill: null,
    selectedObservation: null,
    position: null,
    exitUnavailableReason: null,
  },
  {
    intentId: 'intent-edge-3',
    sourceEventId: 'event-edge-buy-200',
    sourceSlot: 200,
    entryOffset: 3,
    targetSlot: 203,
    pair: PAIR,
    notional: '100.000000000000',
    status: 'REJECTED',
    rejectionReason: 'INVALID_QUALITY',
    fill: null,
    selectedObservation: null,
    position: null,
    exitUnavailableReason: null,
  },
];

export const edgeRun: RunSummary = {
  id: EDGE_RUN_ID,
  datasetId: EDGE_DATASET_ID,
  engineVersion: ENGINE_VERSION,
  configurationHash: CONFIG_HASH,
  resultChecksum: '0011223344556677889900aabbccddeeff00112233445566778899aabbccddee',
  quoteMint: SOL_MINT,
  scenarioCount: 3,
  filledCount: 1,
  rejectedCount: 2,
  openCount: 1,
  closedCount: 0,
  realizedPnl: '0.000000000000',
  createdAt: '2026-10-01T18:05:00Z',
};

export const edgeRunDetail: RunDetail = { run: edgeRun, config: mvpConfig, scenarios: edgeScenarios };

function event(
  slot: number,
  index: number,
  side: 'BUY' | 'SELL' | null,
  ignoredReason: string | null,
  overrides: Partial<RunEvent> = {},
): RunEvent {
  const buy = side !== 'SELL';
  return {
    eventId: `event-sample-${slot}-${index}`,
    signature: SIGNATURE,
    instructionIndex: index,
    slot,
    blockTime: OBSERVED_AT,
    wallet: WALLET,
    pair: PAIR,
    baseDelta: buy ? '1000000000' : '-1000000000',
    quoteDelta: buy ? '-10000000000' : '11000000000',
    baseDecimals: 9,
    quoteDecimals: 9,
    successful: true,
    supportedRoute: true,
    signer: true,
    source: 'curated-fixture',
    commitment: 'CONFIRMED',
    outcome: ignoredReason ? 'IGNORED' : 'ACCEPTED',
    side: ignoredReason ? null : side,
    ignoredReason,
    ...overrides,
  };
}

export const sampleEvents: RunEvent[] = [
  { ...event(100, 0, 'BUY', null), eventId: 'event-sample-buy-100' },
  event(109, 1, 'SELL', null),
  event(110, 2, null, 'FAILED', { successful: false }),
  event(111, 3, null, 'UNSUPPORTED_ROUTE', { supportedRoute: false }),
];

export const edgeEvents: RunEvent[] = [
  { ...event(200, 0, 'BUY', null), eventId: 'event-edge-buy-200' },
];

function ordersOf(scenarios: Scenario[]): RunOrder[] {
  return scenarios.map((s) => ({
    orderId: s.fill?.orderId ?? `order-${s.intentId}`,
    intentId: s.intentId,
    entryOffset: s.entryOffset,
    targetSlot: s.targetSlot,
    status: s.status,
    rejectionReason: s.rejectionReason,
    fill: s.fill,
  }));
}

function positionsOf(scenarios: Scenario[]): RunPosition[] {
  return scenarios.flatMap((s) =>
    s.position && s.fill
      ? [
          {
            id: s.position.id,
            intentId: s.intentId,
            pair: s.pair,
            entryOffset: s.entryOffset,
            state: s.position.state,
            fill: s.fill,
            close: s.position.close,
            exitUnavailableReason: s.exitUnavailableReason,
          },
        ]
      : [],
  );
}

export interface MockRunRecords {
  detail: RunDetail;
  events: RunEvent[];
  observations: Observation[];
  orders: RunOrder[];
  positions: RunPosition[];
}

export const runRecords: Record<string, MockRunRecords> = {
  [SAMPLE_RUN_ID]: {
    detail: sampleRunDetail,
    events: sampleEvents,
    observations: sampleObservations,
    orders: ordersOf(sampleScenarios),
    positions: positionsOf(sampleScenarios),
  },
  [EDGE_RUN_ID]: {
    detail: edgeRunDetail,
    events: edgeEvents,
    observations: edgeObservations,
    orders: ordersOf(edgeScenarios),
    positions: positionsOf(edgeScenarios),
  },
};

export const datasetObservations: Record<string, Observation[]> = {
  [SAMPLE_DATASET_ID]: sampleObservations,
  [EDGE_DATASET_ID]: edgeObservations,
};

export const runForDataset: Record<string, string> = {
  [SAMPLE_DATASET_ID]: SAMPLE_RUN_ID,
  [EDGE_DATASET_ID]: EDGE_RUN_ID,
};

export const REJECTION_REASONS = [
  'MISSING_OBSERVATION',
  'STALE_OBSERVATION',
  'INVALID_QUALITY',
  'INSUFFICIENT_LIQUIDITY',
  'ALREADY_OPEN',
  'ZERO_QUANTITY',
];

export function dashboardFixture(now: Date = new Date()): Dashboard {
  return {
    generatedAt: now.toISOString(),
    health: { status: 'UP', database: 'UP' },
    runs: {
      total: 2,
      last24h: 2,
      recent: [sampleRun, edgeRun].map((r) => ({
        id: r.id,
        createdAt: r.createdAt,
        quoteMint: r.quoteMint,
        realizedPnl: r.realizedPnl,
      })),
    },
    scenarios: { filled: 4, rejected: 2 },
    positions: { open: 1, closed: 3 },
    realizedPnlByQuoteMint: [{ quoteMint: SOL_MINT, realizedPnl: SAMPLE_TOTAL_PNL }],
    rejectionsByReason: REJECTION_REASONS.map((reason) => ({
      reason,
      count: reason === 'STALE_OBSERVATION' || reason === 'INVALID_QUALITY' ? 1 : 0,
    })),
    gauges: {
      heapUsedBytes: 182_452_224 + Math.round(Math.random() * 20_000_000),
      heapMaxBytes: 4_253_024_256,
      uptimeMs: 3_725_000,
      httpRequestCount: 1_204,
      cpuUsage: 0.034,
      httpMeanLatencyMs: 12.4,
    },
  };
}

export const solanaUnconfigured: SolanaStatus = {
  configured: false,
  host: null,
  cluster: null,
  lastCheck: null,
};

/**
 * Mock data mirroring the backend's bundled dip sample (`dip-sample-v3`), engine `dip-paper-v2`. The sample run has three
 * tokens: one closes at take profit (P&L 0.028260434000), one gaps through its stop loss (-0.041748258000) and one never
 * dips, for a total of -0.013487824000, at the defaults (0.1 SOL notional, latency 1, 0.001 SOL per transaction). Each
 * fill and close is the curve's: 125 bps of fees (95 protocol + 30 creator, rounded up), our own price impact, and the
 * transaction cost on top. A second "edge" dataset/run covers a position left open at the horizon and cycles with no entry.
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
  RunUsdtView,
  Scenario,
  SolanaStatus,
  SolUsdtPrice,
} from '../api/types';

export const SOL_MINT = 'So11111111111111111111111111111111111111112';
export const BASE_MINT = '11111111111111111111111111111112';
export const STOP_LOSS_MINT = '11111111111111111111111111111113';
export const NO_DIP_MINT = '11111111111111111111111111111114';
export const WALLET = '11111111111111111111111111111111';
export const SIGNATURE = '1'.repeat(64);
export const PAIR: Pair = { base: BASE_MINT, quote: SOL_MINT };
const STOP_LOSS_PAIR: Pair = { base: STOP_LOSS_MINT, quote: SOL_MINT };
const NO_DIP_PAIR: Pair = { base: NO_DIP_MINT, quote: SOL_MINT };

export const SAMPLE_DATASET_ID = '06103b0faebf5d3e95468fc250232c333a0e6b665612c977fc76441883eac765';
export const EDGE_DATASET_ID = 'b3f1c2d4e5a6978812ab34cd56ef7890a1b2c3d4e5f60718293a4b5c6d7e8f90';
export const SAMPLE_RUN_ID = '4f0c9a7e2b1d3c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6';
export const EDGE_RUN_ID = '9a8b7c6d5e4f30211203f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f';
/** The take-profit cycle's P&L. */
export const SAMPLE_PNL = '0.028260434000';
export const STOP_LOSS_PNL = '-0.041748258000';
export const SAMPLE_TOTAL_PNL = '-0.013487824000';
export const ENGINE_VERSION = 'dip-paper-v2';
/** The take-profit cycle's entry: 100000000 lamports buy 1160584892249 raw tokens from slot 1003's curve. */
export const TAKE_PROFIT_ENTRY_PRICE = '0.00000008616345143543821057475551350438';
export const TAKE_PROFIT_QUANTITY = '1160584.892249000000';
/** Pe × 1.3 and Pe × 0.85, at the price scale. */
export const TAKE_PROFIT_LEVEL = '0.00000011201248686606967374718216755569';
export const STOP_LOSS_LEVEL = '0.00000007323893372012247898854218647872';
/** The sale of those tokens into slot 1006's curve: 133175124 lamports. */
export const TAKE_PROFIT_EXIT_PRICE = '0.00000011474828329182461687631176780630';
export const TAKE_PROFIT_NET_PROCEEDS = '0.130510434000';
/** The stop-loss cycle's gap sale of 1247541616895 raw tokens into slot 2005's curve: 62280246 lamports. */
export const STOP_LOSS_EXIT_PRICE = '0.00000004992237946739523467462528718257';
/** Every entry's fees (125 bps of 0.1 SOL), transaction cost and cost basis (notional + fees + transaction cost). */
export const ENTRY_FEE = '0.001250000000';
export const TRANSACTION_COST = '0.001000000000';
export const COST_BASIS = '0.102250000000';
export const CONFIG_HASH = 'c0ffee00112233445566778899aabbccddeeff00112233445566778899aabbcc';

const CREATED_AT = '2026-10-02T09:15:30Z';
const OBSERVED_AT = '2026-10-01T00:00:00Z';

export const sampleDataset: Dataset = {
  id: SAMPLE_DATASET_ID,
  name: 'dip-sample-v3',
  trackedWallet: WALLET,
  schemaVersion: 3,
  transactionCount: 11,
  observationCount: 31,
  firstSlot: 1000,
  lastSlot: 11501,
  pairs: [PAIR, STOP_LOSS_PAIR, NO_DIP_PAIR],
  createdAt: CREATED_AT,
};

export const edgeDataset: Dataset = {
  id: EDGE_DATASET_ID,
  name: 'edge-cases',
  trackedWallet: WALLET,
  schemaVersion: 3,
  transactionCount: 1,
  observationCount: 2,
  firstSlot: 200,
  lastSlot: 201,
  pairs: [PAIR],
  createdAt: '2026-10-01T18:02:11Z',
};

function observation(slot: number, price: string, quality = 'LAST_TRADE', pair: Pair = PAIR): Observation {
  return {
    id: `observation-${slot}`,
    pair,
    slot,
    priceSlot: slot,
    observedTime: OBSERVED_AT,
    price,
    liquidityQuote: null,
    source: 'pumpfun-bonding-curve-trade/v1',
    sourceEventId: `market-${slot}`,
    commitment: 'CONFIRMED',
    quality,
  };
}

/** Take-profit token: dip at 1002, fill at 1003, take-profit trigger at 1005 and exit at 1006. */
export const sampleObservations: Observation[] = [
  observation(1001, '0.000000095'),
  observation(1002, '0.000000084'),
  observation(1003, '0.000000086'),
  observation(1005, '0.000000113'),
  observation(1006, '0.000000115'),
  observation(2001, '0.00000008', 'LAST_TRADE', STOP_LOSS_PAIR),
  observation(2002, '0.00000008', 'LAST_TRADE', STOP_LOSS_PAIR),
  observation(2004, '0.00000006', 'LAST_TRADE', STOP_LOSS_PAIR),
  observation(2005, '0.00000005', 'LAST_TRADE', STOP_LOSS_PAIR),
  observation(3001, '0.000000095', 'LAST_TRADE', NO_DIP_PAIR),
];

export const edgeObservations: Observation[] = [
  { ...observation(201, '0.00000000001234'), liquidityQuote: '10000' },
  { ...observation(203, '0.00000000001301', 'INVALID'), priceSlot: 199, liquidityQuote: '10000' },
];

export const configDefaults: ConfigDefaults = {
  defaults: {
    strategyName: 'dip-entry',
    strategyVersion: '1',
    notional: '0.1',
    maxAgeSlots: 2,
    dipBps: 1500,
    takeProfitBps: 3000,
    stopLossBps: 1500,
    minMarketCapUsd: '10000',
    horizonSlots: 4500,
    latencySlots: 1,
    transactionCostSol: TRANSACTION_COST,
    quoteMint: SOL_MINT,
  },
  limits: {
    notionalScale: 9,
    notionalPrecision: 35,
    maxAgeSlots: { min: 0, max: 1000 },
    dipBps: { min: 1, max: 9999 },
    takeProfitBps: { min: 1, max: 9999 },
    stopLossBps: { min: 1, max: 9999 },
    minMarketCapUsd: { min: 0, max: 1000000000 },
    horizonSlots: { min: 1, max: 4500 },
    latencySlots: { min: 1, max: 150 },
    transactionCostScale: 12,
    transactionCostSol: { min: '0', max: '0.1' },
  },
};

const mvpConfig: Config = configDefaults.defaults;

/** A 0.1 SOL entry at the defaults: 125 bps of fees and the 0.001 SOL transaction cost on top. */
function fill(id: string, dipSlot: number, slot: number, executionPrice: string, quantity: string, priceImpactBps: number): Fill {
  return {
    orderId: `order-${id}`,
    targetSlot: dipSlot,
    actualSlot: slot,
    observationId: `observation-${slot}`,
    executionPrice,
    quantity,
    fee: ENTRY_FEE,
    costBasis: COST_BASIS,
    feeBps: 125,
    slippageBps: 0,
    priceSource: 'pumpfun-bonding-curve-trade/v1',
    priceAssumption: 'LAST_TRADE',
    priorityFee: TRANSACTION_COST,
    priceImpactBps,
  };
}

const takeProfitFill = fill('sample-tp', 1002, 1003, TAKE_PROFIT_ENTRY_PRICE, TAKE_PROFIT_QUANTITY, 19);
/** Gross 0.133175124 less 0.00166469 in fees and the transaction cost; P&L = net - cost basis. */
const takeProfitClose: Close = {
  slot: 1006,
  observationId: 'observation-1006',
  executionPrice: TAKE_PROFIT_EXIT_PRICE,
  grossProceeds: '0.133175124000',
  fee: '0.001664690000',
  feeBps: 125,
  transactionCost: TRANSACTION_COST,
  priceImpactBps: -22,
  netProceeds: TAKE_PROFIT_NET_PROCEEDS,
  realizedPnl: SAMPLE_PNL,
  reason: 'TAKE_PROFIT',
};
const stopLossFill = fill('sample-sl', 2001, 2002, '0.00000008015764656323810068866023294541', '1247541.616895000000', 20);
const stopLossClose: Close = {
  slot: 2005,
  observationId: 'observation-2005',
  executionPrice: STOP_LOSS_EXIT_PRICE,
  grossProceeds: '0.062280246000',
  fee: '0.000778504000',
  feeBps: 125,
  transactionCost: TRANSACTION_COST,
  priceImpactBps: -16,
  netProceeds: '0.060501742000',
  realizedPnl: STOP_LOSS_PNL,
  reason: 'STOP_LOSS',
};

function cycle(id: string, sourceSlot: number, pair: Pair, overrides: Partial<Scenario>): Scenario {
  return {
    intentId: `intent-${id}`,
    sourceEventId: `event-${id}-buy-${sourceSlot}`,
    sourceSlot,
    entryOffset: 0,
    targetSlot: sourceSlot + 1,
    pair,
    notional: '0.100000000000',
    referencePrice: '0.0000001',
    dipPrice: '0.000000085',
    horizonEndSlot: sourceSlot + 4500,
    watch: 'NO_DIP',
    trigger: null,
    status: 'REJECTED',
    rejectionReason: 'NO_DIP',
    fill: null,
    selectedObservation: null,
    position: null,
    exitUnavailableReason: null,
    ...overrides,
  };
}

export const sampleScenarios: Scenario[] = [
  cycle('sample-tp', 1000, PAIR, {
    targetSlot: 1002,
    watch: 'TRIGGERED',
    trigger: { observationId: 'observation-1002', slot: 1002, price: '0.000000084', marketCapUsd: '16800.000000000000', rate: '200' },
    status: 'FILLED',
    rejectionReason: null,
    fill: takeProfitFill,
    selectedObservation: sampleObservations[2] ?? null,
    position: {
      id: 'position-sample-tp',
      state: 'CLOSED',
      close: takeProfitClose,
      takeProfitPrice: TAKE_PROFIT_LEVEL,
      stopLossPrice: STOP_LOSS_LEVEL,
      exitTrigger: { observationId: 'observation-1005', slot: 1005, reason: 'TAKE_PROFIT' },
    },
  }),
  cycle('sample-sl', 2000, STOP_LOSS_PAIR, {
    targetSlot: 2001,
    watch: 'TRIGGERED',
    trigger: { observationId: 'observation-2001', slot: 2001, price: '0.00000008', marketCapUsd: '16000.000000000000', rate: '200' },
    status: 'FILLED',
    rejectionReason: null,
    fill: stopLossFill,
    selectedObservation: sampleObservations[6] ?? null,
    position: {
      id: 'position-sample-sl',
      state: 'CLOSED',
      close: stopLossClose,
      takeProfitPrice: '0.00000010420494053220953089525830282903',
      stopLossPrice: '0.00000006813399957875238558536119800360',
      exitTrigger: { observationId: 'observation-2004', slot: 2004, reason: 'STOP_LOSS' },
    },
  }),
  cycle('sample-nodip', 3000, NO_DIP_PAIR, {}),
];

export const sampleRun: RunSummary = {
  id: SAMPLE_RUN_ID,
  datasetId: SAMPLE_DATASET_ID,
  engineVersion: ENGINE_VERSION,
  configurationHash: CONFIG_HASH,
  resultChecksum: 'aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899',
  quoteMint: SOL_MINT,
  scenarioCount: 3,
  filledCount: 2,
  rejectedCount: 1,
  openCount: 0,
  closedCount: 2,
  realizedPnl: SAMPLE_TOTAL_PNL,
  createdAt: '2026-10-02T09:20:00Z',
};

export const sampleRunDetail: RunDetail = {
  run: sampleRun,
  config: mvpConfig,
  scenarios: sampleScenarios,
};

const edgeFill: Fill = {
  ...fill('edge-1', 200, 201, '0.0000000000124017', '8063409210.959469', 1),
};

export const edgeScenarios: Scenario[] = [
  cycle('edge-1', 199, PAIR, {
    sourceEventId: 'event-edge-buy-200',
    targetSlot: 200,
    referencePrice: '0.0000000000145',
    dipPrice: '0.000000000012325',
    watch: 'TRIGGERED',
    trigger: { observationId: 'observation-200', slot: 200, price: '0.00000000001234', marketCapUsd: '12340.000000000000', rate: '1000000' },
    status: 'FILLED',
    rejectionReason: null,
    fill: edgeFill,
    selectedObservation: edgeObservations[0] ?? null,
    position: {
      id: 'position-edge-1',
      state: 'OPEN',
      close: null,
      takeProfitPrice: '0.00000000001612221',
      stopLossPrice: '0.000000000010541445',
      exitTrigger: null,
    },
    exitUnavailableReason: 'HORIZON_REACHED',
  }),
  cycle('edge-2', 199, { base: STOP_LOSS_MINT, quote: SOL_MINT }, {
    sourceEventId: 'event-edge-buy-200',
    watch: 'DIP_BELOW_MIN_MARKET_CAP',
    rejectionReason: 'DIP_BELOW_MIN_MARKET_CAP',
  }),
  cycle('edge-3', 199, { base: NO_DIP_MINT, quote: SOL_MINT }, {
    sourceEventId: 'event-edge-buy-200',
    watch: 'MIGRATED_BEFORE_ENTRY',
    rejectionReason: 'MIGRATED_BEFORE_ENTRY',
  }),
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
    baseDelta: buy ? '1000000000000' : '-1000000000000',
    quoteDelta: buy ? '-100000000' : '110000000',
    baseDecimals: 6,
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
  { ...event(1000, 0, 'BUY', null), eventId: 'event-sample-tp-buy-1000' },
  event(1004, 0, null, 'LATER_BUY_SAME_MINT'),
  { ...event(2000, 0, 'BUY', null, { pair: STOP_LOSS_PAIR }), eventId: 'event-sample-sl-buy-2000' },
  { ...event(3000, 0, 'BUY', null, { pair: NO_DIP_PAIR }), eventId: 'event-sample-nodip-buy-3000' },
  event(3100, 1, null, 'FAILED', { successful: false }),
];

export const edgeEvents: RunEvent[] = [
  { ...event(199, 0, 'BUY', null), eventId: 'event-edge-buy-200' },
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
            takeProfitPrice: s.position.takeProfitPrice,
            stopLossPrice: s.position.stopLossPrice,
            exitTrigger: s.position.exitTrigger,
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

/**
 * A run over a recorded Pump.fun capture: real public signatures and mint, so market links apply. Its one cycle reuses the
 * sample's take-profit economics; only identities differ. It is served by id but not listed, so list and dashboard counts
 * stay unchanged.
 */
export const REAL_RUN_ID = '0bad5eed1234567890abcdef1234567890abcdef1234567890abcdef12345678';
export const REAL_BUY_SIGNATURE = '35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA';
export const REAL_MINT = '5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs';
/** The dip trade, then the entry fill. */
export const REAL_FILL_SIGNATURES = [
  '2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM',
  '3dGw1o8DFaMd1SMLz7PA9uWm54m9ByaKNLAH35m26owRWiDDWQKAdE1siqiLtUXnW3Dj4AsGrU1cKR8UjH21qYM6',
];
/** The take-profit trigger, then the exit fill. */
export const REAL_EXIT_SIGNATURES = [
  '42ZQzji2uBf1KsTGsgb98XtNFzmCyGxLekWZcWMjQzvmBR15BUJ3BRbm7v3W5mpG4FoxjG2wPqdFmgj18Tow5tr4',
  '3j8c4wYcaRvusJSzUuzmXbb8ox7TcVpceqD7UHu8ntarADjA5u19S1kmw7akD5MRXvNy4xsNBSnDcqiBFRuUDZZu',
];
export const USDT_PNL = '5.652086800000';
export const USDT_TOTAL_PNL = '5.652086800000';
/** The take-profit cycle at 200 USDT per SOL: amounts at scale 12, prices at the price scale. */
export const USDT_COST_BASIS = '20.450000000000';
export const USDT_TRANSACTION_COST = '0.200000000000';
export const USDT_ENTRY_PRICE = '0.00001723269028708764211495110270087600';
export const USDT_NET_PROCEEDS = '26.102086800000';
const REAL_PAIR: Pair = { base: REAL_MINT, quote: SOL_MINT };
const REAL_SOURCE_EVENT = `${REAL_BUY_SIGNATURE}:1`;
const MINUTE = '2026-10-01T00:00:00Z';

function tradeId(signature: string): string {
  return `pumpfun-bonding-curve-trade/v1:${signature}:1:7:${REAL_MINT}`;
}

const REAL_TRADES = [...REAL_FILL_SIGNATURES, ...REAL_EXIT_SIGNATURES];
const realObservations: Observation[] = REAL_TRADES.map((signature, index) => ({
  ...(sampleObservations[index + 1] as Observation),
  id: tradeId(signature),
  pair: REAL_PAIR,
  sourceEventId: `${signature}:1:7`,
}));

const sampleTakeProfit = sampleScenarios[0] as Scenario;
const realScenarios: Scenario[] = [
  {
    ...sampleTakeProfit,
    intentId: 'intent-real',
    sourceEventId: REAL_SOURCE_EVENT,
    pair: REAL_PAIR,
    trigger: { ...sampleTakeProfit.trigger!, observationId: tradeId(REAL_FILL_SIGNATURES[0]!) },
    fill: { ...(sampleTakeProfit.fill as Fill), observationId: tradeId(REAL_FILL_SIGNATURES[1]!) },
    selectedObservation: realObservations[1] ?? null,
    position: {
      ...(sampleTakeProfit.position as NonNullable<Scenario['position']>),
      exitTrigger: { observationId: tradeId(REAL_EXIT_SIGNATURES[0]!), slot: 1005, reason: 'TAKE_PROFIT' },
      close: { ...takeProfitClose, observationId: tradeId(REAL_EXIT_SIGNATURES[1]!) },
    },
  },
];

export const realRunDetail: RunDetail = {
  run: {
    ...sampleRun,
    id: REAL_RUN_ID,
    datasetId: '37c664414b32697c7718ea082d49b7613bcc69ad1d862edc8ba35120636fa4a4',
    scenarioCount: 1,
    filledCount: 1,
    rejectedCount: 0,
    closedCount: 1,
    realizedPnl: SAMPLE_PNL,
  },
  config: mvpConfig,
  scenarios: realScenarios,
};

/** Not listed by `/api/runs`; served only by id. */
export const recordedRunRecords: Record<string, MockRunRecords> = {
  [REAL_RUN_ID]: {
    detail: realRunDetail,
    events: [
      {
        ...event(1000, 1, 'BUY', null),
        eventId: REAL_SOURCE_EVENT,
        signature: REAL_BUY_SIGNATURE,
        pair: REAL_PAIR,
        source: 'pumpfun-bonding-curve-buy/v1',
      },
    ],
    observations: realObservations,
    orders: ordersOf(realScenarios),
    positions: positionsOf(realScenarios),
  },
};

/** The real trades' prices at 200 USDT per SOL, at the price scale. */
const REAL_PRICES_USDT = ['0.0000168', '0.0000172', '0.0000226', '0.000023'].map((price) => price.padEnd(40, '0'));

/** The real run at a rate of 200 USDT per SOL; every other run is reported as not configured. */
export const realRunUsdt: RunUsdtView = {
  runId: REAL_RUN_ID,
  rateSource: 'binance:SOLUSDT:1m:open',
  unavailableReason: null,
  observations: realObservations.map((o, index) => ({
    id: o.id,
    minute: MINUTE,
    rate: '200',
    priceUsdt: REAL_PRICES_USDT[index] ?? null,
    unavailableReason: null,
  })),
  scenarios: realScenarios.map((s) => ({
    intentId: s.intentId,
    fill: {
      minute: MINUTE,
      rate: '200',
      executionPriceUsdt: USDT_ENTRY_PRICE,
      costBasisUsdt: USDT_COST_BASIS,
      feeUsdt: '0.250000000000',
      priorityFeeUsdt: USDT_TRANSACTION_COST,
      unavailableReason: null,
    },
    close: {
      minute: MINUTE,
      rate: '200',
      executionPriceUsdt: '0.00002294965665836492337526235356126000',
      grossProceedsUsdt: '26.635024800000',
      feeUsdt: '0.332938000000',
      transactionCostUsdt: USDT_TRANSACTION_COST,
      netProceedsUsdt: USDT_NET_PROCEEDS,
      realizedPnlUsdt: USDT_PNL,
      unavailableReason: null,
    },
  })),
  realizedPnlUsdt: USDT_TOTAL_PNL,
  realizedPnlUnavailableReason: null,
};

export const solUsdtPrice: SolUsdtPrice = {
  rate: '212.34000000',
  asOf: '2026-10-05T12:00:00Z',
  source: 'binance:SOLUSDT:ticker',
  unavailableReason: null,
};

export const runForDataset: Record<string, string> = {
  [SAMPLE_DATASET_ID]: SAMPLE_RUN_ID,
  [EDGE_DATASET_ID]: EDGE_RUN_ID,
};

export const REJECTION_REASONS = [
  'NO_DIP',
  'DIP_BELOW_MIN_MARKET_CAP',
  'MIGRATED_BEFORE_ENTRY',
  'UNSUPPORTED_SUPPLY',
  'NO_FILL_AFTER_TRIGGER',
  'EXCEEDS_CURVE',
  'INVALID_QUALITY',
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
    scenarios: { filled: 3, rejected: 3 },
    positions: { open: 1, closed: 2 },
    realizedPnlByQuoteMint: [{ quoteMint: SOL_MINT, realizedPnl: SAMPLE_TOTAL_PNL }],
    rejectionsByReason: REJECTION_REASONS.map((reason) => ({
      reason,
      count: reason === 'NO_DIP' || reason === 'DIP_BELOW_MIN_MARKET_CAP' || reason === 'MIGRATED_BEFORE_ENTRY' ? 1 : 0,
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

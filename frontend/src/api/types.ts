/**
 * Hand-written mirror of the backend `/api` JSON (console module DTOs).
 *
 * Decimal amounts (prices, P&L, fees, quantities, notional, liquidity, raw token amounts) are
 * `DecimalString`: exact decimal strings, never JSON numbers. Slots and counts are JSON numbers.
 * Timestamps are ISO-8601 UTC strings.
 */

/** An exact decimal value as sent by the backend, e.g. "8.696567164180". Never parse for display. */
export type DecimalString = string;
/** ISO-8601 UTC timestamp, e.g. "2026-09-23T00:00:00Z". */
export type IsoInstant = string;

export interface ApiErrorBody {
  code: ApiErrorCode | string;
  message: string;
  fields: Record<string, string>;
  suggestion?: string;
  retryAfterSeconds?: number;
}

export type ApiErrorCode =
  | 'DATASET_INVALID'
  | 'DATASET_NOT_FOUND'
  | 'SAMPLE_NOT_FOUND'
  | 'RUN_NOT_FOUND'
  | 'RUN_CONFLICT'
  | 'CONFIG_INVALID'
  | 'INVALID_REQUEST'
  | 'UPLOAD_TOO_LARGE'
  | 'INVALID_ADDRESS'
  | 'SOLANA_NOT_CONFIGURED';

export interface Page<T> {
  items: T[];
  /** 0-based. */
  page: number;
  size: number;
  total: number;
}

export interface Pair {
  base: string;
  quote: string;
}

export interface Dataset {
  source?: DatasetSource;
  id: string;
  name: string;
  trackedWallet: string;
  schemaVersion: number;
  transactionCount: number;
  observationCount: number;
  firstSlot: number | null;
  lastSlot: number | null;
  pairs: Pair[];
  createdAt: IsoInstant;
}

export interface DatasetRegistration {
  dataset: Dataset;
  created: boolean;
}

export type ObservationQuality = 'LAST_TRADE' | 'MIDPOINT' | 'INVALID';

export interface Observation {
  id: string;
  pair: Pair;
  slot: number;
  priceSlot: number;
  observedTime: IsoInstant;
  price: DecimalString;
  liquidityQuote: DecimalString | null;
  source: string;
  sourceEventId: string;
  commitment: string;
  quality: ObservationQuality | string;
  /** Absent in older recordings; null when this observation has no captured trade. */
  trade?: ObservationTrade | null;
}

export interface ObservationTrade {
  trader: string;
  side: 'BUY' | 'SELL';
  solLamports: DecimalString;
  tokenRaw: DecimalString;
}

export interface ChartRequest {
  pair?: string | null;
  from?: IsoInstant;
  to?: IsoInstant;
  maxBuckets?: number;
  markerLimit?: number;
  wallet?: string[];
  /** Opaque backend token. */
  cursor?: string;
}

export interface ChartOhlc {
  open: DecimalString;
  high: DecimalString;
  low: DecimalString;
  close: DecimalString;
}

export interface ChartCandle {
  bucketStart: IsoInstant;
  observationCount: number;
  price: ChartOhlc;
  mcap: ChartOhlc | null;
  mcapMissingPoints: number;
  mcapUnsupportedPoints: number;
}

export interface ObservedChartMarker extends ObservationTrade {
  kind: 'OBSERVED';
  observationId: string;
  isTrackedWallet: boolean;
  price: DecimalString;
  mcap: DecimalString | null;
  slot: number;
  time: IsoInstant;
  signature: string | null;
  source: string;
}

export interface PaperChartFill {
  kind: 'PAPER';
  phase: 'ENTRY' | 'EXIT';
  side: 'BUY' | 'SELL';
  intentId: string;
  slot: number;
  time: IsoInstant;
  price: DecimalString;
  mcap: DecimalString | null;
  quantity: DecimalString;
  reason: string | null;
}

export interface ChartCluster {
  bucketStart: IsoInstant;
  count: number;
  buyCount: number;
  sellCount: number;
  firstSlot: number;
  lastSlot: number;
  firstTime: IsoInstant;
  lastTime: IsoInstant;
}

export interface ChartResponse {
  extent?: {
    firstTime: IsoInstant;
    lastTime: IsoInstant;
    firstSlot: number;
    lastSlot: number;
  } | null;
  bucketSeconds: number;
  range: { from: IsoInstant; to: IsoInstant };
  trackedWallet: string | null;
  valuationBasis: {
    label: string;
    supply: DecimalString | null;
    supplySource: string | null;
    quoteCurrency: 'USDT';
    rateSource: string | null;
    rateGranularity: string | null;
    available: boolean;
    unavailableReason: 'UNSUPPORTED_TOKEN' | 'NOT_SOL_QUOTED' | 'RATES_UNAVAILABLE' | null;
  };
  candles: ChartCandle[];
  markers: {
    available: boolean;
    unavailableReason: 'NOT_RECORDED_BEFORE_SCHEMA_V4' | null;
    mode: 'ITEMS' | 'CLUSTERED';
    total: number;
    items: ObservedChartMarker[];
    nextCursor: string | null;
    clusters: ChartCluster[];
  };
  paperFills: PaperChartFill[];
}

export interface Config {
  strategyName: string;
  strategyVersion: string;
  notional: DecimalString;
  maxAgeSlots: number;
  /** Entry once a trade is this far below the wallet's first price P0. */
  dipBps: number;
  dipPercent?: DecimalString;
  takeProfitBps: number;
  takeProfitPercent?: DecimalString;
  stopLossBps: number;
  stopLossPercent?: DecimalString;
  /** Plain decimal US dollars. */
  minMarketCapUsd: DecimalString;
  /** Slots after the wallet's first buy that are watched; about 0.4 s each. */
  horizonSlots: number;
  /** Slots between a trigger and the trade that fills it; 1 fills at the next slot. */
  latencySlots: number;
  /** SOL charged per transaction (network plus priority fee or tip), on entry and on exit. */
  transactionCostSol: DecimalString;
  quoteMint: string;
}

/** Inclusive range; a null `max` is unbounded above. */
export interface Range {
  min: number;
  max: number | null;
}

/** Inclusive decimal range, as exact decimal strings. */
export interface DecimalRange {
  min: DecimalString;
  max: DecimalString;
}

/**
 * A notional has at most `notionalScale` decimals (whole lamports; never rounded) and `notionalPrecision` digits at that
 * scale; a transaction cost has at most `transactionCostScale` decimals.
 */
export interface ConfigLimits {
  dipPercent?: DecimalRange;
  takeProfitPercent?: DecimalRange;
  stopLossPercent?: DecimalRange;
  notionalScale: number;
  notionalPrecision: number;
  maxAgeSlots: Range;
  dipBps: Range;
  takeProfitBps: Range;
  stopLossBps: Range;
  minMarketCapUsd: Range;
  horizonSlots: Range;
  latencySlots: Range;
  transactionCostScale: number;
  transactionCostSol: DecimalRange;
}

export interface ConfigDefaults {
  defaults: Config;
  limits: ConfigLimits;
}

/** Body of `POST /api/runs`; a missing config (or field) uses the MVP default. */
export interface RunStartRequest {
  presetId?: string;
  datasetId: string;
  config?: {
    /** Plain decimal string; the backend rejects a JSON number. */
    notional?: DecimalString;
    maxAgeSlots?: number;
    dipBps?: number;
    dipPercent?: DecimalString;
    takeProfitBps?: number;
    takeProfitPercent?: DecimalString;
    stopLossBps?: number;
    stopLossPercent?: DecimalString;
    /** Plain decimal string, like notional. */
    minMarketCapUsd?: DecimalString;
    horizonSlots?: number;
    latencySlots?: number;
    /** Plain decimal string, like notional. */
    transactionCostSol?: DecimalString;
    quoteMint?: string;
  };
}

export interface RunSummary {
  id: string;
  datasetId: string;
  engineVersion: string;
  configurationHash: string;
  resultChecksum: string;
  quoteMint: string;
  scenarioCount: number;
  filledCount: number;
  rejectedCount: number;
  openCount: number;
  closedCount: number;
  /** Exact sum over closed positions, in units of `quoteMint`. */
  realizedPnl: DecimalString;
  createdAt: IsoInstant;
}

export interface RunStart {
  run: RunSummary;
  created: boolean;
}

/**
 * An entry on the bonding curve. `fee` is the protocol and creator fees, charged at `feeBps`; `priorityFee` is the
 * configured transaction cost; `priceImpactBps` is the buy's move against the curve (positive); `slippageBps` is always 0.
 * `costBasis` = notional + fee + priorityFee.
 */
export interface Fill {
  feePercent?: DecimalString;
  priceImpactPercent?: DecimalString;
  orderId: string;
  targetSlot: number;
  actualSlot: number;
  observationId: string;
  executionPrice: DecimalString;
  quantity: DecimalString;
  fee: DecimalString;
  costBasis: DecimalString;
  feeBps: number;
  slippageBps: number;
  priceSource: string;
  priceAssumption: string;
  priorityFee: DecimalString;
  priceImpactBps: number;
}

/** An exit: `netProceeds` = `grossProceeds` − `fee` (at `feeBps`) − `transactionCost`; `priceImpactBps` is negative for the sale. */
export interface Close {
  feePercent?: DecimalString;
  priceImpactPercent?: DecimalString;
  slot: number;
  observationId: string;
  executionPrice: DecimalString;
  grossProceeds: DecimalString;
  fee: DecimalString;
  feeBps: number;
  transactionCost: DecimalString;
  priceImpactBps: number;
  netProceeds: DecimalString;
  realizedPnl: DecimalString;
  reason: string;
}

export type ScenarioStatus = 'FILLED' | 'REJECTED';
export type PositionState = 'OPEN' | 'CLOSED';

/** The trade that crossed take profit or stop loss; the exit fills at a later slot's trade, if any. */
export interface ExitTrigger {
  observationId: string;
  slot: number;
  reason: string;
}

export interface ScenarioPosition {
  id: string;
  state: PositionState;
  close: Close | null;
  takeProfitPrice: DecimalString;
  stopLossPrice: DecimalString;
  exitTrigger: ExitTrigger | null;
}

/** The trade that reached the dip level, with the market cap and SOL/USDT rate it was judged at. */
export interface DipTrigger {
  observationId: string;
  slot: number;
  price: DecimalString;
  marketCapUsd: DecimalString;
  rate: DecimalString;
}

/** One dip-entry cycle: the tracked wallet's first buy of a token, then the dip entry, if any, and its exit. */
export interface Scenario {
  timeline?: {
    firstBuyAt: IsoInstant | null;
    dipAt: IsoInstant | null;
    entryAt: IsoInstant | null;
    exitTriggerAt: IsoInstant | null;
    exitAt: IsoInstant | null;
  };
  intentId: string;
  sourceEventId: string;
  sourceSlot: number;
  entryOffset: number;
  targetSlot: number;
  pair: Pair;
  notional: DecimalString;
  /** P0: the wallet's own price on its first buy. */
  referencePrice: DecimalString;
  dipPrice: DecimalString;
  horizonEndSlot: number;
  /** TRIGGERED, NO_DIP, DIP_BELOW_MIN_MARKET_CAP, MIGRATED_BEFORE_ENTRY or UNSUPPORTED_SUPPLY. */
  watch: string;
  trigger: DipTrigger | null;
  status: ScenarioStatus;
  rejectionReason: string | null;
  fill: Fill | null;
  selectedObservation: Observation | null;
  position: ScenarioPosition | null;
  exitUnavailableReason: string | null;
}

export interface RunDetail {
  source?: DatasetSource;
  run: RunSummary;
  config: Config;
  scenarios: Scenario[];
}

export interface DatasetSource {
  datasetId: string;
  datasetName: string | null;
  captureId: string | null;
  captureDisplayName: string | null;
  caseId: string | null;
}

/** A run's values in USDT for display. Each converted value is null when its rate is unavailable, with the reason. */
export interface RunUsdtView {
  runId: string;
  rateSource: string | null;
  /** Set when nothing could be converted, e.g. NOT_CONFIGURED or NOT_SOL_QUOTED. */
  unavailableReason: string | null;
  observations: UsdtObservation[];
  scenarios: UsdtScenario[];
  realizedPnlUsdt: DecimalString | null;
  realizedPnlUnavailableReason: string | null;
}

export interface UsdtObservation {
  id: string;
  minute: IsoInstant;
  rate: DecimalString | null;
  priceUsdt: DecimalString | null;
  unavailableReason: string | null;
}

export interface UsdtScenario {
  intentId: string;
  fill: UsdtFill | null;
  close: UsdtClose | null;
}

export interface UsdtFill {
  minute: IsoInstant;
  rate: DecimalString | null;
  executionPriceUsdt: DecimalString | null;
  costBasisUsdt: DecimalString | null;
  feeUsdt: DecimalString | null;
  priorityFeeUsdt: DecimalString | null;
  unavailableReason: string | null;
}

export interface UsdtClose {
  minute: IsoInstant;
  rate: DecimalString | null;
  executionPriceUsdt: DecimalString | null;
  grossProceedsUsdt: DecimalString | null;
  feeUsdt: DecimalString | null;
  transactionCostUsdt: DecimalString | null;
  netProceedsUsdt: DecimalString | null;
  realizedPnlUsdt: DecimalString | null;
  unavailableReason: string | null;
}

export interface SolUsdtPrice {
  rate: DecimalString | null;
  asOf: IsoInstant | null;
  source: string | null;
  unavailableReason: string | null;
}

export interface RunEvent {
  eventId: string;
  signature: string;
  instructionIndex: number;
  slot: number;
  blockTime: IsoInstant | null;
  wallet: string;
  pair: Pair;
  baseDelta: DecimalString;
  quoteDelta: DecimalString;
  baseDecimals: number;
  quoteDecimals: number;
  successful: boolean;
  supportedRoute: boolean;
  signer: boolean;
  source: string;
  commitment: string;
  outcome: 'ACCEPTED' | 'IGNORED';
  side: 'BUY' | 'SELL' | null;
  ignoredReason: string | null;
}

export interface RunOrder {
  orderId: string;
  intentId: string;
  entryOffset: number;
  targetSlot: number;
  status: ScenarioStatus;
  rejectionReason: string | null;
  fill: Fill | null;
}

export interface RunPosition {
  id: string;
  intentId: string;
  pair: Pair;
  entryOffset: number;
  state: PositionState;
  fill: Fill;
  close: Close | null;
  exitUnavailableReason: string | null;
  takeProfitPrice: DecimalString;
  stopLossPrice: DecimalString;
  exitTrigger: ExitTrigger | null;
}

export interface RecentRun {
  id: string;
  createdAt: IsoInstant;
  quoteMint: string;
  realizedPnl: DecimalString;
}

export interface QuoteMintPnl {
  quoteMint: string;
  realizedPnl: DecimalString;
}

export interface RejectionCount {
  reason: string;
  count: number;
}

/** Telemetry, not money: plain numbers. Each may be null when its meter is unavailable. */
export interface Gauges {
  heapUsedBytes: number | null;
  heapMaxBytes: number | null;
  uptimeMs: number | null;
  httpRequestCount: number | null;
  /** 0..1 */
  cpuUsage: number | null;
  httpMeanLatencyMs: number | null;
}

/**
 * `GET /api/dashboard`. `runs.last24h`, `scenarios`, `positions` and `realizedPnlByQuoteMint` are
 * newer additions, so they are optional here and the UI copes with their absence.
 */
export interface Dashboard {
  generatedAt: IsoInstant;
  health: { status: string; database: string | null };
  runs: { total: number; last24h?: number; recent: RecentRun[] };
  scenarios?: { filled: number; rejected: number };
  positions?: { open: number; closed: number };
  realizedPnlByQuoteMint?: QuoteMintPnl[];
  rejectionsByReason: RejectionCount[];
  gauges: Gauges;
}

export type SolanaCheckOutcome =
  'OK' | 'NOT_CONFIGURED' | 'RATE_LIMITED' | 'TIMEOUT' | 'RPC_ERROR' | 'UNAVAILABLE';

export type SolanaClusterName = 'devnet' | 'testnet' | 'mainnet-beta' | 'unknown';

export interface SolanaCheck {
  outcome: SolanaCheckOutcome;
  cached: boolean;
  checkedAt: IsoInstant;
  latencyMs: number | null;
  version: string | null;
  slot: number | null;
  cluster: SolanaClusterName | null;
  rpcErrorCode: number | null;
  message: string | null;
}

export interface SolanaStatus {
  configured: boolean;
  /** Host only; the RPC URL is a secret and never returned. */
  host: string | null;
  cluster: SolanaClusterName | string | null;
  lastCheck: SolanaCheck | null;
}

export type AddressCheckOutcome =
  | 'CHECKED'
  | 'NO_SUCCESSFUL_TRANSACTION'
  | 'RATE_LIMITED'
  | 'TIMEOUT'
  | 'RPC_ERROR'
  | 'UNAVAILABLE'
  | 'NOT_CONFIGURED';

export interface AddressCheck {
  address: string;
  outcome: AddressCheckOutcome;
  cached: boolean;
  checkedAt: IsoInstant;
  signature: string | null;
  slot: number | null;
  transactionVersion: 'legacy' | '0' | '1' | null;
  presentFields: string[];
  missingFields: string[];
  signaturesExamined: number | null;
  rpcErrorCode: number | null;
  message: string | null;
}

/** `GET /actuator/health` (details may be hidden). */
export interface ActuatorHealth {
  status: string;
}

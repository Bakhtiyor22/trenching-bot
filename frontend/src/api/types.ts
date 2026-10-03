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
}

export interface Config {
  strategyName: string;
  strategyVersion: string;
  notional: DecimalString;
  feeBps: number;
  slippageBps: number;
  maxWaitSlots: number;
  maxAgeSlots: number;
  holdingSlots: number;
  quoteMint: string;
}

/** Inclusive range; a null `max` is unbounded above. */
export interface Range {
  min: number;
  max: number | null;
}

export interface ConfigLimits {
  notionalScale: number;
  notionalPrecision: number;
  feeBps: Range;
  slippageBps: Range;
  maxWaitSlots: Range;
  maxAgeSlots: Range;
  holdingSlots: Range;
}

export interface ConfigDefaults {
  defaults: Config;
  limits: ConfigLimits;
}

/** Body of `POST /api/runs`; a missing config (or field) uses the MVP default. */
export interface RunStartRequest {
  datasetId: string;
  config?: {
    /** Plain decimal string; the backend rejects a JSON number. */
    notional?: DecimalString;
    feeBps?: number;
    slippageBps?: number;
    maxWaitSlots?: number;
    maxAgeSlots?: number;
    holdingSlots?: number;
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

export interface Fill {
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

export interface Close {
  slot: number;
  observationId: string;
  executionPrice: DecimalString;
  grossProceeds: DecimalString;
  fee: DecimalString;
  netProceeds: DecimalString;
  realizedPnl: DecimalString;
  reason: string;
}

export type ScenarioStatus = 'FILLED' | 'REJECTED';
export type PositionState = 'OPEN' | 'CLOSED';

export interface ScenarioPosition {
  id: string;
  state: PositionState;
  close: Close | null;
}

export interface Scenario {
  intentId: string;
  sourceEventId: string;
  sourceSlot: number;
  entryOffset: number;
  targetSlot: number;
  pair: Pair;
  notional: DecimalString;
  status: ScenarioStatus;
  rejectionReason: string | null;
  fill: Fill | null;
  selectedObservation: Observation | null;
  position: ScenarioPosition | null;
  exitUnavailableReason: string | null;
}

export interface RunDetail {
  run: RunSummary;
  config: Config;
  scenarios: Scenario[];
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
  | 'OK'
  | 'NOT_CONFIGURED'
  | 'RATE_LIMITED'
  | 'TIMEOUT'
  | 'RPC_ERROR'
  | 'UNAVAILABLE';

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

import { createSetupHandlers, withRunSetup, datasetSource, runPreset } from './setupHandlers';
import { mockState } from './previewState';
import { delay, http, HttpResponse } from 'msw';
import type {
  AddressCheck,
  ApiErrorBody,
  ChartCandle,
  ChartResponse,
  Dataset,
  Observation,
  Range,
  RunStartRequest,
  SolanaCheck,
  SolanaStatus,
} from '../api/types';
import { pairKey, SAMPLE_DATASET } from '../api/endpoints';
import { base58ByteLength } from '../lib/base58';
import { SELL_CHECK_NEEDS_TRADES, SIGNAL_SIZE_NEEDS_TRADES } from '../lib/codes';
import { recordsTrades, TRADES_SCHEMA_VERSION } from '../lib/datasetSchema';
import { compareDecimals, decimalSign } from '../lib/decimal';
import { bpsToPercent } from '../lib/percent';
import { createCaptureHandlers, resetCaptureMocks } from './captureHandlers';
import { createAutomationHandlers, resetAutomationMocks } from './automationHandlers';
import {
  configDefaults,
  dashboardFixture,
  datasetObservations,
  edgeDataset,
  expandedRunChart,
  firstSlotGapChart,
  realRunUsdt,
  REAL_RUN_ID,
  recordedRunRecords,
  runForDataset,
  runRecords,
  SAMPLE_DATASET_ID,
  sampleDataset,
  solanaUnconfigured,
  solUsdtPrice,
  v3Chart,
  v4Chart,
  v4Dataset,
  v4Observations,
  V4_DATASET_ID,
} from './fixtures';

const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
/** `ReplayDatasetCatalogService.MAX_NAME_LENGTH`. */
const MAX_DATASET_NAME_LENGTH = 128;
/** `ReplayDataset.SUPPORTED_SCHEMA_VERSIONS`. */
const SUPPORTED_SCHEMA_VERSIONS = [3, 4, 5, 6];

export interface MockOptions {
  /** Pretend `SOLANA_RPC_URL` is set (devnet). Default false, like a fresh local setup. */
  solanaConfigured?: boolean;
  /** Artificial latency in ms, for exploring loading states in the browser. */
  latencyMs?: number;
}

interface MockDb {
  datasets: Dataset[];
  startedRuns: Set<string>;
  solanaLastCheck: SolanaCheck | null;
}

function freshDb(): MockDb {
  return {
    datasets: [sampleDataset, edgeDataset],
    startedRuns: new Set(Object.keys(runRecords)),
    solanaLastCheck: null,
  };
}

let db = freshDb();

/** Restores the in-memory mock state; tests call it after each test. */
export function resetMockDb(): void {
  db = freshDb();
  resetCaptureMocks();
  resetAutomationMocks();
}

function error(status: number, code: string, message: string, fields: Record<string, string> = {}) {
  const body: ApiErrorBody = { code, message, fields };
  return HttpResponse.json(body, { status });
}

/** `InvalidConsoleRequestException` as `ConsoleExceptionHandler` answers it: a 400 naming the one bad field. */
function invalidRequest(field: string, reason: string) {
  return error(400, 'INVALID_REQUEST', `Invalid request field=${field} reason=${reason}`, {
    [field]: reason,
  });
}

/** `DatasetNotFoundException`, which every dataset endpoint and the run start answer with. */
function datasetNotFound(id: string) {
  return error(404, 'DATASET_NOT_FOUND', `Replay dataset not found datasetId=${id}`);
}

/** `SimulationRunNotFoundException`, which every run endpoint answers for an unknown id. */
function runNotFound(id: string) {
  return error(404, 'RUN_NOT_FOUND', `Simulation run not found runId=${id}`);
}

/** `DatasetInvalidException` or `EmptyDatasetUploadException`: an upload the catalog refuses, with its message. */
function datasetInvalid(message: string) {
  return error(400, 'DATASET_INVALID', message);
}

/** `MethodArgumentTypeMismatchException`: a query parameter Spring cannot convert to its declared type. */
function parameterMismatch(name: string) {
  return error(400, 'INVALID_REQUEST', 'Request parameter has an invalid value', {
    [name]: 'has an invalid value',
  });
}

/** `ConsolePairs.parse`'s reason for a `pair` parameter it cannot read. */
const PAIR_FORMAT = 'must be <baseMint>/<quoteMint> with two different base58 mints';

/** `ConsolePairs.parse`: two different 32-byte base58 mints, as `<baseMint>/<quoteMint>`. */
function isPair(value: string): boolean {
  const mints = value.split('/');
  return (
    mints.length === 2 &&
    mints[0] !== mints[1] &&
    mints.every((mint) => base58ByteLength(mint) === 32)
  );
}

/** `Base58.requireBytes(value, 32)`'s reason for refusing [value], or null for a 32-byte base58 address. */
function base58Refusal(value: string): string | null {
  if (value.length < 32 || value.length > 64) return 'Invalid base58 length';
  const bytes = base58ByteLength(value);
  if (bytes === null) return 'Invalid base58 character';
  return bytes === 32 ? null : 'Invalid decoded base58 length';
}

/** An upload's text, or null when `ReplayDatasetCatalogService.parse` refuses it: not UTF-8, or with a NUL. */
function utf8Text(bytes: Uint8Array): string | null {
  if (bytes.includes(0)) return null;
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
}

/** A catalog dataset by id, or the v4 chart fixture, which the catalog does not list but serves by id. */
function findDataset(id: unknown): Dataset | undefined {
  return db.datasets.find((d) => d.id === id) ?? (id === V4_DATASET_ID ? v4Dataset : undefined);
}

/**
 * `ReplayRunner.run`'s refusals on a dataset whose observations record no trades, in its order: the wallet-sell rule
 * first, then the minimum signal size. Each is a 409 with the backend's code and `TradesRequiredException` message.
 */
function tradesRefusal(schemaVersion: number, config: RunStartRequest['config']) {
  if (recordsTrades(schemaVersion)) return null;
  const refuse = (code: string, rule: string) =>
    error(
      409,
      code,
      `${rule} needs a dataset whose observations record trades schemaVersion=${schemaVersion} minimumSchemaVersion=${TRADES_SCHEMA_VERSION} (${code})`,
    );
  if (config?.followWalletSells === true)
    return refuse(SELL_CHECK_NEEDS_TRADES, 'The wallet-sell rule');
  if (config?.minSignalSol != null)
    return refuse(SIGNAL_SIZE_NEEDS_TRADES, 'The minimum signal size');
  return null;
}

function pageOf<T>(items: T[], url: URL) {
  const page = Number(url.searchParams.get('page') ?? '0');
  const size = Math.min(Number(url.searchParams.get('size') ?? '20'), 100);
  return { items: items.slice(page * size, page * size + size), page, size, total: items.length };
}

function runDetailOr404(id: string) {
  const records = runRecords[id] ?? recordedRunRecords[id];
  return records ?? null;
}

const BUCKET_LADDER = [1, 5, 15, 30, 60, 300, 900, 3600, 14400, 86400];
const MARKER_CURSOR_PREFIX = 'mock-chart-page:';
// ChartLimits.
const DEFAULT_MAX_BUCKETS = 300;
const MAX_BUCKETS = 2000;
const DEFAULT_MARKER_LIMIT = 500;
const MAX_MARKER_LIMIT = 2000;
const MAX_CHART_WALLETS = 20;
const MAX_TICK_LIMIT = 5000;

/** A chart request as `ChartParameters.of` leaves it: limits defaulted and capped, wallets distinct. */
interface ChartQuery {
  pair: string | null;
  from: string | null;
  to: string | null;
  maxBuckets: number;
  markerLimit: number;
  wallets: string[];
  /** The marker offset the cursor encodes; null on a first page. */
  offset: number | null;
  tickLimit: number;
}

/** `Instant.parse`'s ISO-8601 instant: seconds required, up to 9 fraction digits, then `Z` or an offset. */
function isInstant(text: string): boolean {
  return (
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.test(text) &&
    Number.isFinite(Date.parse(text))
  );
}

/** A decimal Java `int`, as Spring converts an `Int` parameter. */
function isInt(text: string): boolean {
  const value = Number(text);
  return /^[+-]?\d+$/.test(text) && value >= -(2 ** 31) && value < 2 ** 31;
}

/** `ChartController`'s parameters that Spring converts, in declaration order; pair, wallet and cursor stay strings. */
const CHART_TYPED_PARAMETERS: [string, (text: string) => boolean][] = [
  ['from', isInstant],
  ['to', isInstant],
  ['maxBuckets', isInt],
  ['markerLimit', isInt],
  ['tickLimit', isInt],
];

/** The offset a mock marker cursor encodes, or null; as with `ChartPosition.decode`, any such cursor is valid. */
function cursorOffset(cursor: string): number | null {
  const offset = cursor.startsWith(MARKER_CURSOR_PREFIX)
    ? cursor.slice(MARKER_CURSOR_PREFIX.length)
    : '';
  return /^\d+$/.test(offset) ? Number(offset) : null;
}

/** `ChartParameters.limit`'s refusal of a value below [minimum]; a value above its cap is capped, not refused. */
function belowMinimum(field: string, value: number | null, minimum: number) {
  return value !== null && value < minimum
    ? invalidRequest(field, `must be at least ${minimum}`)
    : null;
}

/**
 * The chart request [url] makes, or the backend's refusal of it: first Spring's conversion of each typed parameter,
 * then `ChartParameters.of`'s checks, each in its order. Spring reads an empty instant or number as absent; an empty
 * pair, wallet or cursor is still sent.
 */
function chartQuery(url: URL): ChartQuery | Response {
  const params = url.searchParams;
  const value = (name: string) => params.get(name) || null;
  const mismatch = CHART_TYPED_PARAMETERS.find(([name, converts]) => {
    const text = value(name);
    return text !== null && !converts(text);
  });
  if (mismatch) return parameterMismatch(mismatch[0]);
  const integer = (name: string) => {
    const text = value(name);
    return text === null ? null : Number(text);
  };
  const from = value('from');
  const to = value('to');
  if (from !== null && to !== null && Date.parse(from) > Date.parse(to))
    return invalidRequest('from', 'must not be after to');
  const wallets = [...new Set(params.getAll('wallet'))];
  if (wallets.length > MAX_CHART_WALLETS)
    return invalidRequest('wallet', `accepts at most ${MAX_CHART_WALLETS} wallets`);
  // `WalletAddress.of`, which does not trim.
  if (wallets.some((wallet) => base58ByteLength(wallet) !== 32))
    return invalidRequest('wallet', 'must be a base58 wallet address');
  const pair = params.get('pair');
  if (pair !== null && !isPair(pair)) return invalidRequest('pair', PAIR_FORMAT);
  const maxBuckets = integer('maxBuckets');
  const markerLimit = integer('markerLimit');
  const cursor = params.get('cursor');
  const offset = cursor === null ? null : cursorOffset(cursor);
  const tickLimit = integer('tickLimit');
  const refusal =
    belowMinimum('maxBuckets', maxBuckets, 1) ??
    belowMinimum('markerLimit', markerLimit, 1) ??
    (cursor !== null && offset === null
      ? invalidRequest('cursor', 'is not a valid cursor')
      : null) ??
    belowMinimum('tickLimit', tickLimit, 0);
  if (refusal) return refusal;
  return {
    pair,
    from,
    to,
    maxBuckets: Math.min(maxBuckets ?? DEFAULT_MAX_BUCKETS, MAX_BUCKETS),
    markerLimit: Math.min(markerLimit ?? DEFAULT_MARKER_LIMIT, MAX_MARKER_LIMIT),
    wallets,
    offset,
    tickLimit: Math.min(tickLimit ?? 0, MAX_TICK_LIMIT),
  };
}

/** `ChartBuckets.bucketSeconds`: the finest ladder step that fits [maxBuckets]; the finest for an open range. */
function chartBucketSeconds(from: string | null, to: string | null, maxBuckets: number): number {
  if (from === null || to === null) return BUCKET_LADDER[0]!;
  const span = Date.parse(to) - Date.parse(from);
  return (
    BUCKET_LADDER.find((seconds) => Math.floor(span / (seconds * 1000)) + 1 <= maxBuckets) ??
    BUCKET_LADDER.at(-1)!
  );
}

function mockMcap(price: string): string {
  const [integer = '0', fraction = ''] = price.split('.');
  const digits = (BigInt(integer + fraction) * 200000000000n)
    .toString()
    .padStart(fraction.length + 1, '0');
  return fraction.length
    ? `${digits.slice(0, -fraction.length)}.${digits.slice(-fraction.length)}`
    : digits;
}

function legacyChart(
  observations: Observation[],
  records?: NonNullable<ReturnType<typeof runDetailOr404>>,
): ChartResponse {
  if (records?.detail.run.datasetId === SAMPLE_DATASET_ID)
    observations = observations.map((item) => ({
      ...item,
      observedTime: new Date(
        Date.parse('2026-10-01T00:00:00Z') + (item.slot - 1000) * 60_000,
      ).toISOString(),
    }));
  const sorted = [...observations].sort(
    (left, right) =>
      Date.parse(left.observedTime) - Date.parse(right.observedTime) || left.slot - right.slot,
  );
  const candles = sorted.map((observation) => {
    const mcap = mockMcap(observation.price);
    return {
      bucketStart: observation.observedTime,
      observationCount: 1,
      price: {
        open: observation.price,
        high: observation.price,
        low: observation.price,
        close: observation.price,
      },
      mcap: { open: mcap, high: mcap, low: mcap, close: mcap },
      mcapMissingPoints: 0,
      mcapUnsupportedPoints: 0,
    };
  });
  const paperFills: ChartResponse['paperFills'] = [];
  for (const scenario of records?.detail.scenarios ?? []) {
    const fill = scenario.fill;
    const entry = observations.find((item) => item.id === fill?.observationId);
    if (fill && entry)
      paperFills.push({
        kind: 'PAPER',
        phase: 'ENTRY',
        side: 'BUY',
        intentId: scenario.intentId,
        slot: fill.actualSlot,
        time: entry.observedTime,
        estimatedTime: entry.observedTime,
        price: fill.executionPrice,
        mcap: mockMcap(entry.price),
        quantity: fill.quantity,
        reason: null,
      });
    const close = scenario.position?.close;
    const exit = observations.find((item) => item.id === close?.observationId);
    if (fill && close && exit)
      paperFills.push({
        kind: 'PAPER',
        phase: 'EXIT',
        side: 'SELL',
        intentId: scenario.intentId,
        slot: close.slot,
        time: exit.observedTime,
        estimatedTime: exit.observedTime,
        price: close.executionPrice,
        mcap: mockMcap(exit.price),
        quantity: fill.quantity,
        reason: close.reason,
      });
  }
  return {
    ...v3Chart,
    ticks: {
      included: true,
      total: sorted.length,
      items: sorted.map((item) => ({
        observationId: item.id,
        slot: item.slot,
        indexInSlot: 0,
        slotOrderProven: false,
        time: item.observedTime,
        estimatedTime: item.observedTime,
        price: item.price,
        mcap: mockMcap(item.price),
        curvePrice: null,
        curveMcap: null,
        trader: item.trade?.trader ?? null,
        side: item.trade?.side ?? null,
        isTrackedWallet: false,
        solLamports: item.trade?.solLamports ?? null,
        tokenRaw: item.trade?.tokenRaw ?? null,
      })),
    },
    range: { from: sorted[0]?.observedTime ?? null, to: sorted.at(-1)?.observedTime ?? null },
    candles,
    paperFills,
  };
}

function chartResponse(base: ChartResponse, query: ChartQuery, datasetOnly = false) {
  const from = query.from ?? base.range.from;
  const to = query.to ?? base.range.to;
  // `ChartParameters.contains`: an absent bound leaves its side open.
  const inRange = (time: string) =>
    (from === null || Date.parse(time) >= Date.parse(from)) &&
    (to === null || Date.parse(time) <= Date.parse(to));
  const bucketSeconds = chartBucketSeconds(from, to, query.maxBuckets);
  const candles = new Map<string, ChartCandle>();
  for (const candle of base.candles.filter((item) => inRange(item.bucketStart))) {
    const bucketStart = new Date(
      Math.floor(Date.parse(candle.bucketStart) / (bucketSeconds * 1000)) * bucketSeconds * 1000,
    ).toISOString();
    const previous = candles.get(bucketStart);
    if (!previous) candles.set(bucketStart, { ...candle, bucketStart });
    else
      candles.set(bucketStart, {
        ...previous,
        observationCount: previous.observationCount + candle.observationCount,
        price: {
          open: previous.price.open,
          high:
            compareDecimals(previous.price.high, candle.price.high) >= 0
              ? previous.price.high
              : candle.price.high,
          low:
            compareDecimals(previous.price.low, candle.price.low) <= 0
              ? previous.price.low
              : candle.price.low,
          close: candle.price.close,
        },
        mcap:
          previous.mcap && candle.mcap
            ? {
                open: previous.mcap.open,
                high:
                  compareDecimals(previous.mcap.high, candle.mcap.high) >= 0
                    ? previous.mcap.high
                    : candle.mcap.high,
                low:
                  compareDecimals(previous.mcap.low, candle.mcap.low) <= 0
                    ? previous.mcap.low
                    : candle.mcap.low,
                close: candle.mcap.close,
              }
            : (previous.mcap ?? candle.mcap),
        mcapMissingPoints: previous.mcapMissingPoints + candle.mcapMissingPoints,
        mcapUnsupportedPoints: previous.mcapUnsupportedPoints + candle.mcapUnsupportedPoints,
      });
  }
  const selected = base.markers.items.filter(
    (item) =>
      inRange(item.time) &&
      (query.wallets.length === 0 || item.isTrackedWallet || query.wallets.includes(item.trader)),
  );
  const clustered = selected.length > query.markerLimit;
  const items = clustered ? selected.filter((item) => item.isTrackedWallet) : selected;
  // A cursor past the last marker is a valid, empty page.
  const offset = query.offset ?? 0;
  const page = items.slice(offset, offset + query.markerLimit);
  const ticks = base.ticks.items.filter((item) => inRange(item.time));
  const firstPage = query.offset === null;
  const ticksIncluded = firstPage && query.tickLimit > 0 && ticks.length <= query.tickLimit;
  const clusterMap = new Map<string, ChartResponse['markers']['clusters'][number]>();
  if (clustered && firstPage) {
    for (const item of selected.filter((marker) => !marker.isTrackedWallet)) {
      const bucketStart = new Date(
        Math.floor(Date.parse(item.time) / (bucketSeconds * 1000)) * bucketSeconds * 1000,
      ).toISOString();
      const previous = clusterMap.get(bucketStart);
      clusterMap.set(
        bucketStart,
        previous
          ? {
              ...previous,
              count: previous.count + 1,
              buyCount: previous.buyCount + (item.side === 'BUY' ? 1 : 0),
              sellCount: previous.sellCount + (item.side === 'SELL' ? 1 : 0),
              lastSlot: item.slot,
              lastTime: item.time,
            }
          : {
              bucketStart,
              count: 1,
              buyCount: item.side === 'BUY' ? 1 : 0,
              sellCount: item.side === 'SELL' ? 1 : 0,
              firstSlot: item.slot,
              lastSlot: item.slot,
              firstTime: item.time,
              lastTime: item.time,
            },
      );
    }
  }
  const { from: firstTime, to: lastTime } = base.range;
  return HttpResponse.json({
    ...base,
    extent:
      base.extent ??
      (firstTime && lastTime ? { firstTime, lastTime, firstSlot: 1000, lastSlot: 5500 } : null),
    bucketSeconds,
    range: { from, to },
    ticks: {
      included: ticksIncluded,
      total: ticks.length,
      items: ticksIncluded && mockState() !== 'chart-empty' ? ticks : [],
    },
    candles: mockState() === 'chart-empty' && query.maxBuckets !== 1 ? [] : [...candles.values()],
    markers: {
      ...base.markers,
      mode: clustered ? 'CLUSTERED' : 'ITEMS',
      total: selected.length,
      items: page,
      clusters: [...clusterMap.values()],
      nextCursor:
        offset + page.length < items.length
          ? `${MARKER_CURSOR_PREFIX}${offset + page.length}`
          : null,
    },
    paperFills: datasetOnly ? [] : base.paperFills.filter((fill) => inRange(fill.time)),
  } satisfies ChartResponse);
}

/** Whether [pair] is one of the recorded [pairs]; the dev-only `?mockState=chart-unrecorded-pair` says none is. */
function recordedPair(pairs: string[], pair: string | null): boolean {
  return pair !== null && pairs.includes(pair) && mockState() !== 'chart-unrecorded-pair';
}

/**
 * `ChartConsoleService.assemble` for a pair the run or dataset has no observations of: an empty chart, not an error.
 * Its range is the requested one, null where none was asked for.
 */
function emptyChart(base: ChartResponse, query: ChartQuery) {
  return HttpResponse.json({
    ...base,
    millisPerSlot: null,
    coverage: [],
    extent: null,
    bucketSeconds: chartBucketSeconds(query.from, query.to, query.maxBuckets),
    range: { from: query.from, to: query.to },
    candles: [],
    markers: {
      ...base.markers,
      mode: 'ITEMS',
      total: 0,
      items: [],
      clusters: [],
      nextCursor: null,
    },
    paperFills: [],
    ticks: { included: query.offset === null && query.tickLimit > 0, total: 0, items: [] },
  } satisfies ChartResponse);
}

// ConsoleConfigValidator, field by field: the same checks, in its order, with its messages and error codes.
const PLAIN_DECIMAL = /^[0-9]{1,64}(\.[0-9]{1,64})?$/;
const PERCENT = /^[0-9]+(\.[0-9]{1,2})?$/;
const DECIMAL_FORMAT_MESSAGE = 'must be a plain decimal string such as "100" or "0.5"';
const PERCENT_FORMAT_MESSAGE =
  'must be a percent string with at most 2 decimals, such as "20" or "15.5"';
/** `DecimalPolicy.SCALE`: the market cap and transaction cost are rounded to it before their range is checked. */
const STORAGE_SCALE = 12;
/** Each level's basis point field and its percent twin: either may be sent, and both must then agree. */
const LEVELS = [
  ['dipBps', 'dipPercent'],
  ['takeProfitBps', 'takeProfitPercent'],
  ['stopLossBps', 'stopLossPercent'],
] as const;
const PERCENT_FIELDS: ReadonlySet<string> = new Set(LEVELS.map(([, percent]) => percent));
/** `ConfigErrorCode`'s messages. */
const CONFIG_ERROR_MESSAGES = {
  CONFIG_INVALID: 'Simulation configuration is invalid',
  INVALID_PERCENT: 'A percent setting is invalid',
  CONFLICTING_FIELDS: 'A percent setting disagrees with its basis point field',
} as const;
/** Every property the backend's config request accepts; any other is reported, as the backend does. */
const CONFIG_FIELDS = new Set([
  'notional',
  'maxAgeSlots',
  'dipBps',
  'dipPercent',
  'takeProfitPercent',
  'stopLossPercent',
  'takeProfitBps',
  'stopLossBps',
  'minMarketCapUsd',
  'horizonSlots',
  'latencySlots',
  'transactionCostSol',
  'quoteMint',
  'dipWindowMs',
  'maxHoldMs',
  'hardExitStartMs',
  'priceMeasure',
  'followWalletSells',
  'minSignalSol',
]);

type FieldErrors = Record<string, string>;

/** JSON null or a missing property: the field keeps its default. */
function isAbsent(value: unknown): value is null | undefined {
  return value == null;
}

/** A plain decimal string, or null when absent or reported as malformed. */
function decimalOrNull(field: string, value: unknown, errors: FieldErrors): string | null {
  if (isAbsent(value)) return null;
  if (typeof value === 'string' && PLAIN_DECIMAL.test(value)) return value;
  errors[field] = DECIMAL_FORMAT_MESSAGE;
  return null;
}

/** A JSON integer within [range], or null when absent or reported as invalid. */
function integerOrNull(
  field: string,
  value: unknown,
  range: Range,
  errors: FieldErrors,
): number | null {
  if (isAbsent(value)) return null;
  if (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= range.min &&
    (range.max === null || value <= range.max)
  )
    return value;
  errors[field] =
    range.max === null
      ? `must be an integer of at least ${range.min}`
      : `must be an integer between ${range.min} and ${range.max}`;
  return null;
}

/** A percent string as exact basis points within [range], or null when absent or reported as invalid. */
function percentBpsOrNull(
  field: string,
  value: unknown,
  range: Range,
  errors: FieldErrors,
): number | null {
  if (isAbsent(value)) return null;
  if (typeof value !== 'string' || !PERCENT.test(value)) {
    errors[field] = PERCENT_FORMAT_MESSAGE;
    return null;
  }
  const [whole = '', fraction = ''] = value.split('.');
  const bps = BigInt(whole + fraction.padEnd(2, '0'));
  if (bps < BigInt(range.min) || (range.max !== null && bps > BigInt(range.max))) {
    const min = bpsToPercent(range.min);
    errors[field] =
      range.max === null
        ? `must be at least ${min} percent`
        : `must be between ${min} and ${bpsToPercent(range.max)} percent`;
    return null;
  }
  return Number(bps);
}

/** Decimals left once trailing zeros are dropped: `stripTrailingZeros().scale()` for a value with a fraction. */
function significantDecimals(value: string): number {
  return (value.split('.')[1] ?? '').replace(/0+$/, '').length;
}

/** `DecimalPolicy.scale`: a plain decimal rounded half to even to `STORAGE_SCALE` decimals. */
function storageScaled(value: string): string {
  const [whole = '', fraction = ''] = value.split('.');
  if (fraction.length <= STORAGE_SCALE) return value;
  const kept = BigInt(whole + fraction.slice(0, STORAGE_SCALE));
  const dropped = fraction.slice(STORAGE_SCALE);
  const half = '5'.padEnd(dropped.length, '0');
  // Digit strings of the same length compare like the numbers they spell.
  const up = dropped > half || (dropped === half && kept % 2n === 1n);
  const digits = String(kept + (up ? 1n : 0n)).padStart(STORAGE_SCALE + 1, '0');
  return `${digits.slice(0, -STORAGE_SCALE)}.${digits.slice(-STORAGE_SCALE)}`;
}

/** True when the plain decimal [value] lies outside [range]; a null maximum is unbounded. */
function outside(
  value: string,
  { min, max }: { min: string | number; max: string | number | null },
): boolean {
  return (
    compareDecimals(value, String(min)) < 0 ||
    (max !== null && compareDecimals(value, String(max)) > 0)
  );
}

/** A valid [field] whose pair was not sent reports the pair as missing; an invalid one is already reported on its own. */
function requirePair(
  field: string,
  value: number | null,
  pairField: string,
  pair: unknown,
  errors: FieldErrors,
) {
  if (value !== null && isAbsent(pair)) errors[pairField] = `must be set together with ${field}`;
}

/**
 * `ConsoleConfigValidator.validate` as the backend answers it: a 400 naming every bad field under the most specific
 * `ConfigErrorCode` (a conflict, then a bad percent, then any other error), or null for a valid configuration.
 */
function configRefusal(config: RunStartRequest['config']) {
  if (isAbsent(config)) return null;
  const { limits } = configDefaults;
  const errors: FieldErrors = {};
  const conflicts = new Set<string>();
  const notional = decimalOrNull('notional', config.notional, errors);
  if (notional !== null) {
    const [whole = ''] = notional.split('.');
    const integerDigits = limits.notionalPrecision - limits.notionalScale;
    if (significantDecimals(notional) > limits.notionalScale)
      errors.notional = `must be a whole number of lamports: at most ${limits.notionalScale} decimals`;
    else if (decimalSign(notional) === 0) errors.notional = 'must be at least one lamport';
    else if (whole.replace(/^0+/, '').length > integerDigits)
      errors.notional = `must have at most ${integerDigits} integer digits`;
  }
  integerOrNull('maxAgeSlots', config.maxAgeSlots, limits.maxAgeSlots, errors);
  for (const [bpsField, percentField] of LEVELS) {
    const range = limits[bpsField];
    const bps = integerOrNull(bpsField, config[bpsField], range, errors);
    const percent = percentBpsOrNull(percentField, config[percentField], range, errors);
    if (bps !== null && percent !== null && bps !== percent) {
      errors[percentField] =
        `must equal ${bpsField} when both are sent: ${bpsField} ${bps} is ${bpsToPercent(bps)} percent`;
      conflicts.add(percentField);
    }
  }
  const marketCap = decimalOrNull('minMarketCapUsd', config.minMarketCapUsd, errors);
  if (marketCap !== null && outside(storageScaled(marketCap), limits.minMarketCapUsd)) {
    const { min, max } = limits.minMarketCapUsd;
    errors.minMarketCapUsd = `must be between ${min} and ${max} US dollars`;
  }
  integerOrNull('horizonSlots', config.horizonSlots, limits.horizonSlots, errors);
  integerOrNull('latencySlots', config.latencySlots, limits.latencySlots, errors);
  const cost = decimalOrNull('transactionCostSol', config.transactionCostSol, errors);
  if (cost !== null && outside(storageScaled(cost), limits.transactionCostSol)) {
    const { min, max } = limits.transactionCostSol;
    errors.transactionCostSol = `must be between ${min} and ${max} SOL`;
  }
  // `MintAddress.of`, which unlike isSolanaAddress does not trim.
  if (
    !isAbsent(config.quoteMint) &&
    (typeof config.quoteMint !== 'string' || base58ByteLength(config.quoteMint) !== 32)
  )
    errors.quoteMint = 'must be a 32-byte base58 address';
  integerOrNull('dipWindowMs', config.dipWindowMs, limits.dipWindowMs, errors);
  const maxHoldMs = integerOrNull('maxHoldMs', config.maxHoldMs, limits.maxHoldMs, errors);
  const hardExitStartMs = integerOrNull(
    'hardExitStartMs',
    config.hardExitStartMs,
    limits.hardExitStartMs,
    errors,
  );
  requirePair('maxHoldMs', maxHoldMs, 'hardExitStartMs', config.hardExitStartMs, errors);
  requirePair('hardExitStartMs', hardExitStartMs, 'maxHoldMs', config.maxHoldMs, errors);
  if (
    !isAbsent(config.priceMeasure) &&
    !(limits.priceMeasure as unknown[]).includes(config.priceMeasure)
  )
    errors.priceMeasure = `must be one of ${limits.priceMeasure.join(', ')}`;
  if (!isAbsent(config.followWalletSells) && typeof config.followWalletSells !== 'boolean')
    errors.followWalletSells = 'must be true or false';
  const minSignal = decimalOrNull('minSignalSol', config.minSignalSol, errors);
  // The fixture's limits always carry the minimum signal size, which older backends left out.
  const signalRange = limits.minSignalSol!;
  if (
    minSignal !== null &&
    (significantDecimals(minSignal) > limits.minSignalSolScale! || outside(minSignal, signalRange))
  )
    errors.minSignalSol = `must be a whole number of lamports between ${signalRange.min} and ${signalRange.max} SOL`;
  for (const field of Object.keys(config)) {
    if (!CONFIG_FIELDS.has(field)) errors[field] = 'is not a configuration field';
  }
  if (Object.keys(errors).length === 0) return null;
  const code =
    conflicts.size > 0
      ? 'CONFLICTING_FIELDS'
      : Object.keys(errors).some((field) => PERCENT_FIELDS.has(field))
        ? 'INVALID_PERCENT'
        : 'CONFIG_INVALID';
  return error(400, code, CONFIG_ERROR_MESSAGES[code], errors);
}

function solanaStatus(options: MockOptions): SolanaStatus {
  if (!options.solanaConfigured) return solanaUnconfigured;
  return {
    configured: true,
    host: 'api.devnet.solana.com',
    cluster: 'devnet',
    lastCheck: db.solanaLastCheck,
  };
}

export function createHandlers(options: MockOptions = {}) {
  const wait = async () => {
    if (options.latencyMs) await delay(options.latencyMs);
  };

  return [
    ...createSetupHandlers(wait),
    http.get('/actuator/health', () => HttpResponse.json({ status: 'UP' })),

    http.get('/api/datasets', async ({ request }) => {
      await wait();
      return HttpResponse.json(pageOf(db.datasets, new URL(request.url)));
    }),

    http.post('/api/datasets/samples/:sample', async ({ params }) => {
      await wait();
      // DatasetConsoleService.importSample matches the enum name trimmed, case-insensitively, `-` standing for `_`.
      const sample = String(params.sample);
      const key = (name: string) => name.trim().toUpperCase().replace(/-/g, '_');
      if (key(sample) !== key(SAMPLE_DATASET)) {
        return error(404, 'SAMPLE_NOT_FOUND', `Unknown sample dataset sample=${sample}`);
      }
      const exists = db.datasets.some((d) => d.id === SAMPLE_DATASET_ID);
      if (!exists) db.datasets.unshift(sampleDataset);
      return HttpResponse.json(
        { dataset: sampleDataset, created: !exists },
        { status: exists ? 200 : 201 },
      );
    }),

    http.post('/api/datasets', async ({ request }) => {
      await wait();
      // DatasetController.upload, DatasetConsoleService.upload, then ReplayDatasetCatalogService.register, in order.
      let form: FormData;
      try {
        form = await request.formData();
      } catch {
        return error(
          400,
          'INVALID_REQUEST',
          'Request must be multipart/form-data with a file part',
        );
      }
      const file = form.get('file');
      // A part without a file name is a plain form field, which Spring does not bind as the file either.
      if (file === null || typeof file === 'string')
        return error(400, 'INVALID_REQUEST', 'Multipart request is missing a required part', {
          file: 'is required',
        });
      if (file.size > MAX_UPLOAD_BYTES)
        return error(413, 'UPLOAD_TOO_LARGE', 'Dataset upload exceeds the maximum upload size');
      if (file.size === 0) return datasetInvalid('Uploaded dataset file is empty');
      const nameField = form.get('name');
      // Without a name the file's own name labels the dataset, extension and all.
      const name = (
        typeof nameField === 'string' && nameField.trim() ? nameField : file.name
      ).trim();
      if (!name || name.length > MAX_DATASET_NAME_LENGTH)
        return datasetInvalid(`Dataset name must be 1..${MAX_DATASET_NAME_LENGTH} characters`);
      const text = utf8Text(new Uint8Array(await file.arrayBuffer()));
      if (text === null) return datasetInvalid('Dataset must be UTF-8 JSON');
      let root: unknown;
      try {
        root = JSON.parse(text);
      } catch {
        // The backend relays the JSON parser's own message, which names the position; the mock cannot reproduce it.
        return datasetInvalid('Replay dataset is not valid JSON');
      }
      // ReplayDatasetLoader reads a missing field, or any field of a non-object, as absent.
      const field = (key: string): unknown =>
        typeof root === 'object' && root !== null && !Array.isArray(root)
          ? (root as Record<string, unknown>)[key]
          : undefined;
      const schemaVersion = field('schemaVersion');
      if (typeof schemaVersion !== 'number' || !Number.isInteger(schemaVersion))
        return datasetInvalid('Expected integer field=schemaVersion');
      if (!SUPPORTED_SCHEMA_VERSIONS.includes(schemaVersion)) {
        const supported = SUPPORTED_SCHEMA_VERSIONS.join(', ');
        return datasetInvalid(
          `Unsupported replay schemaVersion=${schemaVersion}; supported versions are [${supported}] (UNSUPPORTED_SCHEMA)`,
        );
      }
      // The mock reads no other field: a well-formed checksum not starting 0000 stands for a dataset that loads.
      const checksum = field('checksum');
      if (typeof checksum !== 'string' || !checksum.trim())
        return datasetInvalid('Expected nonempty string field=checksum');
      if (!/^[0-9a-f]{64}$/.test(checksum) || checksum.startsWith('0000'))
        return datasetInvalid(`Replay checksum mismatch computed=${SAMPLE_DATASET_ID}`);
      const existing = db.datasets.find((d) => d.id === checksum);
      if (existing) return HttpResponse.json({ dataset: existing, created: false });
      const dataset: Dataset = {
        ...sampleDataset,
        id: checksum,
        name,
        schemaVersion,
        createdAt: new Date().toISOString(),
      };
      db.datasets.unshift(dataset);
      return HttpResponse.json({ dataset, created: true }, { status: 201 });
    }),

    http.get('/api/datasets/:id', async ({ params }) => {
      await wait();
      const dataset = findDataset(params.id);
      return dataset
        ? HttpResponse.json({
            ...dataset,
            source: { ...datasetSource, datasetId: dataset.id, datasetName: dataset.name },
          })
        : datasetNotFound(String(params.id));
    }),

    http.delete('/api/datasets/:id', async ({ params }) => {
      await wait();
      const index = db.datasets.findIndex((d) => d.id === params.id);
      if (index < 0) return datasetNotFound(String(params.id));
      db.datasets.splice(index, 1);
      return new HttpResponse(null, { status: 204 });
    }),

    http.get('/api/datasets/:id/observations', async ({ params, request }) => {
      await wait();
      // DatasetConsoleService.observations reads the pair before it loads the dataset.
      const pair = new URL(request.url).searchParams.get('pair');
      if (pair !== null && !isPair(pair)) return invalidRequest('pair', PAIR_FORMAT);
      const dataset = findDataset(params.id);
      if (!dataset) return datasetNotFound(String(params.id));
      const all =
        dataset.id === V4_DATASET_ID
          ? v4Observations
          : (datasetObservations[dataset.id] ?? datasetObservations[SAMPLE_DATASET_ID] ?? []);
      return HttpResponse.json(pair === null ? all : all.filter((o) => pairKey(o.pair) === pair));
    }),

    http.get('/api/datasets/:id/chart', async ({ params, request }) => {
      await wait();
      // ChartConsoleService.datasetChart: the parameters, then the pair it requires, then the dataset.
      const query = chartQuery(new URL(request.url));
      if (query instanceof Response) return query;
      const pair = query.pair;
      if (pair === null) return invalidRequest('pair', 'is required');
      const dataset = findDataset(params.id);
      if (!dataset) return datasetNotFound(String(params.id));
      const observations = (
        datasetObservations[dataset.id] ??
        datasetObservations[SAMPLE_DATASET_ID] ??
        []
      ).filter((item) => pairKey(item.pair) === pair);
      const base = recordsTrades(dataset.schemaVersion) ? v4Chart : legacyChart(observations);
      return recordedPair(dataset.pairs.map(pairKey), pair)
        ? chartResponse(base, query, true)
        : emptyChart(base, query);
    }),

    http.get('/api/config/defaults', async () => {
      await wait();
      return HttpResponse.json(configDefaults);
    }),

    http.post('/api/runs', async ({ request }) => {
      await wait();
      const body = (await request.json()) as RunStartRequest;
      // RunLaunchService.start's order: the dataset id, the preset, the configuration, then the dataset itself.
      const datasetId = String(body?.datasetId ?? '').trim();
      if (!datasetId) return invalidRequest('datasetId', 'is required');
      if (body.presetId != null && body.presetId !== runPreset.id)
        return invalidRequest('presetId', 'is not a known preset');
      const configError = configRefusal(body.config);
      if (configError) return configError;
      const dataset = findDataset(datasetId);
      if (!dataset) return datasetNotFound(datasetId);
      const refusal = tradesRefusal(dataset.schemaVersion, body.config);
      if (refusal) return refusal;
      const runId = runForDataset[datasetId] ?? Object.keys(runRecords)[0]!;
      const records = runRecords[runId]!;
      const created = !db.startedRuns.has(runId);
      db.startedRuns.add(runId);
      return HttpResponse.json(
        { run: records.detail.run, created },
        { status: created ? 201 : 200 },
      );
    }),

    http.get('/api/runs', async ({ request }) => {
      await wait();
      const url = new URL(request.url);
      const datasetId = url.searchParams.get('datasetId');
      const runs = Object.values(runRecords)
        .map((r) => r.detail.run)
        .filter((r) => db.startedRuns.has(r.id))
        .filter((r) => !datasetId || r.datasetId === datasetId);
      return HttpResponse.json(pageOf(runs, url));
    }),

    http.get('/api/runs/:id', async ({ params }) => {
      await wait();
      const records = runDetailOr404(String(params.id));
      return records
        ? HttpResponse.json(withRunSetup(records.detail))
        : runNotFound(String(params.id));
    }),

    http.get('/api/runs/:id/chart', async ({ params, request }) => {
      await wait();
      // ChartConsoleService.runChart: the parameters, then the run, then its only pair when none is named.
      const query = chartQuery(new URL(request.url));
      if (query instanceof Response) return query;
      const id = String(params.id);
      const records = runDetailOr404(id);
      if (!records) return runNotFound(id);
      const pairs = [...new Set(records.observations.map((item) => pairKey(item.pair)))];
      if (query.pair === null && pairs.length > 1)
        return invalidRequest('pair', 'is required when the run has several pairs');
      const pair = query.pair ?? pairs[0] ?? null;
      const observations = records.observations.filter((item) => pairKey(item.pair) === pair);
      const base =
        mockState() === 'chart-first-slot-gap'
          ? firstSlotGapChart
          : id === REAL_RUN_ID
            ? expandedRunChart
            : legacyChart(observations, records);
      return recordedPair(pairs, pair) ? chartResponse(base, query) : emptyChart(base, query);
    }),

    ...(['events', 'observations', 'orders', 'positions'] as const).map((kind) =>
      http.get(`/api/runs/:id/${kind}`, async ({ params }) => {
        await wait();
        const records = runDetailOr404(String(params.id));
        return records ? HttpResponse.json(records[kind]) : runNotFound(String(params.id));
      }),
    ),

    http.get('/api/runs/:id/usdt', async ({ params }) => {
      await wait();
      const id = String(params.id);
      if (!runDetailOr404(id)) return runNotFound(id);
      if (id === realRunUsdt.runId) return HttpResponse.json(realRunUsdt);
      return HttpResponse.json({
        runId: id,
        rateSource: null,
        unavailableReason: 'NOT_CONFIGURED',
        observations: [],
        scenarios: [],
        realizedPnlUsdt: null,
        realizedPnlUnavailableReason: 'NOT_CONFIGURED',
      });
    }),

    http.get('/api/market/sol-usdt', async () => {
      await wait();
      return HttpResponse.json(solUsdtPrice);
    }),

    http.get('/api/runs/:id/report', ({ params }) => {
      const records = runDetailOr404(String(params.id));
      if (!records) return runNotFound(String(params.id));
      return HttpResponse.json(records.detail, {
        headers: { 'Content-Disposition': `attachment; filename="run-${String(params.id)}.json"` },
      });
    }),

    http.get('/api/dashboard', async () => {
      await wait();
      return HttpResponse.json(dashboardFixture());
    }),

    http.get('/api/solana/status', async () => {
      await wait();
      return HttpResponse.json(solanaStatus(options));
    }),

    http.post('/api/solana/check', async () => {
      await wait();
      const now = new Date().toISOString();
      if (!options.solanaConfigured) {
        const check: SolanaCheck = {
          outcome: 'NOT_CONFIGURED',
          cached: false,
          checkedAt: now,
          latencyMs: null,
          version: null,
          slot: null,
          cluster: null,
          rpcErrorCode: null,
          message: 'SOLANA_RPC_URL is not set',
        };
        return HttpResponse.json(check);
      }
      const previous = db.solanaLastCheck;
      if (previous && Date.now() - Date.parse(previous.checkedAt) < 30_000) {
        return HttpResponse.json({ ...previous, cached: true });
      }
      const check: SolanaCheck = {
        outcome: 'OK',
        cached: false,
        checkedAt: now,
        latencyMs: 182,
        version: '3.0.4',
        slot: 412_345_678,
        cluster: 'devnet',
        rpcErrorCode: null,
        message: null,
      };
      db.solanaLastCheck = check;
      return HttpResponse.json(check);
    }),

    http.post('/api/solana/address-check', async ({ request }) => {
      await wait();
      const body = (await request.json()) as { address?: string };
      // SolanaProbeService.checkAddress trims the address and checks it before the configuration.
      const address = String(body?.address ?? '').trim();
      const reason = base58Refusal(address);
      if (reason) {
        return error(400, 'INVALID_ADDRESS', 'Not a valid 32-byte base58 Solana address', {
          address: reason,
        });
      }
      if (!options.solanaConfigured) {
        return error(
          409,
          'SOLANA_NOT_CONFIGURED',
          'Solana RPC is not configured; set SOLANA_RPC_URL',
        );
      }
      const result: AddressCheck = {
        address,
        outcome: 'CHECKED',
        cached: false,
        checkedAt: new Date().toISOString(),
        signature: '5'.repeat(87),
        slot: 412_345_001,
        transactionVersion: '0',
        presentFields: [
          'slot',
          'blockTime',
          'meta.err',
          'transaction.message.accountKeys[].signer',
          'meta.postTokenBalances[].mint',
          'meta.postTokenBalances[].owner',
        ],
        missingFields: [
          'meta.postTokenBalances[].uiTokenAmount.amount',
          'meta.postTokenBalances[].uiTokenAmount.decimals',
        ],
        signaturesExamined: 5,
        rpcErrorCode: null,
        message: null,
      };
      return HttpResponse.json(result);
    }),

    ...createCaptureHandlers(wait),
    ...createAutomationHandlers(wait),
  ];
}

export const handlers = createHandlers();

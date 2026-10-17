import { createSetupHandlers, withRunSetup, datasetSource } from './setupHandlers';
import { delay, http, HttpResponse } from 'msw';
import type {
  AddressCheck,
  ApiErrorBody,
  ChartCandle,
  ChartResponse,
  Dataset,
  Observation,
  RunStartRequest,
  SolanaCheck,
  SolanaStatus,
} from '../api/types';
import { SAMPLE_DATASET } from '../api/endpoints';
import { isSolanaAddress } from '../lib/base58';
import { compareDecimals } from '../lib/decimal';
import { createCaptureHandlers, resetCaptureMocks } from './captureHandlers';
import {
  configDefaults,
  dashboardFixture,
  datasetObservations,
  edgeDataset,
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
}

function error(status: number, code: string, message: string, fields: Record<string, string> = {}) {
  const body: ApiErrorBody = { code, message, fields };
  return HttpResponse.json(body, { status });
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
        price: close.executionPrice,
        mcap: mockMcap(exit.price),
        quantity: fill.quantity,
        reason: close.reason,
      });
  }
  return {
    ...v3Chart,
    range: {
      from: sorted[0]?.observedTime ?? v3Chart.range.from,
      to: sorted.at(-1)?.observedTime ?? v3Chart.range.to,
    },
    candles,
    paperFills,
  };
}

function chartResponse(base: ChartResponse, url: URL, datasetOnly = false) {
  const params = url.searchParams;
  const from = params.get('from') ?? base.range.from;
  const to = params.get('to') ?? base.range.to;
  const maxBuckets = Number(params.get('maxBuckets') ?? 300);
  const markerLimit = Number(params.get('markerLimit') ?? 500);
  const wallets = params.getAll('wallet');
  if (
    !Number.isFinite(Date.parse(from)) ||
    !Number.isFinite(Date.parse(to)) ||
    Date.parse(from) > Date.parse(to)
  ) {
    return error(400, 'INVALID_REQUEST', 'Chart range must contain ordered ISO instants');
  }
  if (
    !Number.isInteger(maxBuckets) ||
    maxBuckets < 1 ||
    !Number.isInteger(markerLimit) ||
    markerLimit < 1 ||
    wallets.length > 20 ||
    wallets.some((wallet) => !isSolanaAddress(wallet))
  ) {
    return error(400, 'INVALID_REQUEST', 'Invalid chart bucket, marker or wallet parameters');
  }
  const inRange = (time: string) =>
    Date.parse(time) >= Date.parse(from) && Date.parse(time) <= Date.parse(to);
  const bucketSeconds =
    BUCKET_LADDER.find(
      (seconds) =>
        Math.floor((Date.parse(to) - Date.parse(from)) / (seconds * 1000)) + 1 <=
        Math.min(maxBuckets, 2000),
    ) ?? BUCKET_LADDER.at(-1)!;
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
      (wallets.length === 0 || item.isTrackedWallet || wallets.includes(item.trader)),
  );
  const clustered = selected.length > Math.min(markerLimit, 2000);
  const items = clustered ? selected.filter((item) => item.isTrackedWallet) : selected;
  const cursor = params.get('cursor');
  const offset = cursor?.startsWith(MARKER_CURSOR_PREFIX)
    ? Number(cursor.slice(MARKER_CURSOR_PREFIX.length))
    : 0;
  if (
    cursor &&
    (!cursor.startsWith(MARKER_CURSOR_PREFIX) ||
      !Number.isInteger(offset) ||
      offset < 1 ||
      offset >= items.length)
  ) {
    return error(400, 'INVALID_REQUEST', 'Invalid chart marker cursor');
  }
  const page = items.slice(offset, offset + Math.min(markerLimit, 2000));
  const clusterMap = new Map<string, ChartResponse['markers']['clusters'][number]>();
  if (clustered && !cursor) {
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
  return HttpResponse.json({
    ...base,
    extent: base.extent ?? {
      firstTime: base.range.from,
      lastTime: base.range.to,
      firstSlot: 1000,
      lastSlot: 5500,
    },
    bucketSeconds,
    range: { from, to },
    candles: [...candles.values()],
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

const PLAIN_DECIMAL = /^[0-9]+(\.[0-9]+)?$/;
const DECIMAL_FORMAT_MESSAGE = 'must be a plain decimal string such as "100" or "0.5"';
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
]);

function validateConfig(config: RunStartRequest['config']): Record<string, string> {
  const fields: Record<string, string> = {};
  if (!config) return fields;
  const { limits } = configDefaults;
  if (config.notional !== undefined) {
    if (typeof config.notional !== 'string' || !PLAIN_DECIMAL.test(config.notional)) {
      fields.notional = DECIMAL_FORMAT_MESSAGE;
    } else if (
      (config.notional.split('.')[1] ?? '').replace(/0+$/, '').length > limits.notionalScale
    ) {
      fields.notional = `must be a whole number of lamports: at most ${limits.notionalScale} decimals`;
    } else if (!/[1-9]/.test(config.notional)) {
      fields.notional = 'must be at least one lamport';
    }
  }
  if (config.transactionCostSol !== undefined) {
    const { min, max } = limits.transactionCostSol;
    if (
      typeof config.transactionCostSol !== 'string' ||
      !PLAIN_DECIMAL.test(config.transactionCostSol)
    ) {
      fields.transactionCostSol = DECIMAL_FORMAT_MESSAGE;
    } else if (
      compareDecimals(config.transactionCostSol, min) < 0 ||
      compareDecimals(config.transactionCostSol, max) > 0
    ) {
      fields.transactionCostSol = `must be between ${min} and ${max} SOL`;
    }
  }
  const ranges = {
    maxAgeSlots: limits.maxAgeSlots,
    dipBps: limits.dipBps,
    takeProfitBps: limits.takeProfitBps,
    stopLossBps: limits.stopLossBps,
    horizonSlots: limits.horizonSlots,
    latencySlots: limits.latencySlots,
  } as const;
  for (const [field, range] of Object.entries(ranges)) {
    const value = (config as Record<string, unknown>)[field];
    if (value === undefined) continue;
    const ok =
      typeof value === 'number' &&
      Number.isInteger(value) &&
      value >= range.min &&
      (range.max === null || value <= range.max);
    if (!ok) {
      fields[field] =
        range.max === null
          ? `must be an integer of at least ${range.min}`
          : `must be an integer between ${range.min} and ${range.max}`;
    }
  }
  if (
    config.minMarketCapUsd !== undefined &&
    (typeof config.minMarketCapUsd !== 'string' || !PLAIN_DECIMAL.test(config.minMarketCapUsd))
  ) {
    fields.minMarketCapUsd = DECIMAL_FORMAT_MESSAGE;
  }
  if (config.quoteMint !== undefined && !isSolanaAddress(String(config.quoteMint))) {
    fields.quoteMint = 'must be a 32-byte base58 address';
  }
  for (const field of Object.keys(config)) {
    if (!CONFIG_FIELDS.has(field)) fields[field] = 'is not a configuration field';
  }
  return fields;
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
      if (params.sample !== SAMPLE_DATASET) {
        return error(404, 'SAMPLE_NOT_FOUND', `Unknown sample dataset ${String(params.sample)}`);
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
      let form: FormData;
      try {
        form = await request.formData();
      } catch {
        return error(400, 'INVALID_REQUEST', 'The upload is not a readable multipart request');
      }
      const file = form.get('file');
      if (!(file instanceof File))
        return error(400, 'INVALID_REQUEST', 'file is required', { file: 'is required' });
      if (file.size > MAX_UPLOAD_BYTES)
        return error(413, 'UPLOAD_TOO_LARGE', 'The upload exceeds 100 MB');
      const text = await file.text();
      if (!text.trim()) return error(400, 'DATASET_INVALID', 'The uploaded dataset is empty');
      let parsed: { checksum?: unknown; schemaVersion?: unknown };
      try {
        parsed = JSON.parse(text) as typeof parsed;
      } catch {
        return error(400, 'DATASET_INVALID', 'Replay dataset is not valid JSON');
      }
      if (parsed.schemaVersion !== 3 && parsed.schemaVersion !== 4) {
        return error(
          400,
          'DATASET_INVALID',
          `Unsupported replay schemaVersion ${String(parsed.schemaVersion)}; expected 3 or 4`,
        );
      }
      const checksum = String(parsed.checksum ?? '');
      if (!/^[0-9a-f]{64}$/.test(checksum) || checksum.startsWith('0000')) {
        return error(
          400,
          'DATASET_INVALID',
          `Replay checksum mismatch: declared ${checksum || '(none)'}, computed ${SAMPLE_DATASET_ID}`,
        );
      }
      const existing = db.datasets.find((d) => d.id === checksum);
      if (existing) return HttpResponse.json({ dataset: existing, created: false });
      const name = String(form.get('name') ?? '') || file.name.replace(/\.json$/i, '');
      const dataset: Dataset = {
        ...sampleDataset,
        id: checksum,
        name,
        schemaVersion: parsed.schemaVersion,
        createdAt: new Date().toISOString(),
      };
      db.datasets.unshift(dataset);
      return HttpResponse.json({ dataset, created: true }, { status: 201 });
    }),

    http.get('/api/datasets/:id', async ({ params }) => {
      await wait();
      const dataset =
        db.datasets.find((d) => d.id === params.id) ??
        (params.id === V4_DATASET_ID ? v4Dataset : undefined);
      return dataset
        ? HttpResponse.json({
            ...dataset,
            source: { ...datasetSource, datasetId: dataset.id, datasetName: dataset.name },
          })
        : error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
    }),

    http.delete('/api/datasets/:id', async ({ params }) => {
      await wait();
      const index = db.datasets.findIndex((d) => d.id === params.id);
      if (index < 0)
        return error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
      db.datasets.splice(index, 1);
      return new HttpResponse(null, { status: 204 });
    }),

    http.get('/api/datasets/:id/observations', async ({ params, request }) => {
      await wait();
      const dataset =
        db.datasets.find((d) => d.id === params.id) ??
        (params.id === V4_DATASET_ID ? v4Dataset : undefined);
      if (!dataset)
        return error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
      const pair = new URL(request.url).searchParams.get('pair');
      const all =
        dataset.id === V4_DATASET_ID
          ? v4Observations
          : (datasetObservations[dataset.id] ?? datasetObservations[SAMPLE_DATASET_ID] ?? []);
      return HttpResponse.json(
        pair ? all.filter((o) => `${o.pair.base}/${o.pair.quote}` === pair) : all,
      );
    }),

    http.get('/api/datasets/:id/chart', async ({ params, request }) => {
      await wait();
      const dataset =
        db.datasets.find((item) => item.id === params.id) ??
        (params.id === V4_DATASET_ID ? v4Dataset : undefined);
      if (!dataset)
        return error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
      const url = new URL(request.url);
      const pair = url.searchParams.get('pair');
      if (!pair || !dataset.pairs.some((item) => `${item.base}/${item.quote}` === pair))
        return error(400, 'INVALID_REQUEST', 'A recorded pair is required');
      const observations = (
        datasetObservations[dataset.id] ??
        datasetObservations[SAMPLE_DATASET_ID] ??
        []
      ).filter((item) => `${item.pair.base}/${item.pair.quote}` === pair);
      return chartResponse(
        dataset.schemaVersion >= 4 ? v4Chart : legacyChart(observations),
        url,
        true,
      );
    }),

    http.get('/api/config/defaults', async () => {
      await wait();
      return HttpResponse.json(configDefaults);
    }),

    http.post('/api/runs', async ({ request }) => {
      await wait();
      const body = (await request.json()) as RunStartRequest;
      if (!body?.datasetId)
        return error(400, 'INVALID_REQUEST', 'datasetId is required', { datasetId: 'is required' });
      const fields = validateConfig(body.config);
      if (Object.keys(fields).length > 0) {
        return error(400, 'CONFIG_INVALID', 'Simulation configuration is invalid', fields);
      }
      if (!db.datasets.some((d) => d.id === body.datasetId)) {
        return error(404, 'DATASET_NOT_FOUND', `Dataset ${body.datasetId} not found`);
      }
      const runId = runForDataset[body.datasetId] ?? Object.keys(runRecords)[0]!;
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
        : error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
    }),

    http.get('/api/runs/:id/chart', async ({ params, request }) => {
      await wait();
      const records = runDetailOr404(String(params.id));
      if (!records) return error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
      const url = new URL(request.url);
      const pairs = [
        ...new Set(records.observations.map((item) => `${item.pair.base}/${item.pair.quote}`)),
      ];
      const pair = url.searchParams.get('pair');
      if ((!pair && pairs.length > 1) || (pair && !pairs.includes(pair)))
        return error(400, 'INVALID_REQUEST', 'A recorded pair is required');
      const observations = records.observations.filter(
        (item) => !pair || `${item.pair.base}/${item.pair.quote}` === pair,
      );
      return chartResponse(
        params.id === REAL_RUN_ID ? v4Chart : legacyChart(observations, records),
        url,
      );
    }),

    ...(['events', 'observations', 'orders', 'positions'] as const).map((kind) =>
      http.get(`/api/runs/:id/${kind}`, async ({ params }) => {
        await wait();
        const records = runDetailOr404(String(params.id));
        return records
          ? HttpResponse.json(records[kind])
          : error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
      }),
    ),

    http.get('/api/runs/:id/usdt', async ({ params }) => {
      await wait();
      const id = String(params.id);
      if (!runDetailOr404(id)) return error(404, 'RUN_NOT_FOUND', `Run ${id} not found`);
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
      if (!records) return error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
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
      const address = String(body?.address ?? '');
      if (!isSolanaAddress(address)) {
        return error(400, 'INVALID_ADDRESS', 'Address is not a valid 32-byte base58 address', {
          address: 'must be a 32-byte base58 address',
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
  ];
}

export const handlers = createHandlers();

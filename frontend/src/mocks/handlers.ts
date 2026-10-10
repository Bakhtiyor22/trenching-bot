import { delay, http, HttpResponse } from 'msw';
import type {
  AddressCheck,
  ApiErrorBody,
  Dataset,
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
  recordedRunRecords,
  runForDataset,
  runRecords,
  SAMPLE_DATASET_ID,
  sampleDataset,
  solanaUnconfigured,
  solUsdtPrice,
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
  return { datasets: [sampleDataset, edgeDataset], startedRuns: new Set(Object.keys(runRecords)), solanaLastCheck: null };
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

const PLAIN_DECIMAL = /^[0-9]+(\.[0-9]+)?$/;
const DECIMAL_FORMAT_MESSAGE = 'must be a plain decimal string such as "100" or "0.5"';
/** Every property the backend's config request accepts; any other is reported, as the backend does. */
const CONFIG_FIELDS = new Set([
  'notional',
  'maxAgeSlots',
  'dipBps',
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
    } else if ((config.notional.split('.')[1] ?? '').replace(/0+$/, '').length > limits.notionalScale) {
      fields.notional = `must be a whole number of lamports: at most ${limits.notionalScale} decimals`;
    } else if (!/[1-9]/.test(config.notional)) {
      fields.notional = 'must be at least one lamport';
    }
  }
  if (config.transactionCostSol !== undefined) {
    const { min, max } = limits.transactionCostSol;
    if (typeof config.transactionCostSol !== 'string' || !PLAIN_DECIMAL.test(config.transactionCostSol)) {
      fields.transactionCostSol = DECIMAL_FORMAT_MESSAGE;
    } else if (compareDecimals(config.transactionCostSol, min) < 0 || compareDecimals(config.transactionCostSol, max) > 0) {
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
  if (config.minMarketCapUsd !== undefined &&
    (typeof config.minMarketCapUsd !== 'string' || !PLAIN_DECIMAL.test(config.minMarketCapUsd))) {
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
  return { configured: true, host: 'api.devnet.solana.com', cluster: 'devnet', lastCheck: db.solanaLastCheck };
}

export function createHandlers(options: MockOptions = {}) {
  const wait = async () => {
    if (options.latencyMs) await delay(options.latencyMs);
  };

  return [
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
      return HttpResponse.json({ dataset: sampleDataset, created: !exists }, { status: exists ? 200 : 201 });
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
      if (!(file instanceof File)) return error(400, 'INVALID_REQUEST', 'file is required', { file: 'is required' });
      if (file.size > MAX_UPLOAD_BYTES) return error(413, 'UPLOAD_TOO_LARGE', 'The upload exceeds 100 MB');
      const text = await file.text();
      if (!text.trim()) return error(400, 'DATASET_INVALID', 'The uploaded dataset is empty');
      let parsed: { checksum?: unknown; schemaVersion?: unknown };
      try {
        parsed = JSON.parse(text) as typeof parsed;
      } catch {
        return error(400, 'DATASET_INVALID', 'Replay dataset is not valid JSON');
      }
      if (parsed.schemaVersion !== 1) {
        return error(400, 'DATASET_INVALID', `Unsupported replay schemaVersion ${String(parsed.schemaVersion)}; expected 1`);
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
      const dataset: Dataset = { ...sampleDataset, id: checksum, name, createdAt: new Date().toISOString() };
      db.datasets.unshift(dataset);
      return HttpResponse.json({ dataset, created: true }, { status: 201 });
    }),

    http.get('/api/datasets/:id', async ({ params }) => {
      await wait();
      const dataset = db.datasets.find((d) => d.id === params.id);
      return dataset
        ? HttpResponse.json(dataset)
        : error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
    }),

    http.get('/api/datasets/:id/observations', async ({ params, request }) => {
      await wait();
      const dataset = db.datasets.find((d) => d.id === params.id);
      if (!dataset) return error(404, 'DATASET_NOT_FOUND', `Dataset ${String(params.id)} not found`);
      const pair = new URL(request.url).searchParams.get('pair');
      const all = datasetObservations[dataset.id] ?? datasetObservations[SAMPLE_DATASET_ID] ?? [];
      return HttpResponse.json(pair ? all.filter((o) => `${o.pair.base}/${o.pair.quote}` === pair) : all);
    }),

    http.get('/api/config/defaults', async () => {
      await wait();
      return HttpResponse.json(configDefaults);
    }),

    http.post('/api/runs', async ({ request }) => {
      await wait();
      const body = (await request.json()) as RunStartRequest;
      if (!body?.datasetId) return error(400, 'INVALID_REQUEST', 'datasetId is required', { datasetId: 'is required' });
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
      return HttpResponse.json({ run: records.detail.run, created }, { status: created ? 201 : 200 });
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
      return records ? HttpResponse.json(records.detail) : error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
    }),

    ...(['events', 'observations', 'orders', 'positions'] as const).map((kind) =>
      http.get(`/api/runs/:id/${kind}`, async ({ params }) => {
        await wait();
        const records = runDetailOr404(String(params.id));
        return records ? HttpResponse.json(records[kind]) : error(404, 'RUN_NOT_FOUND', `Run ${String(params.id)} not found`);
      }),
    ),

    http.get('/api/runs/:id/usdt', async ({ params }) => {
      await wait();
      const id = String(params.id);
      if (!runDetailOr404(id)) return error(404, 'RUN_NOT_FOUND', `Run ${id} not found`);
      if (id === realRunUsdt.runId) return HttpResponse.json(realRunUsdt);
      return HttpResponse.json({
        runId: id, rateSource: null, unavailableReason: 'NOT_CONFIGURED', observations: [], scenarios: [],
        realizedPnlUsdt: null, realizedPnlUnavailableReason: 'NOT_CONFIGURED',
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
          outcome: 'NOT_CONFIGURED', cached: false, checkedAt: now, latencyMs: null, version: null,
          slot: null, cluster: null, rpcErrorCode: null, message: 'SOLANA_RPC_URL is not set',
        };
        return HttpResponse.json(check);
      }
      const previous = db.solanaLastCheck;
      if (previous && Date.now() - Date.parse(previous.checkedAt) < 30_000) {
        return HttpResponse.json({ ...previous, cached: true });
      }
      const check: SolanaCheck = {
        outcome: 'OK', cached: false, checkedAt: now, latencyMs: 182, version: '3.0.4',
        slot: 412_345_678, cluster: 'devnet', rpcErrorCode: null, message: null,
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
        return error(409, 'SOLANA_NOT_CONFIGURED', 'Solana RPC is not configured; set SOLANA_RPC_URL');
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

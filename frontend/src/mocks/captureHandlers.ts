import { http, HttpResponse } from 'msw';
import type {
  CaptureAttempt,
  CaptureAttemptRequest,
  CaptureCreate,
  CaptureOperation,
  CaptureOperationRequest,
  CaptureSession,
} from '../api/captures';
import type { ApiErrorBody } from '../api/types';
import { calibrationReport, captureConfig, captureSession, readyInspection } from './captureFixtures';
import { sampleDataset } from './fixtures';

/**
 * Stateful capture mocks. Operations finish immediately with a canned result; no handler models a
 * provider, matching the backend promise that capture reads and polls never acquire data.
 */
let sessions = new Map<string, CaptureSession>();
let operations = new Map<string, CaptureOperation>();

export function resetCaptureMocks(): void {
  sessions = new Map([[captureSession.id, captureSession]]);
  operations = new Map(captureSession.operation ? [[captureSession.operation.id, captureSession.operation]] : []);
}
resetCaptureMocks();

function error(status: number, code: string, message: string) {
  const body: ApiErrorBody = { code, message, fields: {} };
  return HttpResponse.json(body, { status });
}

function attempt(id: string, stage: 'MARKET' | 'RATES', walletAttemptId: string, marketAttemptId: string | null): CaptureAttempt {
  return {
    id,
    stage,
    createdAt: new Date().toISOString(),
    attached: false,
    market: stage === 'MARKET' ? captureConfig.marketDefaults : null,
    walletAttemptId,
    marketAttemptId,
  };
}

function created(body: CaptureCreate): CaptureSession {
  const walletAttemptId = `${body.requestId}-wallet`;
  const marketAttemptId = `${body.requestId}-market`;
  return {
    id: body.requestId,
    name: body.name,
    createdAt: new Date().toISOString(),
    wallet: body.wallet ?? null,
    walletAttemptId,
    marketAttempt: attempt(marketAttemptId, 'MARKET', walletAttemptId, null),
    ratesAttempt: attempt(`${body.requestId}-rates`, 'RATES', walletAttemptId, marketAttemptId),
    inspection: null,
    operation: null,
    datasetReady: false,
    calibrationReady: false,
    datasetId: null,
    calibration: null,
  };
}

function completed(session: CaptureSession, request: CaptureOperationRequest): CaptureSession {
  const now = new Date().toISOString();
  const operation: CaptureOperation = {
    id: request.requestId,
    requestId: request.requestId,
    step: request.step,
    attemptId: request.attemptId ?? null,
    state: 'SUCCEEDED',
    startedAt: now,
    updatedAt: now,
    endedAt: now,
    budget: request.budget ?? captureConfig.budgetDefaults,
    scanPages: request.scanPages ?? null,
    progress: null,
    code: null,
    message: null,
  };
  operations.set(operation.id, operation);
  return {
    ...session,
    operation,
    inspection: session.inspection ?? readyInspection,
    datasetReady: session.datasetReady || request.step === 'EXPORT',
    calibrationReady: session.calibrationReady || request.step === 'CALIBRATE',
    calibration: request.step === 'CALIBRATE' ? calibrationReport : session.calibration,
  };
}

export function createCaptureHandlers(wait: () => Promise<void>) {
  const found = (id: unknown) => sessions.get(String(id));
  return [
    http.get('/api/captures/config', async () => {
      await wait();
      return HttpResponse.json(captureConfig);
    }),
    http.get('/api/captures', async ({ request }) => {
      await wait();
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      const items = [...sessions.values()];
      return HttpResponse.json({ items: items.slice(page * size, page * size + size), page, size, total: items.length });
    }),
    http.post('/api/captures', async ({ request }) => {
      await wait();
      const body = (await request.json()) as CaptureCreate;
      const session = sessions.get(body.requestId) ?? created(body);
      sessions.set(session.id, session);
      return HttpResponse.json(session);
    }),
    http.get('/api/captures/:id', async ({ params }) => {
      await wait();
      const session = found(params.id);
      return session ? HttpResponse.json(session) : error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
    }),
    http.delete('/api/captures/:id', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      if (session.operation?.state === 'RUNNING') return error(409, 'CAPTURE_BUSY', 'This capture has a running operation');
      sessions.delete(session.id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('/api/captures/:id/attempts', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const items = [session.marketAttempt, session.ratesAttempt].filter((a): a is CaptureAttempt => a != null);
      return HttpResponse.json({ items, page: 0, size: 20, total: items.length });
    }),
    http.post('/api/captures/:id/attempts', async ({ params, request }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const body = (await request.json()) as CaptureAttemptRequest;
      const next: CaptureSession = body.stage === 'MARKET'
        ? { ...session, marketAttempt: { ...attempt(body.requestId, 'MARKET', session.walletAttemptId, null), market: body.market ?? captureConfig.marketDefaults }, ratesAttempt: null, inspection: null, datasetReady: false, calibrationReady: false, datasetId: null, calibration: null }
        : { ...session, ratesAttempt: attempt(body.requestId, 'RATES', session.walletAttemptId, session.marketAttempt?.id ?? null), inspection: null, datasetReady: false, datasetId: null };
      sessions.set(next.id, next);
      return HttpResponse.json(next);
    }),
    http.post('/api/captures/:id/operations', async ({ params, request }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const body = (await request.json()) as CaptureOperationRequest;
      const existing = operations.get(body.requestId);
      if (existing) return HttpResponse.json(existing, { status: 202 });
      if (session.operation?.state === 'RUNNING') return error(409, 'CAPTURE_BUSY', 'Capture worker is busy or stopping');
      const next = completed(session, body);
      sessions.set(next.id, next);
      return HttpResponse.json(next.operation, { status: 202 });
    }),
    http.get('/api/captures/:id/operations/:operationId', async ({ params }) => {
      await wait();
      const operation = operations.get(String(params.operationId));
      return operation ? HttpResponse.json(operation) : error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
    }),
    http.post('/api/captures/:id/dataset', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session?.datasetReady) return error(404, 'ARTIFACT_NOT_FOUND', 'Successful artifact not found');
      const created = session.datasetId == null;
      sessions.set(session.id, { ...session, datasetId: sampleDataset.id });
      return HttpResponse.json({ dataset: sampleDataset, created }, { status: created ? 201 : 200 });
    }),
    http.get('/api/captures/:id/downloads/:kind', ({ params }) => {
      const session = found(params.id);
      if (params.kind === 'calibration' && session?.calibration) return HttpResponse.json(session.calibration);
      return error(404, 'ARTIFACT_NOT_FOUND', 'Successful artifact not found');
    }),
  ];
}

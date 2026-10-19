import { CAPTURE_STATUSES, type CaptureSummary } from '../api/captures';
import { captureSuggestion } from './setupHandlers';
import { mockState } from './previewState';
import { http, HttpResponse } from 'msw';
import type {
  CaptureAttempt,
  CaptureAttemptRequest,
  CaptureCase,
  CaptureCreate,
  CaptureInspection,
  CaptureOperation,
  CaptureOperationRequest,
  CaptureSession,
} from '../api/captures';
import { WALLET_TOKEN_SCOPE } from '../api/captures';
import type { ApiErrorBody } from '../api/types';
import {
  calibrationReport,
  captureConfig,
  captureSession,
  provenTarget,
  qualifyingVerdict,
  readyInspection,
  walletTokenSessions,
} from './captureFixtures';
import { sampleDataset } from './fixtures';

/**
 * Stateful capture mocks. Operations finish immediately with a canned result; no handler models a
 * provider, matching the backend promise that capture reads and polls never acquire data.
 */
let sessions = new Map<string, CaptureSession>();
let operations = new Map<string, CaptureOperation>();
let caseResponses = new Map<string, CaptureCase[]>();

const stoppedCapture: CaptureSession = {
  ...captureSession,
  id: 'capture-limited',
  name: 'Decu market sample',
  wallet: {
    ...captureConfig.walletTokenDefaults,
    wallet: captureSuggestion.address,
    mint: sampleDataset.pairs[0]?.base ?? '',
  },
  datasetReady: false,
  calibrationReady: false,
  datasetId: null,
  operation: {
    ...captureSession.operation!,
    id: 'operation-limited',
    requestId: 'operation-limited',
    step: 'MARKET',
    startedAt: '2026-10-19T06:49:00Z',
    updatedAt: '2026-10-19T06:50:00Z',
    endedAt: '2026-10-19T06:50:00Z',
    state: 'STOPPED_BUDGET',
    budget: { ...captureConfig.budgetDefaults, requestBudget: 400 },
    code: 'REQUEST_BUDGET_EXHAUSTED',
    progress: {
      phase: 'FETCHING',
      at: '2026-10-19T06:50:00Z',
      operationRequests: 400,
      operationBytes: 7340032,
      cumulativeRequests: 803,
      cumulativeBytes: 14680064,
      processed: 1323,
      selected: 1748,
    },
  },
  summary: {
    status: 'STOPPED_LIMIT',
    reasonCode: 'REQUEST_BUDGET_EXHAUSTED',
    currentStep: { index: 2, stage: 'MARKETS' },
    steps: [
      { stage: 'WALLET', state: 'DONE' },
      { stage: 'MARKETS', state: 'STOPPED' },
      { stage: 'RATES', state: 'TODO' },
      { stage: 'DATASET', state: 'TODO' },
    ],
    progress: { processed: 1323, selected: 1748, percent: '75.69' },
    nextAction: {
      kind: 'RESUME_MARKETS',
      stage: 'MARKETS',
      attemptId: 'market-1',
      caseId: null,
      maxRequests: 400,
    },
    cases: { total: 1, ready: 0, blocked: 1, downloading: 0 },
    lastProgressAt: '2026-10-19T06:50:00Z',
  },
};

function previewSession(session: CaptureSession): CaptureSession {
  const state = mockState();
  if (!state || !CAPTURE_STATUSES.includes(state as (typeof CAPTURE_STATUSES)[number]))
    return session;
  const summary = stoppedCapture.summary;
  if (!summary) return session;
  const status = state as CaptureSummary['status'];
  return {
    ...stoppedCapture,
    id: session.id,
    name: session.name,
    operation: stoppedCapture.operation
      ? {
          ...stoppedCapture.operation,
          state: status === 'RUNNING' ? 'RUNNING' : status,
          recovery:
            status === 'WAITING_RETRY'
              ? {
                  enabled: true,
                  totalRequestBudget: 1200,
                  totalByteBudget: 104857600,
                  maxAttempts: 3,
                  attemptsUsed: 1,
                  requestsUsed: 400,
                  bytesUsed: 100,
                  reservedRequests: 0,
                  reservedBytes: 0,
                  nextRetryAt: new Date(Date.now() + 60_000).toISOString(),
                }
              : null,
        }
      : null,
    summary: {
      ...summary,
      status,
      nextAction: {
        ...summary.nextAction,
        kind:
          status === 'RUNNING' || status === 'WAITING_RETRY'
            ? 'WAIT'
            : status === 'NOT_STARTED'
              ? 'START_WALLET'
              : status === 'READY_TO_REPLAY'
                ? 'REGISTER_DATASET'
                : status === 'DONE'
                  ? 'START_RUN'
                  : 'RESUME_MARKETS',
      },
    },
  };
}

export function resetCaptureMocks(): void {
  sessions = new Map(
    [
      captureSession,
      stoppedCapture,
      { ...captureSession, id: 'capture-legacy', displayName: `${captureSession.name} (2)` },
      ...Object.values(walletTokenSessions),
    ].map((session) => [session.id, session]),
  );
  operations = new Map(
    [captureSession.operation, stoppedCapture.operation]
      .filter((operation): operation is CaptureOperation => operation !== null)
      .map((operation) => [operation.id, operation]),
  );
  caseResponses = new Map();
  caseResponses.set('capture-limited', [
    {
      caseId: 'case-limited',
      wallet: captureSuggestion.address,
      mint: sampleDataset.pairs[0]?.base ?? '',
      anchorSignature: '2'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 0,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: {},
      selected: 1748,
      captured: 1323,
      gaps: 0,
      marketStage: 'STOPPED_BUDGET',
      coverage: 'PARTIAL',
      progress: stoppedCapture.operation?.progress ?? null,
      readiness: 'BLOCKED',
      captureComplete: false,
      validated: false,
      ready: false,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: false,
      canExport: false,
      blocker: 'REQUEST_BUDGET_EXHAUSTED',
      operationId: 'operation-limited',
      operationState: 'STOPPED_BUDGET',
      datasetId: null,
    },
  ]);
}
export function setCaptureCases(id: string, items: CaptureCase[]): void {
  caseResponses.set(id, items);
}
resetCaptureMocks();

function error(status: number, code: string, message: string) {
  const body: ApiErrorBody = { code, message, fields: {} };
  return HttpResponse.json(body, { status });
}

function attempt(
  id: string,
  stage: 'MARKET' | 'RATES',
  walletAttemptId: string,
  marketAttemptId: string | null,
): CaptureAttempt {
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
    name: body.name?.trim() || 'Decu · token · 17 Oct 00:00',
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
  // A wallet-token session verifies its first buy on inspection and is qualified after export (or an explicit QUALIFY).
  const walletToken = session.wallet?.scope === WALLET_TOKEN_SCOPE;
  const inspection: CaptureInspection =
    session.inspection ??
    (walletToken ? { ...readyInspection, targetToken: provenTarget } : readyInspection);
  const replayed =
    session.qualification != null && session.qualification.replay.state !== 'NOT_RUN';
  const qualifies = walletToken && (request.step === 'EXPORT' || request.step === 'QUALIFY');
  return {
    ...session,
    operation,
    inspection,
    qualification: qualifies && !replayed ? qualifyingVerdict : session.qualification,
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
      return HttpResponse.json({
        ...captureConfig,
        rpcConfigured: mockState() !== 'RPC_NOT_CONFIGURED',
        suggestedWallets: [captureSuggestion],
        recentWallets: [],
      });
    }),
    http.get('/api/captures', async ({ request }) => {
      await wait();
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      const all = mockState() === 'captures-empty' ? [] : [...sessions.values()].map(withSummary);
      const statusCounts = Object.fromEntries(
        CAPTURE_STATUSES.map((status) => [
          status,
          all.filter((item) => item.summary?.status === status).length,
        ]),
      );
      const sort = url.searchParams.get('sort') ?? 'createdAt,desc';
      const status = url.searchParams.get('status');
      const items = all
        .filter((item) => !status || item.summary?.status === status)
        .sort((a, b) =>
          sort === 'name,asc'
            ? a.name.localeCompare(b.name)
            : (sort === 'createdAt,asc' ? 1 : -1) * a.createdAt.localeCompare(b.createdAt) ||
              a.id.localeCompare(b.id),
        );
      return HttpResponse.json({
        items: items.slice(page * size, page * size + size),
        page,
        size,
        total: items.length,
        statusCounts,
      });
    }),
    http.post('/api/captures', async ({ request }) => {
      await wait();
      const body = (await request.json()) as CaptureCreate;
      if (
        ![...sessions.keys()].includes(body.requestId) &&
        body.name?.trim() &&
        [...sessions.values()].some(
          (item) => item.name.toLowerCase() === body.name?.trim().toLowerCase(),
        )
      )
        return HttpResponse.json(
          {
            code: 'NAME_TAKEN',
            message: 'Capture name is already taken',
            fields: { name: 'is already taken' },
            suggestion: body.name + ' (2)',
          },
          { status: 409 },
        );
      const session = sessions.get(body.requestId) ?? created(body);
      sessions.set(session.id, session);
      return HttpResponse.json(withSummary(session));
    }),
    http.get('/api/captures/names/check', async ({ request }) => {
      await wait();
      const name = new URL(request.url).searchParams.get('name')?.trim().replace(/\s+/g, ' ') ?? '';
      const available = ![...sessions.values()].some(
        (session) => session.name.toLowerCase() === name.toLowerCase(),
      );
      return HttpResponse.json({
        available,
        normalized: name,
        suggestion: available ? null : name + ' (2)',
      });
    }),
    http.patch('/api/captures/:id', async ({ params, request }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const body = (await request.json()) as { name: string };
      const name = body.name.trim().replace(/\s+/g, ' ');
      if (
        [...sessions.values()].some(
          (item) => item.id !== session.id && item.name.toLowerCase() === name.toLowerCase(),
        )
      )
        return HttpResponse.json(
          {
            code: 'NAME_TAKEN',
            message: 'Capture name is already taken',
            fields: { name: 'is already taken' },
            suggestion: name + ' (2)',
          },
          { status: 409 },
        );
      const next = { ...session, name };
      sessions.set(session.id, next);
      return HttpResponse.json(withSummary(next));
    }),
    http.get('/api/captures/:id', async ({ params }) => {
      await wait();
      const session = found(params.id);
      return session
        ? HttpResponse.json(previewSession(withSummary(session)))
        : error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
    }),
    http.delete('/api/captures/:id', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      if (session.operation?.state === 'RUNNING')
        return error(409, 'CAPTURE_BUSY', 'This capture has a running operation');
      sessions.delete(session.id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('/api/captures/:id/attempts', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const items = [session.marketAttempt, session.ratesAttempt].filter(
        (a): a is CaptureAttempt => a != null,
      );
      return HttpResponse.json({ items, page: 0, size: 20, total: items.length });
    }),
    http.post('/api/captures/:id/attempts', async ({ params, request }) => {
      await wait();
      const session = found(params.id);
      if (!session) return error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
      const body = (await request.json()) as CaptureAttemptRequest;
      // A new market attempt brings its own empty rates attempt; either kind is then inspected offline.
      const reset: CaptureSession =
        body.stage === 'MARKET'
          ? {
              ...session,
              marketAttempt: {
                ...attempt(body.requestId, 'MARKET', session.walletAttemptId, null),
                market: body.market ?? captureConfig.marketDefaults,
              },
              ratesAttempt: attempt(
                `${body.requestId}-rates`,
                'RATES',
                session.walletAttemptId,
                body.requestId,
              ),
              inspection: null,
              datasetReady: false,
              calibrationReady: false,
              datasetId: null,
              calibration: null,
            }
          : {
              ...session,
              ratesAttempt: attempt(
                body.requestId,
                'RATES',
                session.walletAttemptId,
                session.marketAttempt?.id ?? null,
              ),
              inspection: null,
              datasetReady: false,
              datasetId: null,
            };
      const next = completed(reset, {
        requestId: `${body.requestId}-inspect`,
        step: 'INSPECT',
        attemptId: reset.marketAttempt?.id,
      });
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
      if (session.operation?.state === 'RUNNING')
        return error(409, 'CAPTURE_BUSY', 'Capture worker is busy or stopping');
      const next = completed(session, body);
      sessions.set(next.id, next);
      return HttpResponse.json(next.operation, { status: 202 });
    }),
    http.get('/api/captures/:id/operations/:operationId', async ({ params }) => {
      await wait();
      const operation = operations.get(String(params.operationId));
      return operation
        ? HttpResponse.json(operation)
        : error(404, 'CAPTURE_NOT_FOUND', 'Capture record was not found');
    }),
    http.get('/api/captures/:id/cases', async ({ params }) => {
      await wait();
      return HttpResponse.json({ items: caseResponses.get(String(params.id)) ?? [] });
    }),
    http.post('/api/captures/:id/cases/:caseId/dataset', async ({ params }) => {
      await wait();
      const id = String(params.id);
      const caseId = String(params.caseId);
      const items = caseResponses.get(id) ?? [];
      if (!items.some((item) => item.caseId === caseId))
        return error(404, 'CASE_NOT_FOUND', 'Capture case was not found');
      caseResponses.set(
        id,
        items.map((item) =>
          item.caseId === caseId ? { ...item, datasetId: sampleDataset.id } : item,
        ),
      );
      return HttpResponse.json({ dataset: sampleDataset, created: false });
    }),
    http.post('/api/captures/:id/dataset', async ({ params }) => {
      await wait();
      const session = found(params.id);
      if (!session?.datasetReady)
        return error(404, 'ARTIFACT_NOT_FOUND', 'Successful artifact not found');
      const created = session.datasetId == null;
      sessions.set(session.id, { ...session, datasetId: sampleDataset.id });
      return HttpResponse.json(
        { dataset: sampleDataset, created },
        { status: created ? 201 : 200 },
      );
    }),
    http.get('/api/captures/:id/downloads/:kind', ({ params }) => {
      const session = found(params.id);
      if (params.kind === 'calibration' && session?.calibration)
        return HttpResponse.json(session.calibration);
      return error(404, 'ARTIFACT_NOT_FOUND', 'Successful artifact not found');
    }),
  ];
}

function withSummary(session: CaptureSession): CaptureSession {
  if (session.summary) return session;
  const running = session.operation?.state === 'RUNNING';
  const status = running
    ? 'RUNNING'
    : session.datasetId
      ? 'DONE'
      : session.datasetReady
        ? 'READY_TO_REPLAY'
        : 'NOT_STARTED';
  const kind = running
    ? 'WAIT'
    : session.datasetId
      ? 'START_RUN'
      : session.datasetReady
        ? 'REGISTER_DATASET'
        : 'START_WALLET';
  const summary: CaptureSummary = {
    status,
    reasonCode: session.operation?.code ?? null,
    currentStep: {
      index: session.datasetReady ? 4 : 1,
      stage: session.datasetReady ? 'DATASET' : 'WALLET',
    },
    steps: ['WALLET', 'MARKETS', 'RATES', 'DATASET'].map((stage, index) => ({
      stage: stage as 'WALLET' | 'MARKETS' | 'RATES' | 'DATASET',
      state: session.datasetReady && index < 3 ? 'DONE' : 'TODO',
    })),
    progress: null,
    nextAction: {
      kind,
      stage: session.datasetReady ? 'DATASET' : 'WALLET',
      attemptId: session.walletAttemptId,
      caseId: null,
      maxRequests: session.datasetReady ? null : 400,
    },
    cases: { total: 0, ready: 0, blocked: 0, downloading: 0 },
    lastProgressAt: null,
  };
  return { ...session, displayName: session.displayName ?? session.name, summary };
}

import { http, HttpResponse } from 'msw';
import type {
  AutomationBackfill,
  AutomationDayRetryResult,
  AutomationItem,
  AutomationSessionDetail,
  AutomationVariation,
  BackfillCreateRequest,
  EvaluationStatus,
  RetryRefusal,
  VariationConfigRequest,
  VariationEditRequest,
} from '../api/automation';
import type { Config } from '../api/types';
import { isSolanaAddress } from '../lib/base58';
import { compareDecimals, isDecimalString } from '../lib/decimal';
import { bpsToPercent } from '../lib/percent';
import {
  compareEvaluations,
  createAutomationDb,
  dayDetail,
  dayRows,
  daySummary,
  differingProperties,
  discoveryOnly,
  mockAddDays,
  mockDayOf,
  mockDayStart,
  mockVariation,
  type AutomationDb,
} from './automationFixtures';
import { SOL_MINT } from './fixtures';
import { mockCaptureOperation, mockItemEvents } from './automationEventFixtures';
import { mockState } from './previewState';

/**
 * `/api/automation` mocks with the backend's filters, sorts, paging, refusals and day scoring. Mock states:
 * `automation-off` answers 404 like a backend with automation disabled; `automation-phase0` is discovery only (every day
 * pending); `automation-empty` has no buys yet; `automation-idle` has no active backfill, so a new one can start;
 * `variation-conflict` loses every variation edit to a concurrent one (409 AUTOMATION_CONFLICT); `variation-limit`
 * refuses every edit as if the plan already held its 500 variations (409 VARIATION_LIMIT_REACHED).
 */

let db: AutomationDb = createAutomationDb();
/** When "run now" was queued; the mock keeps it queued for a few seconds. */
let runQueuedAt: number | null = null;
/** When each backfill created here started, to walk it through its states. */
const startedAt = new Map<string, number>();
const RUN_QUEUED_MS = 6_000;
const SCAN_MS = 4_000;
const MAX_DAYS_PER_LIST = 62;
const DEFAULT_DAYS = 14;

/** The request id of each item's last retry, as the backend keeps it in `retry_request_id`. */
const retryRequestIds = new Map<string, string>();
/** The variation each edit request id created, as `uq_automation_variations_request` keeps it. */
const editRequestIds = new Map<string, string>();
const MAX_EDIT_REQUEST_ID = 64;
/** `AutomationLimits.MAX_VARIATIONS_PER_PLAN`: sequences 0 to 499. */
const MAX_VARIATIONS_PER_PLAN = 500;
const CAPTURE_MIN_SIGNAL_SOL = '1.5';
/** The backend's smallest backfill budgets: a dry run, and a capture (`recovery.requests` 2400 + 22 per discovery). */
const MIN_DRY_RUN_BUDGET = 100;
const MIN_CAPTURE_BUDGET = 2422;
/** The properties an edit must send; the clocks may be null (untimed) but must be present. */
const REQUIRED_EDIT_FIELDS = [
  'notional',
  'maxAgeSlots',
  'dipPercent',
  'takeProfitPercent',
  'stopLossPercent',
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
] as const;
const LEVEL_PAIRS = [
  ['dipBps', 'dipPercent'],
  ['takeProfitBps', 'takeProfitPercent'],
  ['stopLossBps', 'stopLossPercent'],
] as const;

/** A configuration's own checks, as the run start's mock makes them; null when it is valid. */
export type ConfigCheck = (config: VariationConfigRequest) => Response | null;
const REQUEST_ID = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/;

export function resetAutomationMocks(next: AutomationDb = createAutomationDb()): void {
  db = next;
  runQueuedAt = null;
  startedAt.clear();
  retryRequestIds.clear();
  editRequestIds.clear();
}

/** Percent "80" → 8000 bps, exactly, as `PercentConversion` does; already validated. */
function percentToBps(percent: string): number {
  const [whole = '0', fraction = ''] = percent.split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0').slice(0, 2));
}

/** The stored configuration an edit's request describes: the agreed strategy's name and version, levels in both units. */
function configOf(request: VariationConfigRequest, base: Config): Config {
  const config: Config = {
    ...base,
    notional: request.notional!,
    maxAgeSlots: request.maxAgeSlots!,
    minMarketCapUsd: request.minMarketCapUsd!,
    horizonSlots: request.horizonSlots!,
    latencySlots: request.latencySlots!,
    transactionCostSol: request.transactionCostSol!,
    quoteMint: request.quoteMint!,
    dipWindowMs: request.dipWindowMs ?? null,
    maxHoldMs: request.maxHoldMs ?? null,
    hardExitStartMs: request.hardExitStartMs ?? null,
    priceMeasure: request.priceMeasure ?? 'TRADE_AVERAGE',
    followWalletSells: request.followWalletSells ?? false,
    minSignalSol: request.minSignalSol ?? null,
  };
  for (const [bpsField, percentField] of LEVEL_PAIRS) {
    const percent = request[percentField];
    const bps = percent != null ? percentToBps(percent) : request[bpsField]!;
    config[bpsField] = bps;
    config[percentField] = bpsToPercent(bps);
  }
  return config;
}

/**
 * The complete-configuration checks an edit adds to the run start's: every field present (a level as percent or
 * bps), a minimum signal size at or above the capture filter, and wrapped SOL as the quote.
 */
function completeConfigRefusal(config: VariationConfigRequest | undefined): Response | null {
  if (config == null || typeof config !== 'object')
    return error(400, 'CONFIG_INVALID', 'Simulation configuration is invalid', {
      config: 'must be set',
    });
  const fields: Record<string, string> = {};
  const record = config as Record<string, unknown>;
  for (const field of REQUIRED_EDIT_FIELDS) {
    const level = LEVEL_PAIRS.find(([, percent]) => percent === field);
    const present = field in record || (level != null && level[0] in record);
    if (!present) fields[field] = 'must be set';
  }
  if (!fields.minSignalSol) {
    const min = config.minSignalSol;
    if (min == null) fields.minSignalSol = 'must be set';
    else if (isDecimalString(min) && compareDecimals(min, CAPTURE_MIN_SIGNAL_SOL) < 0)
      fields.minSignalSol = `must be at least the capture filter, ${CAPTURE_MIN_SIGNAL_SOL} SOL`;
  }
  if (!fields.quoteMint && config.quoteMint !== SOL_MINT) fields.quoteMint = 'must be wrapped SOL';
  return Object.keys(fields).length > 0
    ? error(400, 'CONFIG_INVALID', 'Simulation configuration is invalid', fields)
    : null;
}

/** "V" and the sequence, at least two digits: V10, V11, … */
function labelOf(seq: number): string {
  return `V${String(seq).padStart(2, '0')}`;
}

function sameConfig(a: Config, b: Config): boolean {
  return differingProperties(a, b).length === 0;
}

/** The sequence the next variation gets: one more than the highest. */
function nextSeq(): number {
  return Math.max(...db.variations.map((v) => Number(v.label.slice(1)))) + 1;
}

/** Plan Domain Rule 2: archive `old` and insert its replacement, in one step. */
function applyEdit(
  old: AutomationVariation,
  config: Config,
  requestId: string,
): AutomationVariation {
  const now = new Date().toISOString();
  const seq = nextSeq();
  const created = mockVariation(labelOf(seq), config, {
    replacesLabel: old.label,
    changedFromReplaced: differingProperties(config, old.config),
    activeFrom: now,
    createdAt: now,
  });
  old.state = 'ARCHIVED';
  old.archivedAt = now;
  old.replacedByLabel = created.label;
  db.variations.push(created);
  editRequestIds.set(requestId, created.label);
  return created;
}

/** The backend's retry rule, in its order: not failed, a failure no retry can fix, a backfill still scanning, capture off. */
function retryRefusal(item: AutomationItem): RetryRefusal | null {
  if (item.state !== 'FAILED') return 'ITEM_NOT_FAILED';
  if (item.failureCode === 'BACKFILL_BUDGET' || item.failureCode === 'MISSING_BLOCK_TIME')
    return 'RETRY_NOT_ALLOWED';
  const backfill = db.backfills.find((b) => b.id === item.backfillId);
  if (backfill && (backfill.state === 'QUEUED' || backfill.state === 'SCANNING'))
    return 'BACKFILL_NOT_CAPTURING';
  if (!db.status.captureEnabled) return 'CAPTURE_DISABLED';
  return null;
}

const REFUSAL_MESSAGES: Record<RetryRefusal, string> = {
  ITEM_NOT_FAILED: 'Only a failed automation item can be retried',
  RETRY_NOT_ALLOWED: 'This failure cannot be retried',
  BACKFILL_NOT_CAPTURING: 'The backfill of this item has not started capturing',
  CAPTURE_DISABLED: 'Automation capture is disabled',
};

function refusalResponse(item: AutomationItem, code: RetryRefusal) {
  return error(
    409,
    code,
    REFUSAL_MESSAGES[code],
    code === 'RETRY_NOT_ALLOWED' ? { failureCode: item.failureCode ?? '' } : {},
  );
}

/** Puts a failed item back where it failed (plan 20261107, rules 2–4) and stores the request id. */
function applyRetry(item: AutomationItem, requestId: string): void {
  const session = db.sessions.find((s) => s.id === item.sessionId);
  const sweep = item.failureCode === 'SWEEP_FAILED';
  const next: AutomationItem['state'] = sweep ? 'CAPTURED' : 'CAPTURING';
  if (session) {
    session.itemCounts[item.state] = (session.itemCounts[item.state] ?? 0) - 1;
    session.itemCounts[next] = (session.itemCounts[next] ?? 0) + 1;
  }
  if (item.failureCode === 'CAPTURE_NOT_FOUND') {
    item.step = 'CREATE_SESSION';
    item.captureSessionId = null;
  } else if (sweep) item.step = 'SWEEP';
  item.state = next;
  item.stepAttempt = 1;
  item.operationId = null;
  item.nextActionAt = new Date().toISOString();
  item.failureCode = null;
  item.failureMessage = null;
  item.retries += 1;
  item.retryRefusal = 'ITEM_NOT_FAILED';
  item.updatedAt = new Date().toISOString();
  retryRequestIds.set(item.id, requestId);
}

async function retryRequestId(request: Request): Promise<string | Response> {
  const body = (await request.json().catch(() => ({}))) as { requestId?: unknown };
  const id = body.requestId;
  if (typeof id !== 'string' || !REQUEST_ID.test(id))
    return error(400, 'INVALID_REQUEST', 'requestId is missing or malformed', {
      requestId: 'must match [a-zA-Z0-9][a-zA-Z0-9_-]{0,79}',
    });
  return id;
}

/**
 * The per-item id of a day retry. The backend hashes a request id too long to carry the suffix; the console's ids
 * (at most 45 characters) never are, so the mock leaves that out.
 */
function dayItemRequestId(requestId: string, itemId: string): string {
  return `${requestId}-${itemId.slice(0, 24)}`;
}

function current(): AutomationDb {
  const state = mockState();
  if (state === 'automation-phase0') return discoveryOnly(db);
  if (state === 'automation-idle' && db.status.activeBackfillId) {
    db = createAutomationDb({ activeBackfill: false });
  }
  if (state === 'automation-empty')
    return {
      ...db,
      status: {
        ...db.status,
        openSessionId: null,
        watermark: null,
        requestsLast24h: 0,
        lastDiscoveryAt: null,
        activeBackfillId: null,
      },
      sessions: [],
      items: [],
      sweepRuns: [],
      backfills: [],
    };
  return db;
}

function error(status: number, code: string, message: string, fields: Record<string, string> = {}) {
  return HttpResponse.json({ code, message, fields }, { status });
}

function page<T>(rows: T[], url: URL) {
  const pageIndex = Number(url.searchParams.get('page') ?? 0);
  const size = Math.min(Number(url.searchParams.get('size') ?? 20), 100);
  return {
    items: rows.slice(pageIndex * size, pageIndex * size + size),
    page: pageIndex,
    size,
    total: rows.length,
  };
}

function inRange(iso: string | null, url: URL): boolean {
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to');
  // A buy without a block time is left out whenever a bound is given (plan Rule 14).
  if (iso == null) return !from && !to;
  const t = Date.parse(iso);
  return (!from || t >= Date.parse(from)) && (!to || t < Date.parse(to));
}

/** `from` not before `to` is a 400 on `from`, as the sweep-run list answers it. */
function instantRangeRefusal(url: URL): Response | null {
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to');
  for (const [key, value] of [
    ['from', from],
    ['to', to],
  ] as const)
    if (value != null && Number.isNaN(Date.parse(value)))
      return error(400, 'INVALID_REQUEST', 'Request parameter has an invalid value', {
        [key]: 'has an invalid value',
      });
  if (from && to && Date.parse(from) >= Date.parse(to))
    return error(400, 'INVALID_REQUEST', 'from must be before to', { from: 'must be before to' });
  return null;
}

/** The requested trading days, oldest first, or a 400 body when the span is wrong. */
function span(url: URL): string[] | Response {
  const today = mockDayOf(Date.now());
  const to = url.searchParams.get('to') ?? today;
  const from = url.searchParams.get('from') ?? mockAddDays(to, -(DEFAULT_DAYS - 1));
  if (from > to)
    return error(400, 'INVALID_REQUEST', 'from must not be after to', {
      from: 'must not be after to',
    });
  const days: string[] = [];
  for (let d = from; d <= to; d = mockAddDays(d, 1)) days.push(d);
  if (days.length > MAX_DAYS_PER_LIST)
    return error(400, 'INVALID_REQUEST', 'Too many days', {
      to: `must be within ${MAX_DAYS_PER_LIST} days of from`,
    });
  return days;
}

/** A backfill created through the mock moves from scanning to its next state after a few seconds. */
function withProgress(b: AutomationBackfill): AutomationBackfill {
  const started = startedAt.get(b.id);
  if (started == null || ['CANCELLED', 'DONE', 'STOPPED_BUDGET'].includes(b.state)) return b;
  const elapsed = Date.now() - started;
  if (elapsed < SCAN_MS) {
    const middle = (Date.parse(b.rangeStart) + Date.parse(b.rangeEnd)) / 2;
    return {
      ...b,
      state: 'SCANNING',
      scannedTo: new Date(middle).toISOString(),
      discoveryRequests: 44,
      requestsSpent: 44,
      actionsScanned: 120,
    };
  }
  const days: Record<string, number> = {};
  for (let d = b.fromDay, i = 0; d <= b.toDay; d = mockAddDays(d, 1), i += 1)
    days[d] = (i * 3 + 2) % 7;
  const passing = Object.values(days).reduce((sum, n) => sum + n, 0);
  const finished = {
    ...b,
    scannedTo: b.rangeStart,
    discoveryRequests: 96,
    requestsSpent: 96,
    actionsScanned: 260,
    buysFound: passing + 5,
    buysPassing: passing,
  };
  if (b.dryRun)
    return {
      ...finished,
      state: 'DONE',
      summary: { distinctMints: Math.max(1, passing - 3), buysPerDay: days },
      estimatedRequests: Math.max(1, passing - 3) * 175,
      finishedAt: new Date(started + SCAN_MS).toISOString(),
    };
  return {
    ...finished,
    state: 'CAPTURING',
    itemsCreated: passing,
    itemCounts: { DISCOVERED: passing },
  };
}

function validate(body: Partial<BackfillCreateRequest>): Record<string, string> {
  const fields: Record<string, string> = {};
  if (!body.fromDay) fields.fromDay = 'is required';
  if (!body.toDay) fields.toDay = 'is required';
  if (body.fromDay && body.toDay) {
    if (body.fromDay > body.toDay) fields.toDay = 'must not be before fromDay';
    const days = (mockDayStart(body.toDay) - mockDayStart(body.fromDay)) / 86_400_000 + 1;
    if (days > 31) fields.toDay = 'must be within 31 days of fromDay';
    if (mockDayStart(mockAddDays(body.toDay, 1)) > Date.now())
      fields.toDay = 'must be a finished trading day';
  }
  const min = body.dryRun ? MIN_DRY_RUN_BUDGET : MIN_CAPTURE_BUDGET;
  if (body.requestBudget == null || body.requestBudget < min || body.requestBudget > 20_000)
    fields.requestBudget = `must be between ${min} and 20000`;
  if (body.minSignalSol != null && Number(body.minSignalSol) < 1.5)
    fields.minSignalSol = 'must be at least the capture filter, 1.5 SOL';
  if (body.mints && (body.mints.length > 50 || body.mints.some((m) => !isSolanaAddress(m))))
    fields.mints = 'must be 1 to 50 valid base58 addresses';
  return fields;
}

export function createAutomationHandlers(
  wait: () => Promise<void>,
  configRefusal: ConfigCheck = () => null,
) {
  const off = () =>
    mockState() === 'automation-off' ? new HttpResponse(null, { status: 404 }) : null;
  return [
    http.get('/api/automation/status', async () => {
      await wait();
      const data = current();
      const queued = runQueuedAt != null && Date.now() - runQueuedAt < RUN_QUEUED_MS;
      return off() ?? HttpResponse.json({ ...data.status, manualRunQueued: queued });
    }),
    http.post('/api/automation/discovery/run', async () => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      if (!current().status.enabled)
        return error(409, 'AUTOMATION_DISABLED', 'Automation is disabled');
      const already = runQueuedAt != null && Date.now() - runQueuedAt < RUN_QUEUED_MS;
      if (!already) runQueuedAt = Date.now();
      return HttpResponse.json({ queued: !already, alreadyQueued: already }, { status: 202 });
    }),
    http.get('/api/automation/sessions', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const url = new URL(request.url);
      const data = current();
      const health = url.searchParams.get('health');
      const issues = (id: string) =>
        data.items.some(
          (item) =>
            item.sessionId === id && (item.state === 'FAILED' || item.state === 'ABANDONED'),
        );
      const rows = data.sessions
        .filter((s) => inRange(s.firstBuyAt, url))
        .filter((s) => !health || issues(s.id) === (health === 'ISSUES'))
        .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
      return HttpResponse.json(page(rows, url));
    }),
    http.get('/api/automation/sessions/:id', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const data = current();
      const session = data.sessions.find((s) => s.id === params.id);
      if (!session)
        return error(
          404,
          'AUTOMATION_SESSION_NOT_FOUND',
          `No automation session ${String(params.id)}`,
        );
      const detail: AutomationSessionDetail = {
        session,
        items: data.items.filter((item) => item.sessionId === session.id),
      };
      return HttpResponse.json(detail);
    }),
    http.post('/api/automation/sessions/:id/close', async ({ params }) => {
      await wait();
      const session = db.sessions.find((s) => s.id === params.id);
      if (!session)
        return error(
          404,
          'AUTOMATION_SESSION_NOT_FOUND',
          `No automation session ${String(params.id)}`,
        );
      if (session.state !== 'CLOSED') {
        for (const item of db.items) {
          if (
            item.sessionId !== session.id ||
            ['SIMULATED', 'FAILED', 'ABANDONED', 'SKIPPED_NOT_FIRST_BUY'].includes(item.state)
          )
            continue;
          session.itemCounts[item.state] = (session.itemCounts[item.state] ?? 0) - 1;
          item.state = 'ABANDONED';
          session.itemCounts.ABANDONED = (session.itemCounts.ABANDONED ?? 0) + 1;
        }
        session.state = 'CLOSED';
        session.closedAt = new Date().toISOString();
        session.closeReason = 'MANUAL';
        if (db.status.openSessionId === session.id)
          db.status = { ...db.status, openSessionId: null };
      }
      return HttpResponse.json({
        session,
        items: db.items.filter((i) => i.sessionId === session.id),
      });
    }),
    http.get('/api/automation/items/:id', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const data = current();
      const item = data.items.find((i) => i.id === params.id);
      if (!item)
        return error(404, 'AUTOMATION_ITEM_NOT_FOUND', `No automation item ${String(params.id)}`);
      return HttpResponse.json({
        item,
        sweepRuns: data.sweepRuns.filter((r) => r.itemId === item.id),
      });
    }),
    http.get('/api/automation/items/:id/events', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const data = current();
      const item = data.items.find((i) => i.id === params.id);
      if (!item)
        return error(404, 'AUTOMATION_ITEM_NOT_FOUND', `No automation item ${String(params.id)}`);
      return HttpResponse.json({ itemId: item.id, events: mockItemEvents(data, item) });
    }),
    // The capture operations automation buys ran; any other capture falls through to the capture mocks.
    http.get('/api/captures/:id/operations/:operationId', async ({ params }) => {
      const operation = mockCaptureOperation(
        current(),
        String(params.id),
        String(params.operationId),
      );
      if (!operation) return undefined;
      await wait();
      return HttpResponse.json(operation);
    }),
    http.get('/api/automation/days', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const days = span(new URL(request.url));
      if (days instanceof Response) return days;
      const data = current();
      const now = Date.now();
      return HttpResponse.json({
        from: days[0],
        to: days[days.length - 1],
        items: [...days].reverse().map((d) => daySummary(data, d, now)),
      });
    }),
    http.get('/api/automation/days/:date', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const date = String(params.date);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date))
        return error(400, 'INVALID_REQUEST', 'Request parameter has an invalid value', {
          date: 'has an invalid value',
        });
      return HttpResponse.json(dayDetail(current(), date, Date.now()));
    }),
    http.get('/api/automation/evaluations', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const url = new URL(request.url);
      const days = span(url);
      if (days instanceof Response) return days;
      const data = current();
      const now = Date.now();
      const label = url.searchParams.get('label');
      const status = url.searchParams.get('status') as EvaluationStatus | null;
      let rows = days.flatMap((d) => dayRows(data, d, now));
      rows = rows.filter((e) => (!label || e.label === label) && (!status || e.status === status));
      rows =
        url.searchParams.get('sort') === 'day,desc'
          ? rows.sort((a, b) => b.day.localeCompare(a.day) || compareEvaluations(a, b))
          : rows.sort(compareEvaluations);
      return HttpResponse.json(page(rows, url));
    }),
    http.get('/api/automation/sweep-runs', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const url = new URL(request.url);
      const refused = instantRangeRefusal(url);
      if (refused) return refused;
      const data = current();
      const items = new Map(data.items.map((i) => [i.id, i]));
      const sessionId = url.searchParams.get('sessionId');
      const mint = url.searchParams.get('mint');
      const planId = url.searchParams.get('planId');
      const label = url.searchParams.get('label');
      const outcome = url.searchParams.get('outcome');
      const rows = data.sweepRuns
        .map((run) => {
          const item = items.get(run.itemId)!;
          return { ...run, mint: item.mint, signature: item.signature, blockTime: item.blockTime };
        })
        .filter(
          (r) =>
            (!sessionId || r.sessionId === sessionId) &&
            (!mint || r.mint === mint) &&
            (!planId || r.planId === planId) &&
            (!label || r.label === label) &&
            (!outcome || r.outcome === outcome) &&
            inRange(r.blockTime, url),
        )
        .sort(
          (a, b) =>
            Date.parse(b.blockTime ?? '') - Date.parse(a.blockTime ?? '') ||
            a.label.localeCompare(b.label),
        );
      return HttpResponse.json(page(rows, url));
    }),
    http.get('/api/automation/variations', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const state = new URL(request.url).searchParams.get('state');
      if (state != null && state !== 'ACTIVE' && state !== 'ARCHIVED')
        return error(400, 'INVALID_REQUEST', 'Request parameter has an invalid value', {
          state: 'must be ACTIVE or ARCHIVED',
        });
      const data = current();
      return HttpResponse.json({
        planId: data.status.sweepPlanId,
        items: data.variations.filter((v) => !state || v.state === state),
      });
    }),
    http.get('/api/automation/variations/:label', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const found = current().variations.find((v) => v.label === params.label);
      return found
        ? HttpResponse.json(found)
        : error(
            404,
            'AUTOMATION_VARIATION_NOT_FOUND',
            `No automation variation ${String(params.label)}`,
          );
    }),
    http.post('/api/automation/variations/:label/edit', async ({ params, request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const label = String(params.label);
      const old = db.variations.find((v) => v.label === label);
      if (!old)
        return error(404, 'AUTOMATION_VARIATION_NOT_FOUND', `No automation variation ${label}`);
      const body = (await request.json().catch(() => ({}))) as Partial<VariationEditRequest>;
      const requestId = body.requestId;
      if (typeof requestId !== 'string' || requestId.trim() === '')
        return error(400, 'INVALID_REQUEST', 'requestId is missing', {
          requestId: 'must not be blank',
        });
      if (requestId.length > MAX_EDIT_REQUEST_ID)
        return error(400, 'INVALID_REQUEST', 'requestId is too long', {
          requestId: `must be at most ${MAX_EDIT_REQUEST_ID} characters`,
        });
      const repeated = editRequestIds.get(requestId);
      if (repeated)
        return HttpResponse.json(
          db.variations.find((v) => v.label === repeated),
          { status: 200 },
        );
      if (mockState() === 'variation-conflict')
        return error(409, 'AUTOMATION_CONFLICT', 'The variation was changed concurrently');
      if (old.state === 'ARCHIVED')
        return error(409, 'VARIATION_ARCHIVED', `Variation ${label} is archived`);
      if (old.differsFromAgreed.length === 0)
        return error(409, 'VARIATION_BASELINE', `Variation ${label} is the agreed baseline`);
      const refusal = completeConfigRefusal(body.config) ?? configRefusal(body.config!);
      if (refusal) return refusal;
      const config = configOf(body.config!, old.config);
      if (sameConfig(config, old.config))
        return error(409, 'VARIATION_UNCHANGED', `The configuration equals ${label}'s own`);
      const twin = db.variations.find(
        (v) => v.state === 'ACTIVE' && v.label !== label && sameConfig(v.config, config),
      );
      if (twin)
        return error(409, 'VARIATION_DUPLICATE', `Variation ${twin.label} has this configuration`, {
          label: twin.label,
        });
      if (mockState() === 'variation-limit' || nextSeq() >= MAX_VARIATIONS_PER_PLAN)
        return error(
          409,
          'VARIATION_LIMIT_REACHED',
          `The plan already holds ${MAX_VARIATIONS_PER_PLAN} variations`,
        );
      return HttpResponse.json(applyEdit(old, config, requestId), { status: 201 });
    }),
    http.get('/api/automation/backfills', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const rows = current().backfills.map(withProgress);
      return HttpResponse.json(page(rows, new URL(request.url)));
    }),
    http.get('/api/automation/backfills/:id', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const found = current().backfills.find((b) => b.id === params.id);
      return found
        ? HttpResponse.json(withProgress(found))
        : error(404, 'AUTOMATION_BACKFILL_NOT_FOUND', `No backfill ${String(params.id)}`);
    }),
    http.post('/api/automation/backfills', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const body = (await request.json()) as Partial<BackfillCreateRequest>;
      const data = current();
      const replay = data.backfills.find((b) => b.requestId === body.requestId);
      if (replay) return HttpResponse.json(withProgress(replay), { status: 201 });
      if (!data.status.enabled) return error(409, 'AUTOMATION_DISABLED', 'Automation is disabled');
      const fields = validate(body);
      if (Object.keys(fields).length > 0)
        return error(400, 'INVALID_REQUEST', 'The backfill request is invalid', fields);
      const active = data.backfills
        .map(withProgress)
        .find((b) => ['QUEUED', 'SCANNING', 'CAPTURING'].includes(b.state));
      if (active)
        return error(
          409,
          'BACKFILL_ACTIVE',
          `Backfill ${active.id} is still active for the wallet`,
        );
      if (!body.dryRun && !data.status.captureEnabled)
        return error(409, 'CAPTURE_DISABLED', 'Capture is disabled; only a dry run can start');
      const rangeStart = mockDayStart(body.fromDay!);
      let rangeEnd = mockDayStart(mockAddDays(body.toDay!, 1));
      const floor = data.status.coveredFrom ? Date.parse(data.status.coveredFrom) : null;
      const filtered =
        (body.mints?.length ?? 0) > 0 ||
        (body.minSignalSol != null && Number(body.minSignalSol) > 1.5);
      if (!body.dryRun && floor != null) {
        rangeEnd = Math.min(rangeEnd, floor);
        if (rangeStart >= rangeEnd)
          return error(
            409,
            'BACKFILL_OVERLAPS_COVERAGE',
            'The range is already covered by live discovery',
          );
      }
      const id = `bf-${crypto.randomUUID().replaceAll('-', '').slice(0, 20)}`;
      const now = new Date().toISOString();
      const created: AutomationBackfill = {
        id,
        requestId: body.requestId ?? id,
        wallet: data.status.wallet ?? '',
        fromDay: body.fromDay!,
        toDay: body.toDay!,
        rangeStart: new Date(rangeStart).toISOString(),
        rangeEnd: new Date(rangeEnd).toISOString(),
        dryRun: body.dryRun ?? true,
        minSignalLamports: body.minSignalSol
          ? String(Math.round(Number(body.minSignalSol) * 1e9))
          : null,
        mints: body.mints ?? [],
        requestBudget: body.requestBudget!,
        state: 'QUEUED',
        discoveryRequests: 0,
        itemRequests: 0,
        requestsSpent: 0,
        actionsScanned: 0,
        buysFound: 0,
        buysPassing: 0,
        itemsCreated: 0,
        skippedByFilter: 0,
        skippedNoBlockTime: 0,
        scannedTo: null,
        filtered,
        summary: null,
        estimatedRequests: null,
        itemCounts: {},
        createdAt: now,
        updatedAt: now,
        finishedAt: null,
      };
      db.backfills.unshift(created);
      db.status = { ...db.status, activeBackfillId: id };
      startedAt.set(id, Date.now());
      return HttpResponse.json(withProgress(created), { status: 201 });
    }),
    http.post('/api/automation/items/:id/retry', async ({ params, request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const requestId = await retryRequestId(request);
      if (requestId instanceof Response) return requestId;
      const item = db.items.find((i) => i.id === params.id);
      if (!item)
        return error(404, 'AUTOMATION_ITEM_NOT_FOUND', `No automation item ${String(params.id)}`);
      if (retryRequestIds.get(item.id) !== requestId) {
        const refusal = retryRefusal(item);
        if (refusal) return refusalResponse(item, refusal);
        applyRetry(item, requestId);
      }
      return HttpResponse.json({
        item,
        sweepRuns: db.sweepRuns.filter((r) => r.itemId === item.id),
      });
    }),
    http.post('/api/automation/days/:date/retry', async ({ params, request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const date = String(params.date);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date))
        return error(400, 'INVALID_REQUEST', 'Request parameter has an invalid value', {
          date: 'has an invalid value',
        });
      const requestId = await retryRequestId(request);
      if (requestId instanceof Response) return requestId;
      const sessionIds = new Set(db.sessions.filter((s) => s.tradingDay === date).map((s) => s.id));
      const result: AutomationDayRetryResult = {
        day: date,
        retried: [],
        unchanged: [],
        refused: {},
      };
      const items = db.items
        .filter((i) => sessionIds.has(i.sessionId))
        .sort((a, b) => (a.blockTime ?? '').localeCompare(b.blockTime ?? ''));
      for (const item of items) {
        const itemRequestId = dayItemRequestId(requestId, item.id);
        if (retryRequestIds.get(item.id) === itemRequestId) {
          result.unchanged.push(item.id);
          continue;
        }
        if (item.state !== 'FAILED') continue;
        const refusal = retryRefusal(item);
        if (refusal) result.refused[item.id] = refusal;
        else {
          applyRetry(item, itemRequestId);
          result.retried.push(item.id);
        }
      }
      return HttpResponse.json(result);
    }),
    http.post('/api/automation/backfills/:id/cancel', async ({ params }) => {
      await wait();
      const index = db.backfills.findIndex((b) => b.id === params.id);
      if (index < 0)
        return error(404, 'AUTOMATION_BACKFILL_NOT_FOUND', `No backfill ${String(params.id)}`);
      const shown = withProgress(db.backfills[index]!);
      if (['DONE', 'CANCELLED'].includes(shown.state)) return HttpResponse.json(shown);
      const cancelled: AutomationBackfill = {
        ...shown,
        state: 'CANCELLED',
        finishedAt: new Date().toISOString(),
      };
      startedAt.delete(cancelled.id);
      // As the backend does: its unfinished buys are abandoned and its sessions closed.
      const terminal = ['SIMULATED', 'FAILED', 'ABANDONED', 'SKIPPED_NOT_FIRST_BUY'];
      const counts: Record<string, number> = {};
      for (const item of db.items.filter((i) => i.backfillId === cancelled.id)) {
        if (!terminal.includes(item.state)) item.state = 'ABANDONED';
        counts[item.state] = (counts[item.state] ?? 0) + 1;
      }
      for (const session of db.sessions.filter((s) => s.backfillId === cancelled.id)) {
        const own = db.items.filter((i) => i.sessionId === session.id);
        session.itemCounts = Object.fromEntries(
          own.map((i) => [i.state, own.filter((o) => o.state === i.state).length]),
        );
        if (session.state !== 'CLOSED') {
          session.state = 'CLOSED';
          session.closeReason = 'MANUAL';
          session.closedAt = cancelled.finishedAt;
        }
      }
      if (Object.keys(counts).length > 0) cancelled.itemCounts = counts;
      db.backfills[index] = cancelled;
      if (db.status.activeBackfillId === cancelled.id)
        db.status = { ...db.status, activeBackfillId: null };
      return HttpResponse.json(cancelled);
    }),
  ];
}

import { http, HttpResponse } from 'msw';
import type {
  AutomationBackfill,
  AutomationSessionDetail,
  BackfillCreateRequest,
  EvaluationStatus,
} from '../api/automation';
import { isSolanaAddress } from '../lib/base58';
import {
  compareEvaluations,
  createAutomationDb,
  dayDetail,
  dayRows,
  daySummary,
  discoveryOnly,
  mockAddDays,
  mockDayOf,
  mockDayStart,
  type AutomationDb,
} from './automationFixtures';
import { mockState } from './previewState';

/**
 * `/api/automation` mocks with the backend's filters, sorts, paging, refusals and day scoring. Mock states:
 * `automation-off` answers 404 like a backend with automation disabled; `automation-phase0` is discovery only (every day
 * pending); `automation-empty` has no buys yet; `automation-idle` has no active backfill, so a new one can start.
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

export function resetAutomationMocks(next: AutomationDb = createAutomationDb()): void {
  db = next;
  runQueuedAt = null;
  startedAt.clear();
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

function inRange(iso: string, url: URL): boolean {
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to');
  const t = Date.parse(iso);
  return (!from || t >= Date.parse(from)) && (!to || t < Date.parse(to));
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
  const min = body.dryRun ? 100 : 1222;
  if (body.requestBudget == null || body.requestBudget < min || body.requestBudget > 20_000)
    fields.requestBudget = `must be between ${min} and 20000`;
  if (body.minSignalSol != null && Number(body.minSignalSol) < 1.5)
    fields.minSignalSol = 'must be at least the capture filter, 1.5 SOL';
  if (body.mints && (body.mints.length > 50 || body.mints.some((m) => !isSolanaAddress(m))))
    fields.mints = 'must be 1 to 50 valid base58 addresses';
  return fields;
}

export function createAutomationHandlers(wait: () => Promise<void>) {
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
      const data = current();
      const items = new Map(data.items.map((i) => [i.id, i]));
      const sessionId = url.searchParams.get('sessionId');
      const mint = url.searchParams.get('mint');
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
            (!label || r.label === label) &&
            (!outcome || r.outcome === outcome),
        )
        .sort(
          (a, b) =>
            Date.parse(b.blockTime ?? '') - Date.parse(a.blockTime ?? '') ||
            a.label.localeCompare(b.label),
        );
      return HttpResponse.json(page(rows, url));
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

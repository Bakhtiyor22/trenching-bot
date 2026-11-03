import { http, HttpResponse } from 'msw';
import type { AutomationSessionDetail, EvaluationStatus } from '../api/automation';
import {
  compareEvaluations,
  createAutomationDb,
  discoveryOnly,
  type AutomationDb,
} from './automationFixtures';
import { mockState } from './previewState';

/**
 * `/api/automation` mocks with the backend's filters, sorts and paging. `?mockState=automation-off` answers 404 like a
 * backend with automation disabled; `automation-discovery` is phase 0; `automation-empty` has no nights yet.
 */

let db: AutomationDb = createAutomationDb();

export function resetAutomationMocks(next: AutomationDb = createAutomationDb()): void {
  db = next;
}

function current(): AutomationDb {
  const state = mockState();
  if (state === 'automation-discovery') return discoveryOnly(db);
  if (state === 'automation-empty')
    return {
      ...db,
      status: {
        ...db.status,
        openSessionId: null,
        watermark: null,
        requestsLast24h: 0,
        lastDiscoveryAt: null,
      },
      sessions: [],
      items: [],
      sweepRuns: [],
      evaluations: [],
    };
  return db;
}

const RANK: Record<EvaluationStatus, number> = { SUCCESS: 0, NO_SUCCESS: 1, NO_RESULT: 2 };

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

function notFound(code: string, message: string) {
  return HttpResponse.json({ code, message, fields: {} }, { status: 404 });
}

function detail(data: AutomationDb, id: string): AutomationSessionDetail | null {
  const session = data.sessions.find((s) => s.id === id);
  if (!session) return null;
  return {
    session,
    items: data.items
      .filter((item) => item.sessionId === id)
      .sort(
        (a, b) => Date.parse(a.blockTime ?? a.createdAt) - Date.parse(b.blockTime ?? b.createdAt),
      ),
    evaluations: data.evaluations.filter((e) => e.sessionId === id).sort(compareEvaluations),
  };
}

export function createAutomationHandlers(wait: () => Promise<void>) {
  const off = () =>
    mockState() === 'automation-off' ? new HttpResponse(null, { status: 404 }) : null;
  return [
    http.get('/api/automation/status', async () => {
      await wait();
      return off() ?? HttpResponse.json(current().status);
    }),
    http.get('/api/automation/sessions', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const url = new URL(request.url);
      const data = current();
      const status = url.searchParams.get('status') as EvaluationStatus | null;
      const health = url.searchParams.get('health');
      const issues = (id: string) =>
        data.items.some(
          (item) =>
            item.sessionId === id && (item.state === 'FAILED' || item.state === 'ABANDONED'),
        );
      let rows = data.sessions.filter((s) => inRange(s.firstBuyAt, url));
      if (status) rows = rows.filter((s) => s.bestStatus === status);
      if (health) rows = rows.filter((s) => issues(s.id) === (health === 'ISSUES'));
      const sort = url.searchParams.get('sort') ?? 'status';
      rows = [...rows].sort((a, b) => {
        if (sort === 'createdAt,desc') return Date.parse(b.createdAt) - Date.parse(a.createdAt);
        const ra = a.bestStatus ? RANK[a.bestStatus] : 3;
        const rb = b.bestStatus ? RANK[b.bestStatus] : 3;
        if (ra !== rb) return ra - rb;
        const best = (id: string, label: string | null) =>
          Number(
            data.evaluations.find((e) => e.sessionId === id && e.label === label)?.worstCasePnl ??
              -Infinity,
          );
        const byPnl = best(b.id, b.bestLabel) - best(a.id, a.bestLabel);
        return byPnl || Date.parse(b.createdAt) - Date.parse(a.createdAt);
      });
      return HttpResponse.json(page(rows, url));
    }),
    http.get('/api/automation/sessions/:id', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const found = detail(current(), String(params.id));
      return found
        ? HttpResponse.json(found)
        : notFound('AUTOMATION_SESSION_NOT_FOUND', `No automation session ${String(params.id)}`);
    }),
    http.post('/api/automation/sessions/:id/close', async ({ params }) => {
      await wait();
      const session = db.sessions.find((s) => s.id === params.id);
      if (!session)
        return notFound(
          'AUTOMATION_SESSION_NOT_FOUND',
          `No automation session ${String(params.id)}`,
        );
      if (session.state !== 'CLOSED') {
        const now = new Date().toISOString();
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
        session.closedAt = now;
        session.closeReason = 'MANUAL';
        db.status = { ...db.status, openSessionId: null };
      }
      return HttpResponse.json(detail(db, session.id));
    }),
    http.get('/api/automation/items/:id', async ({ params }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const data = current();
      const item = data.items.find((i) => i.id === params.id);
      if (!item)
        return notFound('AUTOMATION_ITEM_NOT_FOUND', `No automation item ${String(params.id)}`);
      return HttpResponse.json({
        item,
        sweepRuns: data.sweepRuns.filter((r) => r.itemId === item.id),
      });
    }),
    http.get('/api/automation/evaluations', async ({ request }) => {
      await wait();
      const blocked = off();
      if (blocked) return blocked;
      const url = new URL(request.url);
      const data = current();
      const firstBuy = new Map(data.sessions.map((s) => [s.id, s.firstBuyAt]));
      const planId = url.searchParams.get('planId');
      const label = url.searchParams.get('label');
      const status = url.searchParams.get('status');
      let rows = data.evaluations.filter(
        (e) =>
          (!planId || e.planId === planId) &&
          (!label || e.label === label) &&
          (!status || e.status === status) &&
          inRange(firstBuy.get(e.sessionId) ?? e.evaluatedAt, url),
      );
      rows =
        url.searchParams.get('sort') === 'evaluatedAt,desc'
          ? [...rows].sort((a, b) => Date.parse(b.evaluatedAt) - Date.parse(a.evaluatedAt))
          : [...rows].sort(compareEvaluations);
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
  ];
}

import type { AutomationItem, AutomationItemEvent, AutomationStep } from '../api/automation';
import type { CaptureOperation, CaptureStep } from '../api/captures';
import type { AutomationDb } from './automationFixtures';

/**
 * Offline token flow history (`GET /api/automation/items/{id}/events`), derived from each mock buy's current state the way
 * the pipeline would have written it, and the capture operations those events name
 * (`GET /api/captures/{id}/operations/{operationId}`). Buys found before {@link MOCK_HISTORY_FROM} only have the
 * `HISTORY_STARTED` event the backfill gives items that existed before the event table.
 */

export const MOCK_HISTORY_FROM = '2026-10-25T07:00:00.000Z';
/** The steps that run a capture operation, in pipeline order. */
const OPERATION_STEPS: AutomationStep[] = ['WALLET', 'MARKET', 'RATES', 'EXPORT'];
const STEP_ORDER: AutomationStep[] = [
  'CREATE_SESSION',
  'WALLET',
  'MARKET',
  'RATES',
  'EXPORT',
  'QUALIFY',
  'REGISTER',
  'SWEEP',
];
const FINISHED_CAPTURE = new Set(['CAPTURED', 'SIMULATED', 'SKIPPED_NOT_FIRST_BUY']);
const FAILED_ATTEMPT_REQUESTS = 9;
const MOCK_BUDGET = { requestBudget: 400, byteBudget: 50_000_000, operationTimeoutMinutes: 10 };
const BYTES_PER_REQUEST = 14_200;

type Draft = Omit<AutomationItemEvent, 'seq' | 'at'>;

const blank: Draft = {
  kind: 'STATE_CHANGED',
  fromState: null,
  toState: null,
  step: null,
  attempt: null,
  captureSessionId: null,
  operationId: null,
  requestsUsed: null,
  failureCode: null,
  failureMessage: null,
  actor: null,
  requestId: null,
  note: null,
  detail: null,
};

function operationIdOf(item: AutomationItem, step: AutomationStep, attempt: number): string {
  return `op-${item.id.slice(0, 10)}-${step.toLowerCase()}-${attempt}`;
}

/** The steps whose operation finished, and the one the buy stopped or is running at. */
function operationSteps(item: AutomationItem): {
  done: AutomationStep[];
  at: AutomationStep | null;
} {
  if (FINISHED_CAPTURE.has(item.state)) return { done: OPERATION_STEPS, at: null };
  const index = item.step ? STEP_ORDER.indexOf(item.step) : -1;
  const at = item.step && OPERATION_STEPS.includes(item.step) ? item.step : null;
  return { done: OPERATION_STEPS.filter((s) => STEP_ORDER.indexOf(s) < index), at };
}

function drafts(db: AutomationDb, item: AutomationItem): Draft[] {
  const out: Draft[] = [{ ...blank, toState: 'DISCOVERED' }];
  if (item.state === 'DISCOVERED') return out;
  out.push({
    ...blank,
    fromState: 'DISCOVERED',
    toState: 'WAITING',
    step: 'CREATE_SESSION',
    attempt: 1,
  });
  if (item.state === 'WAITING') return out;
  if (item.state === 'BLOCKED_BUDGET') {
    out.push({ ...blank, fromState: 'WAITING', toState: 'BLOCKED_BUDGET', step: 'WALLET' });
    return out;
  }
  out.push({ ...blank, fromState: 'WAITING', toState: 'CAPTURING', step: 'WALLET', attempt: 1 });
  const session = item.captureSessionId;
  const { done, at } = operationSteps(item);
  const perStep = Math.max(1, Math.floor((item.requestsUsed ?? 40) / OPERATION_STEPS.length));
  for (const step of done) {
    const operationId = operationIdOf(item, step, 1);
    const op = { ...blank, step, attempt: 1, captureSessionId: session, operationId };
    out.push({ ...op, kind: 'OPERATION_STARTED' });
    out.push({
      ...op,
      kind: 'OPERATION_ENDED',
      requestsUsed: perStep,
      detail: { operationState: 'SUCCEEDED' },
    });
  }
  if (item.state === 'CAPTURING' && at) {
    out.push({
      ...blank,
      kind: 'OPERATION_STARTED',
      step: at,
      attempt: item.stepAttempt,
      captureSessionId: session,
      operationId: item.operationId ?? operationIdOf(item, at, item.stepAttempt),
    });
  }
  if (item.state === 'FAILED' && item.step) {
    for (let attempt = 1; attempt <= item.stepAttempt; attempt += 1) {
      const operationId = operationIdOf(item, item.step, attempt);
      const op = { ...blank, step: item.step, attempt, captureSessionId: session, operationId };
      out.push({ ...op, kind: 'OPERATION_STARTED' });
      out.push({
        ...op,
        kind: 'ATTEMPT_FAILED',
        requestsUsed: FAILED_ATTEMPT_REQUESTS,
        failureCode: item.failureCode,
        failureMessage: item.failureMessage,
      });
    }
    out.push({
      ...blank,
      fromState: 'CAPTURING',
      toState: 'FAILED',
      step: item.step,
      attempt: item.stepAttempt,
      failureCode: item.failureCode,
      failureMessage: item.failureMessage,
    });
  }
  if (item.state === 'ABANDONED') {
    out.push({
      ...blank,
      fromState: 'CAPTURING',
      toState: 'ABANDONED',
      step: item.step,
      failureCode: item.failureCode,
      failureMessage: item.failureMessage,
      actor: 'console',
      requestId: `ui-close-${item.sessionId.slice(0, 12)}`,
    });
  }
  if (FINISHED_CAPTURE.has(item.state)) {
    out.push({ ...blank, fromState: 'CAPTURING', toState: 'CAPTURED', step: 'QUALIFY' });
    if (item.state === 'SKIPPED_NOT_FIRST_BUY') {
      out.push({
        ...blank,
        fromState: 'CAPTURED',
        toState: 'SKIPPED_NOT_FIRST_BUY',
        step: 'QUALIFY',
        note: 'Decu held this token before this buy.',
      });
    } else {
      out.push({ ...blank, fromState: 'CAPTURED', toState: 'CAPTURED', step: 'REGISTER' });
    }
  }
  if (item.state === 'SIMULATED') {
    for (const run of db.sweepRuns.filter((r) => r.itemId === item.id)) {
      out.push({
        ...blank,
        kind: 'SWEEP_ENTRY_RECORDED',
        step: 'SWEEP',
        detail: { planId: run.planId, label: run.label, runId: run.runId, outcome: run.outcome },
      });
    }
    out.push({ ...blank, fromState: 'CAPTURED', toState: 'SIMULATED', step: 'SWEEP' });
  }
  return out;
}

/** A buy's events in `seq` order, spread from its creation to its last update. */
export function mockItemEvents(db: AutomationDb, item: AutomationItem): AutomationItemEvent[] {
  const start = Date.parse(item.createdAt);
  if (start < Date.parse(MOCK_HISTORY_FROM)) {
    return [
      {
        ...blank,
        seq: 1,
        at: MOCK_HISTORY_FROM,
        kind: 'HISTORY_STARTED',
        toState: item.state,
        step: item.step,
        attempt: item.stepAttempt,
        requestsUsed: item.requestsUsed,
        failureCode: item.failureCode,
        failureMessage: item.failureMessage,
      },
    ];
  }
  const list = drafts(db, item);
  const span = Math.max(Date.parse(item.updatedAt) - start, list.length * 1000);
  const step = list.length > 1 ? span / (list.length - 1) : 0;
  return list.map((draft, i) => ({
    ...draft,
    seq: i + 1,
    at: new Date(Math.round(start + i * step)).toISOString(),
  }));
}

/** The capture operation an automation event names, as the capture API would answer it, or null. */
export function mockCaptureOperation(
  db: AutomationDb,
  sessionId: string,
  operationId: string,
): CaptureOperation | null {
  const item = db.items.find((i) => i.captureSessionId === sessionId);
  if (!item) return null;
  const events = mockItemEvents(db, item).filter((e) => e.operationId === operationId);
  const started = events.find((e) => e.kind === 'OPERATION_STARTED');
  if (!started) return null;
  const ended = events.find((e) => e.kind === 'OPERATION_ENDED' || e.kind === 'ATTEMPT_FAILED');
  const failed = ended?.kind === 'ATTEMPT_FAILED';
  const requests = ended?.requestsUsed ?? 12;
  return {
    id: operationId,
    requestId: `automation-${operationId}`,
    step: started.step as CaptureStep,
    attemptId: null,
    state: ended ? (failed ? 'FAILED' : 'SUCCEEDED') : 'RUNNING',
    startedAt: started.at,
    updatedAt: ended?.at ?? started.at,
    endedAt: ended?.at ?? null,
    budget: MOCK_BUDGET,
    scanPages: null,
    progress: {
      phase: ended ? 'DONE' : 'FETCHING',
      at: ended?.at ?? started.at,
      operationRequests: requests,
      operationBytes: requests * BYTES_PER_REQUEST,
      cumulativeRequests: null,
      cumulativeBytes: null,
      processed: requests * 3,
      selected: requests,
    },
    code: failed ? ended.failureCode : null,
    message: failed ? ended.failureMessage : null,
  };
}

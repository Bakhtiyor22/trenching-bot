import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { API_BASE, ApiError, getJson, postJson, withQuery } from './client';
import type { DecimalString, IsoInstant } from './types';

/**
 * The automation read API (`/api/automation`, backend `console.automation`). It exists only while automation and the
 * console are enabled; otherwise every path answers 404, which {@link isAutomationOff} recognises.
 */

export type EvaluationStatus = 'SUCCESS' | 'NO_SUCCESS' | 'NO_RESULT';
export type AutomationSessionState = 'OPEN' | 'CLOSING' | 'CLOSED';
export type AutomationItemState =
  | 'DISCOVERED'
  | 'WAITING'
  | 'BLOCKED_BUDGET'
  | 'CAPTURING'
  | 'CAPTURED'
  | 'SIMULATED'
  | 'FAILED'
  | 'ABANDONED'
  | 'SKIPPED_NOT_FIRST_BUY';
export type AutomationStep =
  'CREATE_SESSION' | 'WALLET' | 'MARKET' | 'RATES' | 'EXPORT' | 'QUALIFY' | 'REGISTER' | 'SWEEP';
export type SessionHealth = 'ISSUES' | 'OK';
export type SessionSort = 'status' | 'createdAt,desc';
export type EvaluationSort = 'status' | 'evaluatedAt,desc';

export const ITEM_STATES: AutomationItemState[] = [
  'DISCOVERED',
  'WAITING',
  'BLOCKED_BUDGET',
  'CAPTURING',
  'CAPTURED',
  'SIMULATED',
  'FAILED',
  'ABANDONED',
  'SKIPPED_NOT_FIRST_BUY',
];
export const STEPS: AutomationStep[] = [
  'CREATE_SESSION',
  'WALLET',
  'MARKET',
  'RATES',
  'EXPORT',
  'QUALIFY',
  'REGISTER',
  'SWEEP',
];
export const EVALUATION_STATUSES: EvaluationStatus[] = ['SUCCESS', 'NO_SUCCESS', 'NO_RESULT'];

/** The backend caps every automation page at this many rows. */
export const AUTOMATION_MAX_PAGE_SIZE = 100;

export interface AutomationWatermark {
  slot: number;
  signature: string;
  instructionIndex: number;
  innerIndex: number;
  updatedAt: IsoInstant;
  /** Set while discovery works through a backlog larger than one scan. */
  resumeBefore: string | null;
}

/** `pollFrom` and `pollTo` are local `HH:mm` times in `zone` (e.g. "+05:00"). */
export interface AutomationStatus {
  enabled: boolean;
  captureEnabled: boolean;
  wallet: string | null;
  sweepPlanId: string;
  zone: string;
  pollFrom: string;
  pollTo: string;
  inPollWindow: boolean;
  watermark: AutomationWatermark | null;
  requestsLast24h: number;
  maxRequestsPer24h: number;
  rpcConfigured: boolean;
  lastTickAt: IsoInstant | null;
  nextTickAt: IsoInstant | null;
  lastDiscoveryAt: IsoInstant | null;
  discoveryProblem: string | null;
  openSessionId: string | null;
}

/** One night of buys. `itemCounts` has every item state, zeros included; `best*` stay null until it is scored. */
export interface AutomationSession {
  id: string;
  wallet: string;
  firstItemId: string;
  firstBuyAt: IsoInstant;
  lastBuyAt: IsoInstant;
  state: AutomationSessionState;
  closedAt: IsoInstant | null;
  closeReason: 'IDLE' | 'MANUAL' | null;
  belowFilterCount: number;
  createdAt: IsoInstant;
  itemCounts: Partial<Record<AutomationItemState, number>>;
  requestsUsed: number;
  bestStatus: EvaluationStatus | null;
  bestLabel: string | null;
}

/** `curvePaymentLamports` is an exact integer string, fees excluded. */
export interface AutomationItem {
  id: string;
  sessionId: string;
  wallet: string;
  mint: string;
  signature: string;
  instructionIndex: number;
  innerIndex: number;
  slot: number;
  blockTime: IsoInstant | null;
  curvePaymentLamports: string;
  state: AutomationItemState;
  step: AutomationStep | null;
  stepAttempt: number;
  captureSessionId: string | null;
  operationId: string | null;
  datasetId: string | null;
  classification: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  nextActionAt: IsoInstant | null;
  requestsUsed: number | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

/** One sweep entry replayed on a buy. Without a `runId`, `outcome` is the entry's refusal code. */
export interface AutomationSweepRun {
  itemId: string;
  sessionId: string;
  planId: string;
  label: string;
  runId: string | null;
  configurationHash: string | null;
  engineVersion: string | null;
  outcome: string;
  positionState: string | null;
  signal: boolean;
  realizedPnl: DecimalString;
  worstCasePnl: DecimalString;
  createdAt: IsoInstant;
}

export interface AutomationSweepRunRow extends AutomationSweepRun {
  mint: string;
  signature: string;
  blockTime: IsoInstant | null;
}

/** One variation's score over the window ending at `sessionId`; `windowSessionIds` oldest first. */
export interface AutomationEvaluation {
  sessionId: string;
  planId: string;
  label: string;
  windowSessionIds: string[];
  signals: number;
  scored: number;
  unscored: number;
  realizedPnl: DecimalString;
  worstCasePnl: DecimalString;
  status: EvaluationStatus;
  evaluatedAt: IsoInstant;
}

export interface AutomationPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface AutomationSessionDetail {
  session: AutomationSession;
  items: AutomationItem[];
  evaluations: AutomationEvaluation[];
}

export interface AutomationItemDetail {
  item: AutomationItem;
  sweepRuns: AutomationSweepRun[];
}

export interface SessionQuery {
  page: number;
  size: number;
  from?: IsoInstant | null;
  to?: IsoInstant | null;
  status?: EvaluationStatus | null;
  health?: SessionHealth | null;
  sort?: SessionSort;
}

export interface EvaluationQuery {
  planId?: string | null;
  label?: string | null;
  status?: EvaluationStatus | null;
  from?: IsoInstant | null;
  to?: IsoInstant | null;
  sort?: EvaluationSort;
  page: number;
  size: number;
}

export interface SweepRunQuery {
  sessionId?: string | null;
  mint?: string | null;
  label?: string | null;
  outcome?: string | null;
  page: number;
  size: number;
}

const BASE = `${API_BASE}/automation`;
const enc = encodeURIComponent;

export const automationApi = {
  status: (signal?: AbortSignal) => getJson<AutomationStatus>(`${BASE}/status`, signal),
  sessions: (query: SessionQuery, signal?: AbortSignal) =>
    getJson<AutomationPage<AutomationSession>>(withQuery(`${BASE}/sessions`, { ...query }), signal),
  session: (id: string, signal?: AbortSignal) =>
    getJson<AutomationSessionDetail>(`${BASE}/sessions/${enc(id)}`, signal),
  closeSession: (id: string, requestId: string) =>
    postJson<AutomationSessionDetail>(`${BASE}/sessions/${enc(id)}/close`, { requestId }),
  item: (id: string, signal?: AbortSignal) =>
    getJson<AutomationItemDetail>(`${BASE}/items/${enc(id)}`, signal),
  evaluations: (query: EvaluationQuery, signal?: AbortSignal) =>
    getJson<AutomationPage<AutomationEvaluation>>(
      withQuery(`${BASE}/evaluations`, { ...query }),
      signal,
    ),
  sweepRuns: (query: SweepRunQuery, signal?: AbortSignal) =>
    getJson<AutomationPage<AutomationSweepRunRow>>(
      withQuery(`${BASE}/sweep-runs`, { ...query }),
      signal,
    ),
};

/** True when the automation endpoints are absent: automation or the console is switched off on the backend. */
export function isAutomationOff(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'HTTP_ERROR';
}

/** Matches the API usage page: the status is refreshed every 30 seconds while the tab is visible. */
export const AUTOMATION_STATUS_POLL_MS = 30_000;
/** Lists change at most once per tick (minutes), so a minute is fresh enough. */
const AUTOMATION_LIST_POLL_MS = 60_000;

export const automationKeys = {
  all: ['automation'] as const,
  status: ['automation', 'status'] as const,
  sessions: (query: SessionQuery) => ['automation', 'sessions', query] as const,
  session: (id: string) => ['automation', 'session', id] as const,
  item: (id: string) => ['automation', 'item', id] as const,
  evaluations: (query: EvaluationQuery) => ['automation', 'evaluations', query] as const,
  sweepRuns: (query: SweepRunQuery) => ['automation', 'sweep-runs', query] as const,
};

function visiblePoll(ms: number) {
  return () =>
    typeof document !== 'undefined' && document.visibilityState === 'hidden' ? false : ms;
}

export function useAutomationStatus() {
  return useQuery({
    queryKey: automationKeys.status,
    queryFn: ({ signal }) => automationApi.status(signal),
    refetchInterval: visiblePoll(AUTOMATION_STATUS_POLL_MS),
  });
}

export function useAutomationSessions(query: SessionQuery, enabled = true) {
  return useQuery({
    queryKey: automationKeys.sessions(query),
    queryFn: ({ signal }) => automationApi.sessions(query, signal),
    placeholderData: keepPreviousData,
    refetchInterval: visiblePoll(AUTOMATION_LIST_POLL_MS),
    enabled,
  });
}

export function useAutomationSession(id: string | null) {
  return useQuery({
    queryKey: automationKeys.session(id ?? ''),
    queryFn: ({ signal }) => automationApi.session(id!, signal),
    enabled: id != null,
    refetchInterval: visiblePoll(AUTOMATION_LIST_POLL_MS),
  });
}

export function useAutomationItem(id: string | null) {
  return useQuery({
    queryKey: automationKeys.item(id ?? ''),
    queryFn: ({ signal }) => automationApi.item(id!, signal),
    enabled: id != null,
  });
}

export function useAutomationEvaluations(query: EvaluationQuery, enabled = true) {
  return useQuery({
    queryKey: automationKeys.evaluations(query),
    queryFn: ({ signal }) => automationApi.evaluations(query, signal),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useAutomationSweepRuns(query: SweepRunQuery, enabled = true) {
  return useQuery({
    queryKey: automationKeys.sweepRuns(query),
    queryFn: ({ signal }) => automationApi.sweepRuns(query, signal),
    placeholderData: keepPreviousData,
    enabled,
  });
}

const CLOSE_REQUEST_KEY = 'automation-close:';

/**
 * Closes an open night now. The request id is kept per night in session storage until the call succeeds, so a retry
 * after a lost answer is recognised by the backend instead of counted twice.
 */
export function useCloseSession(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const key = `${CLOSE_REQUEST_KEY}${id}`;
      let requestId: string | null = null;
      try {
        requestId = sessionStorage.getItem(key);
      } catch {
        // Storage can be unavailable; a fresh id is still correct for a single attempt.
      }
      if (!requestId) {
        requestId = `ui-close-${crypto.randomUUID()}`;
        try {
          sessionStorage.setItem(key, requestId);
        } catch {
          // See above.
        }
      }
      const detail = await automationApi.closeSession(id, requestId);
      try {
        sessionStorage.removeItem(key);
      } catch {
        // See above.
      }
      return detail;
    },
    onSuccess: (detail) => {
      client.setQueryData(automationKeys.session(id), detail);
      void client.invalidateQueries({ queryKey: automationKeys.all });
    },
  });
}

/**
 * Every evaluation matching `query`, read page by page (the backend caps a page at 100 rows): 30 nights of a 10-entry
 * plan are 3 requests.
 */
export function useAllAutomationEvaluations(
  query: Omit<EvaluationQuery, 'page' | 'size'>,
  enabled = true,
) {
  return useQuery({
    queryKey: [...automationKeys.all, 'evaluations-all', query] as const,
    queryFn: async ({ signal }) => {
      const rows: AutomationEvaluation[] = [];
      for (let page = 0; ; page += 1) {
        const result = await automationApi.evaluations(
          { ...query, page, size: AUTOMATION_MAX_PAGE_SIZE },
          signal,
        );
        rows.push(...result.items);
        if (result.items.length === 0 || rows.length >= result.total) return rows;
      }
    },
    placeholderData: keepPreviousData,
    refetchInterval: visiblePoll(AUTOMATION_LIST_POLL_MS),
    enabled,
  });
}

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { API_BASE, ApiError, getJson, postJson, withQuery } from './client';
import type { DecimalString, IsoInstant } from './types';

/**
 * The automation API (`/api/automation`, backend `console.automation`). It exists only while automation and the console
 * are enabled; otherwise every path answers 404, which {@link isAutomationOff} recognises. Results are grouped and scored
 * by trading day: 12:00 to 12:00 in the automation's zone, named by its starting date.
 */

/** A trading day, or any calendar date the API takes: `YYYY-MM-DD`. */
export type IsoDate = string;
/** The three verdicts a complete 7-day window can get. */
export type EvaluationVerdict = 'SUCCESS' | 'NO_SUCCESS' | 'NO_RESULT';
/** A verdict, or PENDING while a day of the window is incomplete. PENDING is a state, not a fourth verdict. */
export type EvaluationStatus = EvaluationVerdict | 'PENDING';
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
export type EvaluationSort = 'status' | 'day,desc';
export type DiscoveryMode = 'ALWAYS' | 'WINDOW';
export type BackfillState =
  'QUEUED' | 'SCANNING' | 'CAPTURING' | 'DONE' | 'STOPPED_BUDGET' | 'CANCELLED';

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
export const EVALUATION_STATUSES: EvaluationStatus[] = [
  'SUCCESS',
  'NO_SUCCESS',
  'NO_RESULT',
  'PENDING',
];
/** A backfill in these states still runs; at most one per wallet. */
export const ACTIVE_BACKFILL_STATES: BackfillState[] = ['QUEUED', 'SCANNING', 'CAPTURING'];

/** The backend caps every automation page at this many rows. */
export const AUTOMATION_MAX_PAGE_SIZE = 100;
/** `/days` and `/evaluations` read at most this many trading days at once. */
export const MAX_DAYS_PER_LIST = 62;

export interface AutomationWatermark {
  slot: number;
  signature: string;
  instructionIndex: number;
  innerIndex: number;
  updatedAt: IsoInstant;
  /** Set while discovery works through a backlog larger than one scan. */
  resumeBefore: string | null;
}

/**
 * `pollFrom`, `pollTo` and `tradingDayStart` are local `HH:mm` times in `zone` (e.g. "+05:00"). `inPollWindow` is whether
 * scheduled discovery scans now under `mode`. `coveredFrom` is where live discovery coverage starts.
 */
export interface AutomationStatus {
  enabled: boolean;
  captureEnabled: boolean;
  wallet: string | null;
  sweepPlanId: string;
  zone: string;
  mode: DiscoveryMode;
  pollFrom: string;
  pollTo: string;
  inPollWindow: boolean;
  manualRunQueued: boolean;
  tradingDayStart: string;
  coveredFrom: IsoInstant | null;
  watermark: AutomationWatermark | null;
  requestsLast24h: number;
  maxRequestsPer24h: number;
  rpcConfigured: boolean;
  lastTickAt: IsoInstant | null;
  nextTickAt: IsoInstant | null;
  lastDiscoveryAt: IsoInstant | null;
  discoveryProblem: string | null;
  openSessionId: string | null;
  activeBackfillId: string | null;
}

/** An idle-gap group of buys inside one trading day. `itemCounts` has every item state, zeros included. */
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
  /** The trading day of its first buy. */
  tradingDay: IsoDate;
  /** The backfill whose buys it groups; null for live discovery. */
  backfillId: string | null;
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
  backfillId?: string | null;
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

/**
 * One variation's row for trading day `day`, over `windowDays` (oldest first, ending with the day). A PENDING row names
 * the oldest incomplete day of its window in `pendingDay` and still carries the counts so far.
 */
export interface AutomationEvaluation {
  day: IsoDate;
  planId: string;
  label: string;
  windowDays: IsoDate[];
  signals: number;
  scored: number;
  unscored: number;
  realizedPnl: DecimalString;
  worstCasePnl: DecimalString;
  status: EvaluationStatus;
  pendingDay: IsoDate | null;
}

/** A trading day `[from, to)`. `bestStatus` is PENDING (with `pendingDay`) while a day of its window is incomplete. */
export interface AutomationDaySummary {
  day: IsoDate;
  from: IsoInstant;
  to: IsoInstant;
  covered: boolean;
  complete: boolean;
  sessions: number;
  buys: number;
  belowFilter: number;
  captured: number;
  simulated: number;
  failed: number;
  abandoned: number;
  requestsUsed: number;
  bestStatus: EvaluationStatus | null;
  bestLabel: string | null;
  pendingDay: IsoDate | null;
}

/** Newest day first. */
export interface AutomationDayList {
  from: IsoDate;
  to: IsoDate;
  items: AutomationDaySummary[];
}

export interface AutomationTimeRange {
  from: IsoInstant;
  to: IsoInstant;
}

export interface AutomationDayDetail {
  summary: AutomationDaySummary;
  coverage: { covered: boolean; ranges: AutomationTimeRange[] };
  sessions: AutomationSession[];
  items: AutomationItem[];
  evaluations: AutomationEvaluation[];
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
}

export interface AutomationItemDetail {
  item: AutomationItem;
  sweepRuns: AutomationSweepRun[];
}

/** What "run now" did: queued a pass, or found one already queued and added nothing. */
export interface ManualRunResult {
  queued: boolean;
  alreadyQueued: boolean;
}

/** Body of `POST /backfills`. Days are trading dates, both included. `minSignalSol` is a plain decimal string. */
export interface BackfillCreateRequest {
  requestId: string;
  fromDay: IsoDate;
  toDay: IsoDate;
  dryRun: boolean;
  requestBudget: number;
  minSignalSol?: DecimalString;
  mints?: string[];
}

/**
 * A backfill over `[rangeStart, rangeEnd)` after clipping to live coverage. The scan reads newest first: `scannedTo` is
 * how far back it has read. `discoveryRequests` + `itemRequests` = `requestsSpent`, which `requestBudget` limits. A
 * `filtered` backfill (a token list, or a minimum above the capture filter) captures its buys but never counts as
 * coverage. `summary` and `estimatedRequests` are a dry run's result.
 */
export interface AutomationBackfill {
  id: string;
  requestId: string;
  wallet: string;
  fromDay: IsoDate;
  toDay: IsoDate;
  rangeStart: IsoInstant;
  rangeEnd: IsoInstant;
  dryRun: boolean;
  minSignalLamports: string | null;
  mints: string[];
  requestBudget: number;
  state: BackfillState;
  discoveryRequests: number;
  itemRequests: number;
  requestsSpent: number;
  actionsScanned: number;
  buysFound: number;
  buysPassing: number;
  itemsCreated: number;
  skippedByFilter: number;
  skippedNoBlockTime: number;
  scannedTo: IsoInstant | null;
  filtered: boolean;
  summary: { distinctMints: number; buysPerDay: Record<IsoDate, number> } | null;
  estimatedRequests: number | null;
  itemCounts: Partial<Record<AutomationItemState, number>>;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  finishedAt: IsoInstant | null;
}

export interface SessionQuery {
  page: number;
  size: number;
  from?: IsoInstant | null;
  to?: IsoInstant | null;
  health?: SessionHealth | null;
}

export interface EvaluationQuery {
  from?: IsoDate | null;
  to?: IsoDate | null;
  label?: string | null;
  status?: EvaluationStatus | null;
  sort?: EvaluationSort;
  page: number;
  size: number;
}

export interface DayQuery {
  from?: IsoDate | null;
  to?: IsoDate | null;
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
  runDiscovery: () => postJson<ManualRunResult>(`${BASE}/discovery/run`),
  sessions: (query: SessionQuery, signal?: AbortSignal) =>
    getJson<AutomationPage<AutomationSession>>(withQuery(`${BASE}/sessions`, { ...query }), signal),
  session: (id: string, signal?: AbortSignal) =>
    getJson<AutomationSessionDetail>(`${BASE}/sessions/${enc(id)}`, signal),
  closeSession: (id: string, requestId: string) =>
    postJson<AutomationSessionDetail>(`${BASE}/sessions/${enc(id)}/close`, { requestId }),
  item: (id: string, signal?: AbortSignal) =>
    getJson<AutomationItemDetail>(`${BASE}/items/${enc(id)}`, signal),
  days: (query: DayQuery, signal?: AbortSignal) =>
    getJson<AutomationDayList>(withQuery(`${BASE}/days`, { ...query }), signal),
  day: (date: IsoDate, signal?: AbortSignal) =>
    getJson<AutomationDayDetail>(`${BASE}/days/${enc(date)}`, signal),
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
  backfills: (page: number, size: number, signal?: AbortSignal) =>
    getJson<AutomationPage<AutomationBackfill>>(
      withQuery(`${BASE}/backfills`, { page, size }),
      signal,
    ),
  backfill: (id: string, signal?: AbortSignal) =>
    getJson<AutomationBackfill>(`${BASE}/backfills/${enc(id)}`, signal),
  createBackfill: (body: BackfillCreateRequest) =>
    postJson<AutomationBackfill>(`${BASE}/backfills`, body),
  cancelBackfill: (id: string, requestId: string) =>
    postJson<AutomationBackfill>(`${BASE}/backfills/${enc(id)}/cancel`, { requestId }),
};

/** True when the automation endpoints are absent: automation or the console is switched off on the backend. */
export function isAutomationOff(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'HTTP_ERROR';
}

/** Matches the API usage page: the status is refreshed every 30 seconds while the tab is visible. */
export const AUTOMATION_STATUS_POLL_MS = 30_000;
/** While a "run now" pass is queued, the status is read often enough to see it finish. */
export const AUTOMATION_QUEUED_POLL_MS = 5_000;
/** Lists change at most once per tick (minutes), so a minute is fresh enough. */
const AUTOMATION_LIST_POLL_MS = 60_000;
/** An active backfill moves every scheduler tick. */
export const BACKFILL_ACTIVE_POLL_MS = 15_000;

export const automationKeys = {
  all: ['automation'] as const,
  status: ['automation', 'status'] as const,
  sessions: (query: SessionQuery) => ['automation', 'sessions', query] as const,
  session: (id: string) => ['automation', 'session', id] as const,
  item: (id: string) => ['automation', 'item', id] as const,
  days: (query: DayQuery) => ['automation', 'days', query] as const,
  day: (date: string) => ['automation', 'day', date] as const,
  sweepRuns: (query: SweepRunQuery) => ['automation', 'sweep-runs', query] as const,
  backfills: (page: number, size: number) => ['automation', 'backfills', page, size] as const,
  backfill: (id: string) => ['automation', 'backfill', id] as const,
};

function hidden(): boolean {
  return typeof document !== 'undefined' && document.visibilityState === 'hidden';
}

function visiblePoll(ms: number) {
  return () => (hidden() ? false : ms);
}

export function useAutomationStatus() {
  return useQuery({
    queryKey: automationKeys.status,
    queryFn: ({ signal }) => automationApi.status(signal),
    refetchInterval: (query) =>
      hidden()
        ? false
        : query.state.data?.manualRunQueued
          ? AUTOMATION_QUEUED_POLL_MS
          : AUTOMATION_STATUS_POLL_MS,
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
  });
}

export function useAutomationItem(id: string | null) {
  return useQuery({
    queryKey: automationKeys.item(id ?? ''),
    queryFn: ({ signal }) => automationApi.item(id!, signal),
    enabled: id != null,
  });
}

export function useAutomationDays(query: DayQuery, enabled = true) {
  return useQuery({
    queryKey: automationKeys.days(query),
    queryFn: ({ signal }) => automationApi.days(query, signal),
    placeholderData: keepPreviousData,
    refetchInterval: visiblePoll(AUTOMATION_LIST_POLL_MS),
    enabled,
  });
}

export function useAutomationDay(date: string | null) {
  return useQuery({
    queryKey: automationKeys.day(date ?? ''),
    queryFn: ({ signal }) => automationApi.day(date!, signal),
    enabled: date != null,
    refetchInterval: visiblePoll(AUTOMATION_LIST_POLL_MS),
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

/**
 * Every evaluation row matching `query`, read page by page (the backend caps a page at 100 rows): 30 days of a 10-entry
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

export function useBackfills(page: number, size: number) {
  return useQuery({
    queryKey: automationKeys.backfills(page, size),
    queryFn: ({ signal }) => automationApi.backfills(page, size, signal),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      hidden()
        ? false
        : query.state.data?.items.some((b) => ACTIVE_BACKFILL_STATES.includes(b.state))
          ? BACKFILL_ACTIVE_POLL_MS
          : AUTOMATION_LIST_POLL_MS,
  });
}

export function useBackfill(id: string | null) {
  return useQuery({
    queryKey: automationKeys.backfill(id ?? ''),
    queryFn: ({ signal }) => automationApi.backfill(id!, signal),
    enabled: id != null,
    refetchInterval: (query) =>
      hidden() || !query.state.data || !ACTIVE_BACKFILL_STATES.includes(query.state.data.state)
        ? false
        : BACKFILL_ACTIVE_POLL_MS,
  });
}

function readStored(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    // Storage can be unavailable; a fresh id is still correct for a single attempt.
    return null;
  }
}

function writeStored(key: string, value: string | null): void {
  try {
    if (value == null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // See readStored.
  }
}

/**
 * Runs `call` with a request id kept in session storage under `key` until it succeeds, so a retry after a lost answer
 * is recognised by the backend instead of acting twice.
 */
export async function withStoredRequestId<T>(
  key: string,
  prefix: string,
  call: (requestId: string) => Promise<T>,
): Promise<T> {
  let requestId = readStored(key);
  if (!requestId) {
    requestId = `${prefix}-${crypto.randomUUID()}`;
    writeStored(key, requestId);
  }
  const result = await call(requestId);
  writeStored(key, null);
  return result;
}

/** Closes an open session now; its unfinished buys become Abandoned. */
export function useCloseSession(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () =>
      withStoredRequestId(`automation-close:${id}`, 'ui-close', (requestId) =>
        automationApi.closeSession(id, requestId),
      ),
    onSuccess: () => void client.invalidateQueries({ queryKey: automationKeys.all }),
  });
}

/** Queues one live discovery pass now. */
export function useRunDiscovery() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => automationApi.runDiscovery(),
    onSuccess: () => void client.invalidateQueries({ queryKey: automationKeys.status }),
  });
}

/**
 * Creates a backfill. The request id is kept per form submission until the backend answers, so a retried click returns
 * the same backfill. `formKey` changes when the user edits the form, which starts a new request.
 */
export function useCreateBackfill() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      formKey,
      body,
    }: {
      formKey: string;
      body: Omit<BackfillCreateRequest, 'requestId'>;
    }) =>
      withStoredRequestId(`automation-backfill:${formKey}`, 'ui-backfill', (requestId) =>
        automationApi.createBackfill({ ...body, requestId }),
      ),
    onSuccess: () => void client.invalidateQueries({ queryKey: automationKeys.all }),
  });
}

/** Cancels a backfill; its unfinished buys become Abandoned. Idempotent. */
export function useCancelBackfill(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () =>
      withStoredRequestId(`automation-backfill-cancel:${id}`, 'ui-cancel', (requestId) =>
        automationApi.cancelBackfill(id, requestId),
      ),
    onSuccess: (backfill) => {
      client.setQueryData(automationKeys.backfill(id), backfill);
      void client.invalidateQueries({ queryKey: automationKeys.all });
    },
  });
}

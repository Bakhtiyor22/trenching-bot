import type {
  AutomationBackfill,
  AutomationDayDetail,
  AutomationDaySummary,
  AutomationEvaluation,
  AutomationItem,
  AutomationItemState,
  AutomationSession,
  AutomationStatus,
  AutomationSweepRun,
  EvaluationStatus,
} from '../api/automation';
import { ITEM_STATES } from '../api/automation';

/**
 * Offline automation data on trading days (12:00 → 12:00 UTC+5): live discovery covering 17 Oct to today (Mon 2 Nov,
 * open), a capturing backfill of 6–9 Oct, a finished dry run and two ended backfills. Decu's buys, the 10 variations of
 * `decu-sweep-1` replayed on every replayed buy, and day rows computed on read with the backend's rules (a 7-day
 * window, PENDING while a day of it is incomplete). Deterministic, so tests and screenshots are stable.
 */

export const MOCK_ZONE = '+05:00';
export const MOCK_DAY_START = '12:00';
export const MOCK_PLAN = 'decu-sweep-1';
export const MOCK_WALLET = '4Kq2DecuWa11etAddre55xxxxxxxxxxxxxxxxx9xPd';
export const MOCK_LABELS = ['V00', 'V01', 'V02', 'V03', 'V04', 'V05', 'V06', 'V07', 'V08', 'V09'];
/** Today's trading day; the tests pin the clock inside it. */
export const MOCK_TODAY = '2026-11-02';
const LIVE_NIGHTS = 17;
/** Tonight: Mon 2 Nov 2026, the first buy at 19:12 UTC+5. */
const TONIGHT_UTC = Date.UTC(2026, 10, 2, 14, 12);
const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;
const OFFSET_MS = 5 * 60 * MINUTE_MS;
const DAY_START_MS = 12 * 60 * MINUTE_MS;
const WINDOW_DAYS = 7;
const MIN_SIGNALS = 10;
const TERMINAL: AutomationItemState[] = [
  'SIMULATED',
  'FAILED',
  'ABANDONED',
  'SKIPPED_NOT_FIRST_BUY',
];

const hex = (seed: number, length = 64) => {
  let out = '';
  let x = seed * 2654435761;
  while (out.length < length) {
    x = (x * 1103515245 + 12345) % 2147483648;
    out += x.toString(16).padStart(8, '0');
  }
  return out.slice(0, length);
};
const base58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
export const mockMintOf = (seed: number) => {
  let out = '';
  let x = seed * 7919 + 17;
  for (let i = 0; i < 40; i += 1) {
    x = (x * 48271) % 2147483647;
    out += base58[x % base58.length];
  }
  return `${out}pump`;
};
const iso = (ms: number) => new Date(ms).toISOString();
const pad = (n: number) => String(n).padStart(2, '0');

/** The trading day of an instant: its UTC+5 date after stepping back 12 hours. */
export function mockDayOf(at: string | number): string {
  const ms = (typeof at === 'number' ? at : Date.parse(at)) + OFFSET_MS - DAY_START_MS;
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** The instant a trading day starts (12:00 UTC+5). */
export function mockDayStart(day: string): number {
  const [y, m, d] = day.split('-').map(Number) as [number, number, number];
  return Date.UTC(y, m - 1, d) + DAY_START_MS - OFFSET_MS;
}

export function mockAddDays(day: string, n: number): string {
  return mockDayOf(mockDayStart(day) + n * DAY_MS);
}

type BuyPlan = Array<[minuteOffset: number, state: AutomationItemState]>;

/** Live nights: the buys' minute offsets after the first buy and how each ended. */
const LIVE_PLANS: BuyPlan[] = Array.from({ length: LIVE_NIGHTS }, (_, n) => {
  if (n === LIVE_NIGHTS - 1)
    return [
      [0, 'SIMULATED'],
      [24, 'SIMULATED'],
      [61, 'SIMULATED'],
      [88, 'SIMULATED'],
      [119, 'CAPTURING'],
      [139, 'WAITING'],
    ];
  const count = 2 + (n % 3);
  return Array.from({ length: count }, (_, i): [number, AutomationItemState] => {
    const state: AutomationItemState =
      n === 14 && i === 2
        ? 'FAILED'
        : n === 15 && i === 1
          ? 'SKIPPED_NOT_FIRST_BUY'
          : n === 9 && i === 1
            ? 'ABANDONED'
            : 'SIMULATED';
    return [i * (50 + (n % 4) * 17), state];
  });
});

/** The active backfill's nights, 6–9 Oct: the oldest replayed, the newest still capturing. */
const BACKFILL_PLANS: BuyPlan[] = [
  [
    [0, 'SIMULATED'],
    [44, 'SIMULATED'],
    [97, 'SIMULATED'],
  ],
  [
    [0, 'SIMULATED'],
    [71, 'SKIPPED_NOT_FIRST_BUY'],
  ],
  [
    [0, 'SIMULATED'],
    [33, 'CAPTURED'],
    [80, 'WAITING'],
  ],
  [
    [0, 'WAITING'],
    [52, 'DISCOVERED'],
  ],
];

const OUTCOMES_BY_LABEL: Record<string, string[]> = {
  V00: ['TAKE_PROFIT', 'STOP_LOSS', 'NO_DIP', 'TAKE_PROFIT', 'TIME_EXIT'],
  V01: ['STOP_LOSS', 'STOP_LOSS', 'TAKE_PROFIT', 'STOP_LOSS', 'TIME_EXIT'],
  V02: ['NO_DIP', 'TAKE_PROFIT', 'NO_DIP', 'STOP_LOSS', 'NO_DIP'],
  V03: ['TAKE_PROFIT', 'TAKE_PROFIT', 'NO_DIP', 'TAKE_PROFIT', 'STOP_LOSS'],
  V04: ['HORIZON_REACHED', 'STOP_LOSS', 'NO_DIP', 'TAKE_PROFIT', 'TIME_EXIT'],
  V05: ['TAKE_PROFIT', 'STOP_LOSS', 'NO_DIP', 'TAKE_PROFIT', 'STOP_LOSS'],
  V06: ['TIME_EXIT', 'STOP_LOSS', 'NO_DIP', 'TIME_EXIT', 'TIME_EXIT'],
  V07: ['TAKE_PROFIT', 'NO_DIP', 'NO_DIP', 'STOP_LOSS', 'TIME_EXIT'],
  V08: ['STOP_LOSS', 'TAKE_PROFIT', 'STOP_LOSS', 'STOP_LOSS', 'TIME_EXIT'],
  V09: [
    'REFERENCE_BELOW_MIN_MARKET_CAP',
    'REFERENCE_BELOW_MIN_MARKET_CAP',
    'TAKE_PROFIT',
    'REFERENCE_BELOW_MIN_MARKET_CAP',
    'NO_DIP',
  ],
};
const PNL_BY_OUTCOME: Record<string, string> = {
  TAKE_PROFIT: '0.031200000000',
  STOP_LOSS: '-0.038500000000',
  TIME_EXIT: '0.004100000000',
  NO_DIP: '0.000000000000',
  REFERENCE_BELOW_MIN_MARKET_CAP: '0.000000000000',
};
/** Variation-specific nudges, so the scoreboard is not a tie. */
const TAKE_PROFIT_PNL: Record<string, string> = {
  V03: '0.024600000000',
  V04: '0.061900000000',
};

function sweepRunFor(item: AutomationItem, label: string, k: number): AutomationSweepRun {
  const outcomes = OUTCOMES_BY_LABEL[label]!;
  const outcome = outcomes[k % outcomes.length]!;
  const open = outcome === 'HORIZON_REACHED';
  const realized =
    outcome === 'TAKE_PROFIT'
      ? (TAKE_PROFIT_PNL[label] ?? PNL_BY_OUTCOME.TAKE_PROFIT!)
      : (PNL_BY_OUTCOME[outcome] ?? '0.000000000000');
  return {
    itemId: item.id,
    sessionId: item.sessionId,
    planId: MOCK_PLAN,
    label,
    runId: `run-${hex(k * 31 + MOCK_LABELS.indexOf(label), 24)}`,
    configurationHash: hex(MOCK_LABELS.indexOf(label) + 400, 64),
    engineVersion: 'dip-paper-v6',
    outcome,
    positionState: open
      ? 'OPEN'
      : outcome === 'NO_DIP' || outcome.startsWith('REFERENCE')
        ? null
        : 'CLOSED',
    signal: true,
    realizedPnl: open ? '0.000000000000' : realized,
    worstCasePnl: open ? '-0.101250000000' : realized,
    createdAt: item.updatedAt,
  };
}

export interface AutomationDb {
  status: AutomationStatus;
  sessions: AutomationSession[];
  items: AutomationItem[];
  sweepRuns: AutomationSweepRun[];
  backfills: AutomationBackfill[];
}

export function addDecimal(a: string, b: string): string {
  // Fixed scale 12 strings: sum as integers of 1e-12 SOL.
  const toUnits = (v: string) => {
    const negative = v.startsWith('-');
    const [i = '0', f = ''] = v.replace(/^[+-]/, '').split('.');
    const units = BigInt(i) * 10n ** 12n + BigInt(f.padEnd(12, '0').slice(0, 12));
    return negative ? -units : units;
  };
  const total = toUnits(a) + toUnits(b);
  const negative = total < 0n;
  const abs = negative ? -total : total;
  const integer = abs / 10n ** 12n;
  const fraction = (abs % 10n ** 12n).toString().padStart(12, '0');
  return `${negative ? '-' : ''}${integer}.${fraction}`;
}

const RANK: Record<EvaluationStatus, number> = {
  SUCCESS: 0,
  NO_SUCCESS: 1,
  NO_RESULT: 2,
  PENDING: 3,
};

/** The backend's default order: status rank, then worst case descending, then newest day, then label. */
export function compareEvaluations(a: AutomationEvaluation, b: AutomationEvaluation): number {
  return (
    RANK[a.status] - RANK[b.status] ||
    Number(b.worstCasePnl) - Number(a.worstCasePnl) ||
    b.day.localeCompare(a.day) ||
    a.label.localeCompare(b.label)
  );
}

function emptyCounts(): Record<AutomationItemState, number> {
  return Object.fromEntries(ITEM_STATES.map((s) => [s, 0])) as Record<AutomationItemState, number>;
}

let itemSeed = 0;

function buildSession(
  seed: number,
  start: number,
  plan: BuyPlan,
  open: boolean,
  backfillId: string | null,
  db: Pick<AutomationDb, 'sessions' | 'items' | 'sweepRuns'>,
): void {
  const sessionId = hex(1000 + seed);
  const sessionItems = plan.map(([offset, state]): AutomationItem => {
    itemSeed += 1;
    const blockTime = start + offset * MINUTE_MS;
    const failed = state === 'FAILED';
    return {
      id: hex(itemSeed),
      sessionId,
      wallet: MOCK_WALLET,
      mint: mockMintOf(itemSeed),
      signature: `${hex(itemSeed + 5000, 44)}${hex(itemSeed + 6000, 44)}`,
      instructionIndex: 2,
      innerIndex: -1,
      slot: 377_000_000 + itemSeed * 9_137,
      blockTime: iso(blockTime),
      curvePaymentLamports: String(1_550_000_000 + ((itemSeed * 211_000_000) % 1_700_000_000)),
      state,
      step:
        state === 'SIMULATED'
          ? 'SWEEP'
          : state === 'SKIPPED_NOT_FIRST_BUY'
            ? 'QUALIFY'
            : failed || state === 'CAPTURING'
              ? 'MARKET'
              : state === 'WAITING'
                ? 'WALLET'
                : state === 'CAPTURED'
                  ? 'REGISTER'
                  : state === 'ABANDONED'
                    ? 'RATES'
                    : null,
      stepAttempt: failed ? 2 : 1,
      captureSessionId: state === 'DISCOVERED' ? null : `auto-${hex(itemSeed, 24)}`,
      operationId: state === 'CAPTURING' ? `op-${hex(itemSeed + 77, 12)}` : null,
      datasetId:
        state === 'SIMULATED' || state === 'CAPTURED' ? `ds-${hex(itemSeed + 99, 16)}` : null,
      classification:
        state === 'SKIPPED_NOT_FIRST_BUY'
          ? 'NOT_FIRST_BUY'
          : state === 'SIMULATED'
            ? 'QUALIFIED'
            : null,
      failureCode: failed ? 'TIMEOUT' : state === 'ABANDONED' ? 'SESSION_CLOSED' : null,
      failureMessage: failed
        ? 'The provider did not answer in time on both attempts of the market step.'
        : state === 'ABANDONED'
          ? 'The session was closed by hand before this buy finished.'
          : null,
      nextActionAt: state === 'WAITING' ? iso(blockTime + 35 * MINUTE_MS) : null,
      requestsUsed:
        state === 'WAITING' || state === 'DISCOVERED' ? null : 120 + ((itemSeed * 37) % 160),
      createdAt: iso(blockTime + 2 * MINUTE_MS),
      updatedAt: iso(blockTime + 40 * MINUTE_MS),
      backfillId,
    };
  });
  db.items.push(...sessionItems);
  sessionItems
    .filter((item) => item.state === 'SIMULATED')
    .forEach((item, k) =>
      MOCK_LABELS.forEach((label) => db.sweepRuns.push(sweepRunFor(item, label, k + seed))),
    );
  const counts = emptyCounts();
  sessionItems.forEach((item) => (counts[item.state] += 1));
  const lastBuy = Date.parse(sessionItems[sessionItems.length - 1]!.blockTime!);
  const closed = !open && sessionItems.every((i) => TERMINAL.includes(i.state));
  db.sessions.push({
    id: sessionId,
    wallet: MOCK_WALLET,
    firstItemId: sessionItems[0]!.id,
    firstBuyAt: sessionItems[0]!.blockTime!,
    lastBuyAt: iso(lastBuy),
    state: open ? 'OPEN' : closed ? 'CLOSED' : 'CLOSING',
    closedAt: closed ? iso(lastBuy + 60 * MINUTE_MS) : null,
    closeReason: closed ? (seed === 9 ? 'MANUAL' : 'IDLE') : null,
    belowFilterCount: 3 + (seed % 4),
    createdAt: sessionItems[0]!.createdAt,
    itemCounts: counts,
    requestsUsed: sessionItems.reduce((sum, item) => sum + (item.requestsUsed ?? 0), 0),
    tradingDay: mockDayOf(sessionItems[0]!.blockTime!),
    backfillId,
  });
}

const ACTIVE_BACKFILL_ID = `bf-${hex(7001, 20)}`;

function backfill(
  partial: Partial<AutomationBackfill> &
    Pick<AutomationBackfill, 'id' | 'fromDay' | 'toDay' | 'dryRun' | 'state' | 'requestBudget'>,
): AutomationBackfill {
  const rangeStart = iso(mockDayStart(partial.fromDay));
  const rangeEnd = iso(mockDayStart(mockAddDays(partial.toDay, 1)));
  return {
    requestId: `ui-backfill-${partial.id}`,
    wallet: MOCK_WALLET,
    rangeStart,
    rangeEnd,
    minSignalLamports: null,
    mints: [],
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
    filtered: false,
    summary: null,
    estimatedRequests: null,
    itemCounts: emptyCounts(),
    createdAt: iso(Date.parse(rangeEnd) + 20 * DAY_MS),
    updatedAt: iso(Date.parse(rangeEnd) + 20 * DAY_MS),
    finishedAt: null,
    ...partial,
  };
}

/** `activeBackfill` false leaves out the capturing backfill, so a new one can be created. */
export function createAutomationDb({ activeBackfill = true } = {}): AutomationDb {
  itemSeed = 0;
  const db: Pick<AutomationDb, 'sessions' | 'items' | 'sweepRuns'> = {
    sessions: [],
    items: [],
    sweepRuns: [],
  };
  LIVE_PLANS.forEach((plan, n) => {
    const start = TONIGHT_UTC - (LIVE_NIGHTS - 1 - n) * DAY_MS + ((n * 37) % 90) * MINUTE_MS;
    buildSession(n, start, plan, n === LIVE_NIGHTS - 1, null, db);
  });
  const backfills: AutomationBackfill[] = [
    backfill({
      id: `bf-${hex(7002, 20)}`,
      fromDay: '2026-10-10',
      toDay: '2026-10-16',
      dryRun: true,
      state: 'DONE',
      requestBudget: 500,
      discoveryRequests: 231,
      requestsSpent: 231,
      actionsScanned: 388,
      buysFound: 31,
      buysPassing: 24,
      scannedTo: iso(mockDayStart('2026-10-10')),
      summary: {
        distinctMints: 18,
        buysPerDay: {
          '2026-10-10': 3,
          '2026-10-11': 5,
          '2026-10-12': 0,
          '2026-10-13': 4,
          '2026-10-14': 6,
          '2026-10-15': 2,
          '2026-10-16': 4,
        },
      },
      estimatedRequests: 3150,
      createdAt: '2026-11-02T09:10:00Z',
      updatedAt: '2026-11-02T09:31:00Z',
      finishedAt: '2026-11-02T09:31:00Z',
    }),
    backfill({
      id: `bf-${hex(7003, 20)}`,
      fromDay: '2026-10-01',
      toDay: '2026-10-05',
      dryRun: false,
      state: 'STOPPED_BUDGET',
      requestBudget: 1222,
      minSignalLamports: '2500000000',
      filtered: true,
      discoveryRequests: 96,
      itemRequests: 1134,
      requestsSpent: 1230,
      actionsScanned: 240,
      buysFound: 14,
      buysPassing: 3,
      itemsCreated: 3,
      scannedTo: iso(mockDayStart('2026-10-01')),
      itemCounts: { ...emptyCounts(), SIMULATED: 2, FAILED: 1 },
      createdAt: '2026-10-29T10:00:00Z',
      updatedAt: '2026-10-29T13:00:00Z',
      finishedAt: '2026-10-29T13:00:00Z',
    }),
    backfill({
      id: `bf-${hex(7004, 20)}`,
      fromDay: '2026-09-28',
      toDay: '2026-09-30',
      dryRun: false,
      state: 'CANCELLED',
      requestBudget: 1500,
      mints: [mockMintOf(901), mockMintOf(902)],
      filtered: true,
      discoveryRequests: 88,
      itemRequests: 222,
      requestsSpent: 310,
      actionsScanned: 150,
      buysFound: 9,
      buysPassing: 2,
      itemsCreated: 2,
      skippedByFilter: 7,
      scannedTo: iso(mockDayStart('2026-09-28')),
      itemCounts: { ...emptyCounts(), ABANDONED: 2 },
      createdAt: '2026-10-27T10:00:00Z',
      updatedAt: '2026-10-27T11:00:00Z',
      finishedAt: '2026-10-27T11:00:00Z',
    }),
  ];
  if (activeBackfill) {
    BACKFILL_PLANS.forEach((plan, i) => {
      const start = mockDayStart('2026-10-06') + i * DAY_MS + (8 + (i % 3)) * 60 * MINUTE_MS;
      buildSession(100 + i, start, plan, false, ACTIVE_BACKFILL_ID, db);
    });
    const ownItems = db.items.filter((item) => item.backfillId === ACTIVE_BACKFILL_ID);
    const counts = emptyCounts();
    ownItems.forEach((item) => (counts[item.state] += 1));
    const itemRequests = ownItems.reduce((sum, item) => sum + (item.requestsUsed ?? 0), 0);
    backfills.unshift(
      backfill({
        id: ACTIVE_BACKFILL_ID,
        fromDay: '2026-10-06',
        toDay: '2026-10-09',
        dryRun: false,
        state: 'CAPTURING',
        requestBudget: 4000,
        discoveryRequests: 240,
        itemRequests,
        requestsSpent: 240 + itemRequests,
        actionsScanned: 412,
        buysFound: 13,
        buysPassing: ownItems.length,
        itemsCreated: ownItems.length,
        scannedTo: iso(mockDayStart('2026-10-06')),
        itemCounts: counts,
        createdAt: '2026-11-02T10:04:00Z',
        updatedAt: '2026-11-02T16:40:00Z',
      }),
    );
  }

  const tonight = db.sessions[LIVE_NIGHTS - 1]!;
  const status: AutomationStatus = {
    enabled: true,
    captureEnabled: true,
    wallet: MOCK_WALLET,
    sweepPlanId: MOCK_PLAN,
    zone: MOCK_ZONE,
    mode: 'ALWAYS',
    pollFrom: '18:00',
    pollTo: '05:00',
    inPollWindow: true,
    manualRunQueued: false,
    tradingDayStart: MOCK_DAY_START,
    coveredFrom: iso(mockDayStart('2026-10-17')),
    watermark: {
      slot: 377_400_000,
      signature: `${hex(9001, 44)}${hex(9002, 44)}`,
      instructionIndex: 2,
      innerIndex: -1,
      updatedAt: iso(Date.parse(tonight.lastBuyAt) + 9 * MINUTE_MS),
      resumeBefore: null,
    },
    requestsLast24h: 1_840,
    maxRequestsPer24h: 5_000,
    rpcConfigured: true,
    lastTickAt: iso(Date.parse(tonight.lastBuyAt) + 9 * MINUTE_MS),
    nextTickAt: iso(Date.parse(tonight.lastBuyAt) + 19 * MINUTE_MS),
    lastDiscoveryAt: iso(Date.parse(tonight.lastBuyAt) + 9 * MINUTE_MS),
    discoveryProblem: null,
    openSessionId: tonight.id,
    activeBackfillId: activeBackfill ? ACTIVE_BACKFILL_ID : null,
  };
  return { status, ...db, backfills };
}

/** Phase 0: buys are found and grouped, nothing is captured, replayed or scored, so no day completes. */
export function discoveryOnly(db: AutomationDb): AutomationDb {
  const items = db.items.map((item) => ({
    ...item,
    state: 'DISCOVERED' as const,
    step: null,
    captureSessionId: null,
    datasetId: null,
    classification: null,
    failureCode: null,
    failureMessage: null,
    requestsUsed: null,
  }));
  const sessions = db.sessions.map((session) => {
    const counts = emptyCounts();
    counts.DISCOVERED = items.filter((item) => item.sessionId === session.id).length;
    return { ...session, itemCounts: counts, requestsUsed: 0 };
  });
  return {
    status: { ...db.status, captureEnabled: false, requestsLast24h: 42, activeBackfillId: null },
    sessions,
    items,
    sweepRuns: [],
    backfills: db.backfills.filter((b) => b.dryRun),
  };
}

function covered(db: AutomationDb, day: string, now: number): boolean {
  const from = mockDayStart(day);
  const to = mockDayStart(mockAddDays(day, 1));
  if (to > now) return false;
  const live = db.status.coveredFrom != null && from >= Date.parse(db.status.coveredFrom);
  const byBackfill = db.backfills.some(
    (b) =>
      !b.dryRun &&
      !b.filtered &&
      ['DONE', 'STOPPED_BUDGET', 'CANCELLED'].includes(b.state) &&
      b.scannedTo != null &&
      from >= Date.parse(b.scannedTo) &&
      to <= Date.parse(b.rangeEnd),
  );
  return live || byBackfill;
}

function sessionsOf(db: AutomationDb, day: string) {
  return db.sessions.filter((s) => s.tradingDay === day);
}

/** Covered, every session closed, every item terminal, none stopped by a backfill budget. */
function complete(db: AutomationDb, day: string, now: number): boolean {
  if (!covered(db, day, now)) return false;
  const ids = new Set(sessionsOf(db, day).map((s) => s.id));
  return (
    sessionsOf(db, day).every((s) => s.state === 'CLOSED') &&
    db.items
      .filter((i) => ids.has(i.sessionId))
      .every((i) => TERMINAL.includes(i.state) && i.failureCode !== 'BACKFILL_BUDGET')
  );
}

/** One row per variation for `day` over its 7-day window, as the backend computes them on read. */
export function dayRows(db: AutomationDb, day: string, now: number): AutomationEvaluation[] {
  const windowDays = Array.from({ length: WINDOW_DAYS }, (_, i) =>
    mockAddDays(day, i - WINDOW_DAYS + 1),
  );
  const pendingDay = windowDays.find((d) => !complete(db, d, now)) ?? null;
  const sessionIds = new Set(
    db.sessions.filter((s) => windowDays.includes(s.tradingDay)).map((s) => s.id),
  );
  const unscored = db.items.filter(
    (i) => sessionIds.has(i.sessionId) && (i.state === 'FAILED' || i.state === 'ABANDONED'),
  ).length;
  return MOCK_LABELS.map((label) => {
    const runs = db.sweepRuns.filter((r) => r.label === label && sessionIds.has(r.sessionId));
    const signals = runs.filter((r) => r.signal).length;
    const worst = runs.reduce((sum, r) => addDecimal(sum, r.worstCasePnl), '0.000000000000');
    const realized = runs.reduce((sum, r) => addDecimal(sum, r.realizedPnl), '0.000000000000');
    const status: EvaluationStatus = pendingDay
      ? 'PENDING'
      : signals < MIN_SIGNALS
        ? 'NO_RESULT'
        : Number(worst) > 0
          ? 'SUCCESS'
          : 'NO_SUCCESS';
    return {
      day,
      planId: MOCK_PLAN,
      label,
      windowDays,
      signals,
      scored: runs.length,
      unscored,
      realizedPnl: realized,
      worstCasePnl: worst,
      status,
      pendingDay,
    };
  }).sort(compareEvaluations);
}

export function daySummary(db: AutomationDb, day: string, now: number): AutomationDaySummary {
  const sessions = sessionsOf(db, day);
  const ids = new Set(sessions.map((s) => s.id));
  const items = db.items.filter((i) => ids.has(i.sessionId));
  const rows = dayRows(db, day, now);
  const best = rows[0]!;
  const count = (state: AutomationItemState) => items.filter((i) => i.state === state).length;
  return {
    day,
    from: iso(mockDayStart(day)),
    to: iso(mockDayStart(mockAddDays(day, 1))),
    covered: covered(db, day, now),
    complete: complete(db, day, now),
    sessions: sessions.length,
    buys: items.length,
    belowFilter: sessions.reduce((sum, s) => sum + s.belowFilterCount, 0),
    captured: count('SIMULATED') + count('CAPTURED'),
    simulated: count('SIMULATED'),
    failed: count('FAILED'),
    abandoned: count('ABANDONED'),
    requestsUsed: sessions.reduce((sum, s) => sum + s.requestsUsed, 0),
    bestStatus: best.status,
    bestLabel: best.status === 'PENDING' ? null : best.label,
    pendingDay: best.pendingDay,
  };
}

export function dayDetail(db: AutomationDb, day: string, now: number): AutomationDayDetail {
  const sessions = sessionsOf(db, day).sort((a, b) => a.firstBuyAt.localeCompare(b.firstBuyAt));
  const ids = new Set(sessions.map((s) => s.id));
  const summary = daySummary(db, day, now);
  return {
    summary,
    coverage: {
      covered: summary.covered,
      ranges: summary.covered
        ? [{ from: summary.from, to: summary.to }]
        : db.status.coveredFrom && Date.parse(summary.from) >= Date.parse(db.status.coveredFrom)
          ? [{ from: summary.from, to: iso(Math.min(now, Date.parse(summary.to))) }]
          : [],
    },
    sessions,
    items: db.items
      .filter((i) => ids.has(i.sessionId))
      .sort((a, b) => (a.blockTime ?? '').localeCompare(b.blockTime ?? '')),
    evaluations: dayRows(db, day, now),
  };
}

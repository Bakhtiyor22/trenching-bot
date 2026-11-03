import type {
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
 * Offline automation data: 16 closed nights and tonight's open one, Decu's buys in UTC+5, the 10 variations of
 * `decu-sweep-1` replayed on every replayed buy, and evaluations over 7-night windows. Deterministic, so tests and
 * screenshots are stable.
 */

export const MOCK_ZONE = '+05:00';
export const MOCK_PLAN = 'decu-sweep-1';
export const MOCK_WALLET = '4Kq2DecuWa11etAddre55xxxxxxxxxxxxxxxxx9xPd';
export const MOCK_LABELS = ['V00', 'V01', 'V02', 'V03', 'V04', 'V05', 'V06', 'V07', 'V08', 'V09'];
const NIGHTS = 17;
/** Tonight: Mon 2 Nov 2026, the first buy at 19:12 UTC+5. */
const TONIGHT_UTC = Date.UTC(2026, 10, 2, 14, 12);
const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;

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
const mintOf = (seed: number) => {
  let out = '';
  let x = seed * 7919 + 17;
  for (let i = 0; i < 40; i += 1) {
    x = (x * 48271) % 2147483647;
    out += base58[x % base58.length];
  }
  return `${out}pump`;
};
const iso = (ms: number) => new Date(ms).toISOString();

/** Per night: the buys' minute offsets after the first buy and how each ended. */
const NIGHT_PLANS: Array<Array<[number, AutomationItemState]>> = Array.from(
  { length: NIGHTS },
  (_, n) => {
    if (n === NIGHTS - 1)
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
  },
);

function sessionStart(n: number): number {
  return TONIGHT_UTC - (NIGHTS - 1 - n) * DAY_MS + ((n * 37) % 90) * MINUTE_MS;
}

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
  evaluations: AutomationEvaluation[];
}

function addDecimal(a: string, b: string): string {
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

function rank(status: EvaluationStatus): number {
  return status === 'SUCCESS' ? 0 : status === 'NO_SUCCESS' ? 1 : 2;
}

export function compareEvaluations(a: AutomationEvaluation, b: AutomationEvaluation): number {
  const byStatus = rank(a.status) - rank(b.status);
  if (byStatus) return byStatus;
  const worst = Number(b.worstCasePnl) - Number(a.worstCasePnl);
  if (worst) return worst;
  return Date.parse(b.evaluatedAt) - Date.parse(a.evaluatedAt);
}

export function createAutomationDb(): AutomationDb {
  const sessions: AutomationSession[] = [];
  const items: AutomationItem[] = [];
  const sweepRuns: AutomationSweepRun[] = [];
  let itemSeed = 0;
  NIGHT_PLANS.forEach((plan, n) => {
    const start = sessionStart(n);
    const open = n === NIGHTS - 1;
    const sessionId = hex(1000 + n);
    const nightItems = plan.map(([offset, state]): AutomationItem => {
      itemSeed += 1;
      const blockTime = start + offset * MINUTE_MS;
      const failed = state === 'FAILED';
      return {
        id: hex(itemSeed),
        sessionId,
        wallet: MOCK_WALLET,
        mint: mintOf(itemSeed),
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
              : failed
                ? 'MARKET'
                : state === 'CAPTURING'
                  ? 'MARKET'
                  : state === 'WAITING'
                    ? 'WALLET'
                    : state === 'ABANDONED'
                      ? 'RATES'
                      : null,
        stepAttempt: failed ? 2 : 1,
        captureSessionId: `auto-${hex(itemSeed, 24)}`,
        operationId: state === 'CAPTURING' ? `op-${hex(itemSeed + 77, 12)}` : null,
        datasetId: state === 'SIMULATED' ? `ds-${hex(itemSeed + 99, 16)}` : null,
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
            ? 'The night was closed by hand before this buy finished.'
            : null,
        nextActionAt: state === 'WAITING' ? iso(blockTime + 35 * MINUTE_MS) : null,
        requestsUsed: state === 'WAITING' ? null : 120 + ((itemSeed * 37) % 160),
        createdAt: iso(blockTime + 2 * MINUTE_MS),
        updatedAt: iso(blockTime + 40 * MINUTE_MS),
      };
    });
    items.push(...nightItems);
    nightItems
      .filter((item) => item.state === 'SIMULATED')
      .forEach((item, k) =>
        MOCK_LABELS.forEach((label) => sweepRuns.push(sweepRunFor(item, label, k + n))),
      );
    const counts = Object.fromEntries(ITEM_STATES.map((s) => [s, 0])) as Record<
      AutomationItemState,
      number
    >;
    nightItems.forEach((item) => (counts[item.state] += 1));
    const lastBuy = Date.parse(nightItems[nightItems.length - 1]!.blockTime!);
    sessions.push({
      id: sessionId,
      wallet: MOCK_WALLET,
      firstItemId: nightItems[0]!.id,
      firstBuyAt: nightItems[0]!.blockTime!,
      lastBuyAt: iso(lastBuy),
      state: open ? 'OPEN' : 'CLOSED',
      closedAt: open ? null : iso(lastBuy + 60 * MINUTE_MS),
      closeReason: open ? null : n === 9 ? 'MANUAL' : 'IDLE',
      belowFilterCount: 3 + (n % 4),
      createdAt: nightItems[0]!.createdAt,
      itemCounts: counts,
      requestsUsed: nightItems.reduce((sum, item) => sum + (item.requestsUsed ?? 0), 0),
      bestStatus: null,
      bestLabel: null,
    });
  });

  const closed = sessions.filter((s) => s.state === 'CLOSED');
  const evaluations: AutomationEvaluation[] = [];
  closed.forEach((session, index) => {
    const window = closed.slice(Math.max(0, index - 6), index + 1);
    const windowIds = window.map((s) => s.id);
    const windowItems = items.filter((item) => windowIds.includes(item.sessionId));
    for (const label of MOCK_LABELS) {
      const runs = sweepRuns.filter((r) => r.label === label && windowIds.includes(r.sessionId));
      const signals = runs.filter((r) => r.signal).length;
      const unscored = windowItems.filter(
        (item) => item.state === 'FAILED' || item.state === 'ABANDONED',
      ).length;
      const worst = runs.reduce((sum, r) => addDecimal(sum, r.worstCasePnl), '0.000000000000');
      const realized = runs.reduce((sum, r) => addDecimal(sum, r.realizedPnl), '0.000000000000');
      const status: EvaluationStatus =
        signals < 10 ? 'NO_RESULT' : Number(worst) > 0 ? 'SUCCESS' : 'NO_SUCCESS';
      evaluations.push({
        sessionId: session.id,
        planId: MOCK_PLAN,
        label,
        windowSessionIds: windowIds,
        signals,
        scored: runs.length,
        unscored,
        realizedPnl: realized,
        worstCasePnl: worst,
        status,
        evaluatedAt: session.closedAt!,
      });
    }
    const best = evaluations.filter((e) => e.sessionId === session.id).sort(compareEvaluations)[0]!;
    session.bestStatus = best.status;
    session.bestLabel = best.label;
  });

  const tonight = sessions[sessions.length - 1]!;
  const status: AutomationStatus = {
    enabled: true,
    captureEnabled: true,
    wallet: MOCK_WALLET,
    sweepPlanId: MOCK_PLAN,
    zone: MOCK_ZONE,
    pollFrom: '18:00',
    pollTo: '05:00',
    inPollWindow: true,
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
  };
  return { status, sessions, items, sweepRuns, evaluations };
}

/** Discovery only (phase 0): buys are found and grouped, nothing is captured, replayed or scored. */
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
    const counts = Object.fromEntries(ITEM_STATES.map((s) => [s, 0])) as Record<
      AutomationItemState,
      number
    >;
    counts.DISCOVERED = items.filter((item) => item.sessionId === session.id).length;
    return { ...session, itemCounts: counts, requestsUsed: 0, bestStatus: null, bestLabel: null };
  });
  return {
    status: { ...db.status, captureEnabled: false, requestsLast24h: 42 },
    sessions,
    items,
    sweepRuns: [],
    evaluations: [],
  };
}

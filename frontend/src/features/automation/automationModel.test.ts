import { describe, expect, it } from 'vitest';
import type { AutomationDaySummary, AutomationEvaluation } from '../../api/automation';
import { isSolanaAddress } from '../../lib/base58';
import {
  addDays,
  buyBuckets,
  capPercent,
  captureBudgetFor,
  compareWorstDesc,
  dayInfo,
  dayItemCounts,
  dayRange,
  daysBetween,
  dayStartInstant,
  defaultScoredDay,
  isFilteredBackfill,
  lamportsToSol,
  outcomeLabel,
  parseMints,
  pendingText,
  planLabels,
  pnlBar,
  pnlScale,
  scanFraction,
  shortSol,
  statusGrid,
  stepMarks,
  tradingDayOf,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
} from './automationModel';

const ZONE = '+05:00';
const MINT = 'So11111111111111111111111111111111111111112';

function evaluation(day: string, label: string, worst = '0.1'): AutomationEvaluation {
  return {
    day,
    planId: 'decu-sweep-1',
    label,
    windowDays: [day],
    signals: 12,
    scored: 12,
    unscored: 0,
    realizedPnl: worst,
    worstCasePnl: worst,
    status: 'SUCCESS',
    pendingDay: null,
  };
}

function summary(day: string, overrides: Partial<AutomationDaySummary> = {}): AutomationDaySummary {
  return {
    day,
    from: '',
    to: '',
    covered: true,
    complete: true,
    sessions: 1,
    buys: 4,
    belowFilter: 0,
    captured: 3,
    simulated: 2,
    failed: 1,
    abandoned: 0,
    requestsUsed: 0,
    bestStatus: 'SUCCESS',
    bestLabel: 'V03',
    pendingDay: null,
    ...overrides,
  };
}

describe('the automation clock', () => {
  it('names offset zones as UTC offsets', () => {
    expect(zoneLabel('+05:00')).toBe('UTC+5');
    expect(zoneLabel('-03:30')).toBe('UTC−3:30');
    expect(zoneLabel('Z')).toBe('UTC');
    expect(zoneLabel('Asia/Tashkent')).toBe('Asia/Tashkent');
  });

  it('reads times on the automation clock, not the browser clock', () => {
    expect(zoneTime('2026-11-02T14:12:00Z', ZONE)).toBe('19:12');
    expect(zoneTime('2026-11-02T14:12:09Z', ZONE, true)).toBe('19:12:09');
    expect(zoneDateTime('2026-11-02T20:30:00Z', ZONE)).toBe('3 Nov, 01:30');
    expect(zoneTime('2026-11-02T14:12:00Z', 'Asia/Tashkent')).toBe('19:12');
    expect(zoneTime(null, ZONE)).toBe('—');
  });
});

describe('trading days', () => {
  it('runs noon to noon and is named by its start date', () => {
    expect(tradingDayOf('2026-11-02T14:12:00Z', ZONE)).toBe('2026-11-02');
    // 01:30 on 3 Nov in UTC+5 still belongs to the day that started on 2 Nov at noon.
    expect(tradingDayOf('2026-11-02T20:30:00Z', ZONE)).toBe('2026-11-02');
    // A buy exactly at 12:00:00 belongs to the new day; one second before, to the old one.
    expect(tradingDayOf('2026-11-03T07:00:00Z', ZONE)).toBe('2026-11-03');
    expect(tradingDayOf('2026-11-03T06:59:59Z', ZONE)).toBe('2026-11-02');
    // A configured start moves the boundary.
    expect(tradingDayOf('2026-11-03T05:30:00Z', ZONE, '10:00')).toBe('2026-11-03');
  });

  it('labels, steps and spans days', () => {
    expect(dayInfo('2026-11-02')).toEqual({
      key: '2026-11-02',
      label: 'Mon 2 Nov',
      short: '2 Nov',
    });
    expect(addDays('2026-11-02', -6)).toBe('2026-10-27');
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(daysBetween('2026-10-01', '2026-12-01')).toBe(61);
    expect(dayRange('2026-10-30', '2026-11-01')).toEqual([
      '2026-10-30',
      '2026-10-31',
      '2026-11-01',
    ]);
    expect(dayRange('2026-11-02', '2026-11-01')).toEqual([]);
    expect(dayStartInstant('2026-11-02', ZONE)).toBe('2026-11-02T07:00:00.000Z');
  });

  it('defaults the scoreboard to the newest day with a verdict', () => {
    const days = [
      summary('2026-11-02', { bestStatus: 'PENDING', bestLabel: null, pendingDay: '2026-11-02' }),
      summary('2026-11-01', { bestStatus: 'NO_SUCCESS' }),
      summary('2026-10-31'),
    ];
    expect(defaultScoredDay(days)?.day).toBe('2026-11-01');
    expect(defaultScoredDay(days.slice(0, 1))?.day).toBe('2026-11-02');
    expect(defaultScoredDay([])).toBeNull();
  });

  it('says which day a pending row waits for', () => {
    expect(pendingText('2026-10-31')).toBe('waiting for Sat 31 Oct to complete');
    expect(pendingText(null)).toBe('waiting for its days to complete');
  });

  it('turns a day summary into buy counts, the rest finished on a complete day', () => {
    expect(dayItemCounts(summary('2026-11-01'))).toEqual({
      SIMULATED: 2,
      FAILED: 1,
      ABANDONED: 0,
      SKIPPED_NOT_FIRST_BUY: 1,
    });
    expect(dayItemCounts(summary('2026-11-02', { complete: false }))).toMatchObject({
      CAPTURING: 1,
    });
  });
});

describe('amounts', () => {
  it('turns integer lamports into an exact SOL string', () => {
    expect(lamportsToSol('2412000000')).toBe('2.412');
    expect(lamportsToSol('1')).toBe('0.000000001');
    expect(lamportsToSol('1900000000')).toBe('1.9');
    expect(lamportsToSol('0')).toBe('0');
  });

  it('shortens P&L toward zero with a real minus sign, never inventing a sign for zero', () => {
    expect(shortSol('0.119300000000')).toBe('+0.1193');
    expect(shortSol('-0.038500000000')).toBe('−0.0385');
    expect(shortSol('-0.000010000000')).toBe('0.0000');
    expect(shortSol('0.000000000000')).toBe('0.0000');
  });

  it('orders by worst case exactly, then by label', () => {
    const rows = [
      { label: 'V02', worstCasePnl: '0.1' },
      { label: 'V01', worstCasePnl: '0.100000000001' },
      { label: 'V00', worstCasePnl: '0.1' },
    ];
    expect([...rows].sort(compareWorstDesc).map((r) => r.label)).toEqual(['V01', 'V00', 'V02']);
  });

  it('clamps the request meter', () => {
    expect(capPercent(1840, 5000)).toBeCloseTo(36.8);
    expect(capPercent(6000, 5000)).toBe(100);
    expect(capPercent(1, 0)).toBe(100);
  });
});

describe('scoreboard bars', () => {
  it('scales every bar by the largest magnitude, with 0 SOL in the middle', () => {
    const scale = pnlScale(['0.2', '-0.4', '0.1']);
    expect(scale).toBeCloseTo(0.4);
    expect(pnlBar('0.2', '0.2', scale)).toEqual({ left: 50, width: 25, negative: false, tick: 75 });
    expect(pnlBar('-0.4', '0.1', scale)).toEqual({
      left: 0,
      width: 50,
      negative: true,
      tick: 62.5,
    });
  });

  it('keeps bars finite when every value is zero', () => {
    const scale = pnlScale(['0', '0']);
    expect(pnlBar('0', '0', scale)).toEqual({ left: 50, width: 0, negative: false, tick: 50 });
  });
});

describe('buys', () => {
  it('groups item states into the four buy buckets', () => {
    expect(
      buyBuckets({
        SIMULATED: 4,
        CAPTURING: 1,
        WAITING: 1,
        BLOCKED_BUDGET: 1,
        FAILED: 1,
        ABANDONED: 1,
        SKIPPED_NOT_FIRST_BUY: 2,
      }),
    ).toEqual({ replayed: 4, inProgress: 3, issues: 2, skipped: 2 });
  });

  it('marks pipeline steps done before the current step, and the step where it stopped', () => {
    expect(stepMarks({ state: 'SIMULATED', step: 'SWEEP' })).toEqual(Array(8).fill('done'));
    expect(stepMarks({ state: 'FAILED', step: 'MARKET' }).slice(0, 4)).toEqual([
      'done',
      'done',
      'failed',
      'pending',
    ]);
    expect(stepMarks({ state: 'SKIPPED_NOT_FIRST_BUY', step: 'QUALIFY' })[5]).toBe('skipped');
    expect(stepMarks({ state: 'DISCOVERED', step: null })).toEqual(Array(8).fill('pending'));
  });
});

describe('variations and outcomes', () => {
  it('describes the variations of decu-sweep-1 only', () => {
    expect(variationChange('decu-sweep-1', 'V03')).toBe('take profit +35%');
    expect(variationChange('decu-sweep-2', 'V03')).toBeNull();
    expect(planLabels('decu-sweep-1')).toHaveLength(10);
  });

  it('names outcomes in plain words, the new signal outcomes included', () => {
    expect(outcomeLabel('SIGNAL_BELOW_MIN_SIZE')).toBe('Buy too small · not a signal');
    expect(outcomeLabel('HORIZON_REACHED')).toBe('Still open at the end');
    expect(outcomeLabel('SOMETHING_NEW')).toBe('something new');
  });
});

describe('the status-by-day grid', () => {
  it('puts days oldest first, a column for every day, and leaves a gap without a row', () => {
    const grid = statusGrid(
      ['2026-11-01', '2026-10-31', '2026-11-02'],
      [
        evaluation('2026-10-31', 'V00'),
        evaluation('2026-11-01', 'V00'),
        evaluation('2026-11-01', 'V01'),
      ],
      ['V00', 'V01'],
    );
    expect(grid.columns.map((c) => c.short)).toEqual(['31 Oct', '1 Nov', '2 Nov']);
    expect(grid.rows[0]!.cells.map((c) => c?.day ?? null)).toEqual([
      '2026-10-31',
      '2026-11-01',
      null,
    ]);
    expect(grid.rows[1]!.cells.map((c) => c?.day ?? null)).toEqual([null, '2026-11-01', null]);
  });
});

describe('backfills', () => {
  const base = {
    rangeStart: '2026-10-06T07:00:00Z',
    rangeEnd: '2026-10-10T07:00:00Z',
    scannedTo: null,
  };

  it('measures how far the newest-first scan has read back', () => {
    expect(scanFraction({ ...base, state: 'QUEUED' })).toBe(0);
    expect(scanFraction({ ...base, state: 'SCANNING', scannedTo: '2026-10-08T07:00:00Z' })).toBe(
      0.5,
    );
    expect(scanFraction({ ...base, state: 'CAPTURING' })).toBe(1);
    expect(scanFraction({ ...base, state: 'DONE', scannedTo: '2026-10-06T07:00:00Z' })).toBe(1);
  });

  it('parses a token list, one per line or comma-separated, with duplicates dropped and bad ones apart', () => {
    expect(parseMints(`${MINT}\n${MINT}, not-a-mint\n\n`, isSolanaAddress)).toEqual({
      mints: [MINT],
      invalid: ['not-a-mint'],
    });
    expect(parseMints('   ', isSolanaAddress)).toEqual({ mints: [], invalid: [] });
  });

  it('calls a backfill filtered with a token list or a minimum above the capture filter', () => {
    expect(isFilteredBackfill('', [])).toBe(false);
    expect(isFilteredBackfill('1.5', [])).toBe(false);
    expect(isFilteredBackfill('1.500000001', [])).toBe(true);
    expect(isFilteredBackfill('', [MINT])).toBe(true);
    expect(isFilteredBackfill('abc', [])).toBe(false);
  });

  it('starts a capture from the estimate, within the budget limits', () => {
    expect(captureBudgetFor(3150)).toBe(3150);
    expect(captureBudgetFor(400)).toBe(1222);
    expect(captureBudgetFor(null)).toBe(1222);
    expect(captureBudgetFor(50_000)).toBe(20_000);
  });
});

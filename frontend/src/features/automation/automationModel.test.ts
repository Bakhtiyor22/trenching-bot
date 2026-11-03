import { describe, expect, it } from 'vitest';
import type { AutomationEvaluation, AutomationSession } from '../../api/automation';
import {
  buyBuckets,
  capPercent,
  compareWorstDesc,
  lamportsToSol,
  nightKeyBefore,
  nightOf,
  nightStart,
  outcomeLabel,
  planLabels,
  pnlBar,
  pnlScale,
  shortSol,
  statusGrid,
  stepMarks,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
} from './automationModel';

const ZONE = '+05:00';

function session(id: string, firstBuyAt: string): AutomationSession {
  return {
    id,
    wallet: 'w',
    firstItemId: `${id}-item`,
    firstBuyAt,
    lastBuyAt: firstBuyAt,
    state: 'CLOSED',
    closedAt: firstBuyAt,
    closeReason: 'IDLE',
    belowFilterCount: 0,
    createdAt: firstBuyAt,
    itemCounts: {},
    requestsUsed: 0,
    bestStatus: null,
    bestLabel: null,
  };
}

function evaluation(sessionId: string, label: string, worst = '0.1'): AutomationEvaluation {
  return {
    sessionId,
    planId: 'decu-sweep-1',
    label,
    windowSessionIds: [sessionId],
    signals: 12,
    scored: 12,
    unscored: 0,
    realizedPnl: worst,
    worstCasePnl: worst,
    status: 'SUCCESS',
    evaluatedAt: '2026-11-02T00:00:00Z',
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

  it('puts a buy after midnight into the night of the evening before', () => {
    expect(nightOf('2026-11-02T14:12:00Z', ZONE)).toEqual({
      key: '2026-11-02',
      label: 'Mon 2 Nov',
      short: '2 Nov',
    });
    // 01:30 on 3 Nov in UTC+5 is still the night of Mon 2 Nov.
    expect(nightOf('2026-11-02T20:30:00Z', ZONE).label).toBe('Mon 2 Nov');
    // Noon starts the next night.
    expect(nightOf('2026-11-03T07:00:00Z', ZONE).label).toBe('Tue 3 Nov');
  });

  it('bounds date filters at the noon rollover in the zone', () => {
    expect(nightStart('2026-11-02', ZONE)).toBe('2026-11-02T07:00:00.000Z');
    expect(nightKeyBefore('2026-11-02T20:30:00Z', ZONE, 6)).toBe('2026-10-27');
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

describe('the status-by-night grid', () => {
  it('puts nights oldest first and leaves a gap where a night is not scored', () => {
    const sessions = [
      session('b', '2026-11-01T14:00:00Z'),
      session('a', '2026-10-31T14:00:00Z'),
      session('c', '2026-11-02T14:00:00Z'),
    ];
    const grid = statusGrid(
      sessions,
      [evaluation('a', 'V00'), evaluation('b', 'V00'), evaluation('b', 'V01')],
      ZONE,
      ['V00', 'V01'],
    );
    expect(grid.columns.map((c) => c.night.short)).toEqual(['31 Oct', '1 Nov', '2 Nov']);
    expect(grid.rows[0]!.cells.map((c) => c?.sessionId ?? null)).toEqual(['a', 'b', null]);
    expect(grid.rows[1]!.cells.map((c) => c?.sessionId ?? null)).toEqual([null, 'b', null]);
  });
});

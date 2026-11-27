import { describe, expect, it } from 'vitest';
import type { RunSummary } from '../../api/types';
import { REAL_MINT, sampleRun } from '../../mocks/fixtures';
import {
  activeFilterLabels,
  DEFAULT_SORT,
  parseSort,
  parseStatus,
  pnlDisplay,
  readFilters,
  serializeSort,
  sortRuns,
  toggleSort,
  toRunQuery,
  outcomeLines,
  reasonExplanation,
  stageReason,
  tradeDetail,
  tradeLabel,
  tradeReason,
} from './runList';

const run = (
  id: string,
  realizedPnl: string,
  createdAt: string,
  scenarioCount = 1,
): RunSummary => ({
  ...sampleRun,
  id,
  realizedPnl,
  createdAt,
  scenarioCount,
});

const winner = run('aaaa1111', '26.089701492540', '2026-10-02T21:42:32Z', 3);
const loser = run('bbbb2222', '-3.580241077133', '2026-10-05T11:48:49Z', 2);
const flat = run('cccc3333', '0.000000000000', '2026-10-04T00:00:00Z', 5);
const CLOCK = { zone: '+05:00', dayStart: '12:00' };

describe('toRunQuery', () => {
  it('sends every usable filter and cuts a trading day on the automation clock', () => {
    const filters = readFilters(
      new URLSearchParams({
        status: 'LOSS',
        mint: REAL_MINT,
        day: '2026-11-10',
        datasetId: 'ds-1',
        q: '  decu ',
      }),
    );
    expect(toRunQuery(filters, CLOCK, 2, 20, '2026-11-16T10:00:00Z')).toEqual({
      page: 2,
      size: 20,
      datasetId: 'ds-1',
      variation: null,
      status: 'LOSS',
      mint: REAL_MINT,
      // 12:00 UTC+5 is 07:00 UTC; the range is half-open, ending where the next trading day starts.
      anchorFrom: '2026-11-10T07:00:00.000Z',
      anchorTo: '2026-11-11T07:00:00.000Z',
      createdFrom: null,
      createdTo: null,
      q: 'decu',
      asOf: '2026-11-16T10:00:00Z',
    });
  });

  it('leaves out an invalid token, a one-letter text, an unreal day and a day without the clock', () => {
    const filters = readFilters(
      new URLSearchParams({ mint: 'not-a-mint', q: 'd', day: '2026-02-31' }),
    );
    const query = toRunQuery(filters, CLOCK, 0, 20, null);
    expect(query.mint).toBeNull();
    expect(query.q).toBeNull();
    expect(query.anchorFrom).toBeNull();
    const noClock = toRunQuery(
      readFilters(new URLSearchParams({ day: '2026-11-10' })),
      null,
      0,
      20,
      null,
    );
    expect(noClock.anchorFrom).toBeNull();
  });

  it('sends the created range as local midnights with the end date included, unless it is reversed', () => {
    const query = toRunQuery(
      readFilters(new URLSearchParams({ from: '2026-11-01', to: '2026-11-03' })),
      CLOCK,
      0,
      20,
      null,
    );
    expect(query.createdFrom).toBe(new Date(2026, 10, 1).toISOString());
    expect(query.createdTo).toBe(new Date(2026, 10, 4).toISOString());
    const reversed = toRunQuery(
      readFilters(new URLSearchParams({ from: '2026-11-03', to: '2026-11-01' })),
      CLOCK,
      0,
      20,
      null,
    );
    expect(reversed.createdFrom).toBeNull();
    expect(reversed.createdTo).toBeNull();
  });

  it('names the filters in effect in plain words', () => {
    const filters = readFilters(
      new URLSearchParams({ status: 'NO_ENTRY', day: '2026-11-10', datasetId: 'ds-1' }),
    );
    expect(activeFilterLabels(filters, 'Decu · 5bYU')).toEqual([
      'not bought',
      'trading day Tue 10 Nov',
      'dataset Decu · 5bYU',
    ]);
  });
});

describe('sortRuns', () => {
  it('sorts newest first by default and by exact P&L on request', () => {
    const runs = [winner, loser, flat];
    expect(sortRuns(runs, DEFAULT_SORT).map((r) => r.id)).toEqual([
      'bbbb2222',
      'cccc3333',
      'aaaa1111',
    ]);
    expect(sortRuns(runs, { key: 'pnl', descending: true }).map((r) => r.id)).toEqual([
      'aaaa1111',
      'cccc3333',
      'bbbb2222',
    ]);
    expect(sortRuns(runs, { key: 'pnl', descending: false }).map((r) => r.id)).toEqual([
      'bbbb2222',
      'cccc3333',
      'aaaa1111',
    ]);
  });

  it('flips the active column and starts a new one descending', () => {
    expect(toggleSort(DEFAULT_SORT, 'created')).toEqual({ key: 'created', descending: false });
    expect(toggleSort(DEFAULT_SORT, 'pnl')).toEqual({ key: 'pnl', descending: true });
  });
});

describe('run list URL state', () => {
  it('reads and writes the sort, falling back to the default', () => {
    expect(parseSort(null)).toEqual(DEFAULT_SORT);
    expect(parseSort('nonsense')).toEqual(DEFAULT_SORT);
    expect(parseSort('pnl')).toEqual({ key: 'pnl', descending: true });
    // The Scenarios column is gone; its old links open the default order without an error.
    expect(parseSort('scenarios')).toEqual(DEFAULT_SORT);
    expect(parseSort('scenarios-asc')).toEqual(DEFAULT_SORT);
    expect(serializeSort(DEFAULT_SORT)).toBeNull();
    expect(serializeSort({ key: 'created', descending: false })).toBe('created-asc');
    expect(serializeSort({ key: 'pnl', descending: true })).toBe('pnl');
  });

  it('reads the status, case-insensitively, also from an old outcome link', () => {
    expect(parseStatus('LOSS')).toBe('LOSS');
    expect(parseStatus('no-entry')).toBe('NO_ENTRY');
    expect(parseStatus('nonsense')).toBe('all');
    expect(parseStatus(null)).toBe('all');
    expect(readFilters(new URLSearchParams({ outcome: 'loss' })).status).toBe('LOSS');
  });
});

describe('trade column', () => {
  it('says what happened to the position for each status', () => {
    expect(tradeLabel({ status: 'NO_ENTRY' })).toEqual({ text: 'Not bought', tone: 'neutral' });
    expect(tradeLabel({ status: 'OPEN' })).toEqual({ text: 'Still held', tone: 'attention' });
    expect(tradeLabel({ status: 'PROFIT' })).toEqual({ text: 'Sold', tone: 'neutral' });
    expect(tradeLabel({ status: 'LOSS' })).toEqual({ text: 'Sold', tone: 'neutral' });
    expect(tradeLabel({ status: 'FLAT' })).toEqual({ text: 'Sold', tone: 'neutral' });
  });

  it('counts a multi-token run on a second line, with any position still held', () => {
    expect(tradeDetail({ scenarioCount: 1, filledCount: 1, openCount: 1 })).toBeNull();
    expect(tradeDetail({ scenarioCount: 3, filledCount: 2, openCount: 0 })).toBe(
      '2 of 3 tokens bought',
    );
    expect(tradeDetail({ scenarioCount: 3, filledCount: 2, openCount: 1 })).toBe(
      '2 of 3 tokens bought · 1 still held',
    );
  });
});

describe('pnlDisplay', () => {
  it('shows nothing realized when no position closed, saying why', () => {
    expect(
      pnlDisplay({ closedCount: 0, status: 'NO_ENTRY', realizedPnl: '0.000000000000' }),
    ).toEqual({
      kind: 'none',
      reason: 'Not bought, so nothing was realized',
    });
    expect(pnlDisplay({ closedCount: 0, status: 'OPEN', realizedPnl: '0.000000000000' })).toEqual({
      kind: 'none',
      reason: 'Still held, nothing sold yet',
    });
  });

  it('keeps a zero result of a sale, without a USDT line, and a real result with one', () => {
    expect(pnlDisplay({ closedCount: 1, status: 'FLAT', realizedPnl: '0.000000000000' })).toEqual({
      kind: 'value',
      usdt: false,
    });
    expect(pnlDisplay({ closedCount: 1, status: 'PROFIT', realizedPnl: '0.024477624000' })).toEqual(
      {
        kind: 'value',
        usdt: true,
      },
    );
    // Some sold, some still held: the realized part shows as usual.
    expect(pnlDisplay({ closedCount: 1, status: 'OPEN', realizedPnl: '-0.0385' })).toEqual({
      kind: 'value',
      usdt: true,
    });
  });
});

describe('exit reasons', () => {
  it('names each reason in the variation table\'s words, without its trailing " · no entry"', () => {
    expect(stageReason('TAKE_PROFIT')).toBe('Take profit');
    expect(stageReason('WALLET_SOLD')).toBe('Decu sold');
    expect(stageReason('HORIZON_REACHED')).toBe('Still open at the end');
    expect(stageReason('NO_DIP')).toBe('No dip');
    expect(stageReason('REFERENCE_BELOW_MIN_MARKET_CAP')).toBe('Under min market cap');
  });

  it("puts the reason of the chip's stage beside a one-token run, with its explanation", () => {
    const sold = tradeReason({
      scenarioCount: 1,
      status: 'LOSS',
      outcomes: [{ code: 'STOP_LOSS', stage: 'SOLD', count: 1 }],
    });
    expect(sold).toEqual({
      code: 'STOP_LOSS',
      text: 'Stop loss',
      explanation: 'Sold when the price reached the stop-loss level.',
    });
    expect(
      tradeReason({
        scenarioCount: 1,
        status: 'NO_ENTRY',
        outcomes: [{ code: 'NO_DIP', stage: 'NOT_BOUGHT', count: 1 }],
      })?.text,
    ).toBe('No dip');
    expect(
      tradeReason({
        scenarioCount: 1,
        status: 'OPEN',
        outcomes: [{ code: 'HORIZON_REACHED', stage: 'HELD', count: 1 }],
      })?.text,
    ).toBe('Still open at the end');
  });

  it('shows no reason when none is known, or for a run of several tokens', () => {
    expect(tradeReason({ scenarioCount: 1, status: 'PROFIT', outcomes: [] })).toBeNull();
    expect(tradeReason({ scenarioCount: 1, status: 'PROFIT' })).toBeNull();
    expect(
      tradeReason({
        scenarioCount: 3,
        status: 'LOSS',
        outcomes: [{ code: 'STOP_LOSS', stage: 'SOLD', count: 1 }],
      }),
    ).toBeNull();
  });

  it("explains a sale as a sale, even where the sweep outcome's words mean something else", () => {
    expect(reasonExplanation('WALLET_SOLD', 'SOLD')).toBe(
      'Sold because Decu sold the token while the position was held.',
    );
    expect(reasonExplanation('WALLET_SOLD', 'NOT_BOUGHT')).toMatch(/before the dip/);
    expect(reasonExplanation('NO_DIP', 'NOT_BOUGHT')).toMatch(/never fell far enough/);
  });

  it("lists a multi-token run's reasons with their counts", () => {
    expect(
      outcomeLines([
        { code: 'TAKE_PROFIT', stage: 'SOLD', count: 2 },
        { code: 'HORIZON_REACHED', stage: 'HELD', count: 1 },
        { code: 'NO_DIP', stage: 'NOT_BOUGHT', count: 1 },
      ]),
    ).toEqual([
      '2 sold · Take profit',
      '1 still held · Still open at the end',
      '1 not bought · No dip',
    ]);
    expect(outcomeLines(undefined)).toEqual([]);
  });
});

import { describe, expect, it } from 'vitest';
import {
  newRetryRequestId,
  RETRY_DAY_PREFIX,
  RETRY_ITEM_PREFIX,
  type AutomationDaySummary,
  type AutomationEvaluation,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { MOCK_AGREED_CONFIG, createAutomationDb } from '../../mocks/automationFixtures';
import { configDefaults } from '../../mocks/fixtures';
import { formValuesFrom } from '../runs/runForm';
import { isSolanaAddress } from '../../lib/base58';
import {
  nextTryText,
  allPendingText,
  zoneDate,
  addDays,
  buyBuckets,
  capPercent,
  captureBudgetFor,
  compareWorstDesc,
  dayInfo,
  dayItemCounts,
  dayRange,
  daysBetween,
  dateProblem,
  dayFilter,
  dayStartInstant,
  defaultScoredDay,
  clockText,
  editFieldReasons,
  editName,
  nameOf,
  newVariationName,
  propertiesText,
  settingChanges,
  settingsOfProperties,
  validateVariationForm,
  variationBook,
  variationConfigRequest,
  variationEditErrorText,
  variationName,
  variationText,
  isFilteredBackfill,
  isRealDate,
  parseBackfillPrefill,
  parseEvaluationStatus,
  parsePage,
  lamportsToSol,
  outcomeLabel,
  parseMints,
  pendingText,
  pnlBar,
  pnlScale,
  retriesText,
  retryErrorText,
  retryRefusalText,
  retryStepOf,
  scanFraction,
  shortSol,
  statusGrid,
  stepMarks,
  tradingDayOf,
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

const variations = createAutomationDb().variations;
const variationList = { planId: 'decu-sweep-1', items: variations };
const v = (label: string) => variations.find((x) => x.label === label)!;

describe('variations from the API', () => {
  it('derives a short text from differsFromAgreed and the config', () => {
    expect(variationText(v('V00'))).toBe('agreed (default)');
    expect(variationText(v('V04'))).toBe('take profit 80%');
    expect(variationText(v('V06'))).toBe('hold 5 min, exit 6 min');
    expect(variationText(v('V07'))).toBe('dip window 60 s');
    expect(variationText(v('V08'))).toBe('min signal 1.5 SOL');
    expect(variationText(v('V09'))).toBe('min market cap $20,000');
    const edited = {
      differsFromAgreed: ['takeProfitBps', 'stopLossBps'],
      config: {
        ...MOCK_AGREED_CONFIG,
        takeProfitBps: 8000,
        takeProfitPercent: '80',
        stopLossBps: 5000,
        stopLossPercent: '50',
      },
    };
    expect(variationText(edited)).toBe('take profit 80%, stop loss 50%');
  });

  it('merges bps and percent twins, and names a property no setting shows', () => {
    expect(settingsOfProperties(['dipBps', 'dipPercent', 'maxHoldMs'])).toEqual([
      'dipPercent',
      'maxHoldSeconds',
    ]);
    expect(propertiesText(['strategyVersion'], MOCK_AGREED_CONFIG)).toBe('strategy version 1');
    expect(clockText(null)).toBe('off');
    expect(clockText(90_000)).toBe('90 s');
    expect(clockText(120_000)).toBe('2 min');
    expect(clockText(500)).toBe('0.5 s');
  });

  it('finds the baseline, orders labels by name and names labels it does not know bare', () => {
    const book = variationBook(variationList);
    expect(book.baseline).toBe('V00');
    expect(book.describe('V10')).toBe('dip window 90 s');
    expect(book.describe('V99')).toBeNull();
    expect(variationName(book, 'V04')).toBe('V04 · take profit 80%');
    expect(variationName(book, 'V99')).toBe('V99');
    // The API's order is by name: V07, V07.1 (the legacy label V10), V07.2, V08.
    expect(book.order(['V08', 'V99', 'V07.2', 'V10', 'V07'])).toEqual([
      'V07',
      'V10',
      'V07.2',
      'V08',
      'V99',
    ]);
    const empty = variationBook(undefined);
    expect(empty.baseline).toBeNull();
    expect(newVariationName(empty)).toBeNull();
  });

  it('shows the name for a label, the label itself until the book has it, and the archived state', () => {
    const book = variationBook(variationList);
    expect(book.name('V10')).toBe('V07.1');
    expect(book.name('V07.2')).toBe('V07.2');
    expect(book.name('V04')).toBe('V04');
    expect(book.name('V99')).toBe('V99');
    expect(variationBook(undefined).name('V10')).toBe('V10');
    expect(book.archived('V10')).toBe(true);
    expect(book.archived('V07.2')).toBe(false);
    expect(book.archived('V99')).toBe(false);
    expect(variationName(book, 'V10')).toBe('V07.1 · dip window 90 s');
    expect(nameOf({ label: 'V10' })).toBe('V10');
  });

  it('names an edit as the next version in its line, and a new variation by the next base number', () => {
    expect(editName({ label: 'V12', name: 'V03.2' })).toBe('V03.3');
    expect(editName({ label: 'V04', name: 'V04' })).toBe('V04.1');
    expect(editName({ label: 'V04' })).toBe('V04.1');
    expect(editName({ label: 'odd', name: 'odd' })).toBeNull();
    // V00–V09 plus the legacy V10 and the dotted V07.2: the next base is V11.
    expect(newVariationName(variationBook(variationList))).toBe('V11');
    const legacy = ['V00', 'V09', 'V10', 'V15', 'V03.3'].map((label) => ({ label }));
    expect(newVariationName({ variations: legacy as never })).toBe('V16');
  });

  it('lists each changed setting with both values', () => {
    const values = { ...formValuesFrom(v('V04').config, null), stopLossPercent: '50' };
    expect(settingChanges(values, v('V04').config)).toEqual([
      { field: 'stopLossPercent', label: 'Stop loss', from: '40%', to: '50%' },
    ]);
  });

  it('requires a minimum signal size at or above the capture filter, and never a dataset', () => {
    const values = formValuesFrom(v('V04').config, null);
    const { limits } = configDefaults;
    expect(validateVariationForm(values, limits, '1.5')).toEqual({});
    expect(
      validateVariationForm({ ...values, minSignalSol: '' }, limits, '1.5').minSignalSol,
    ).toMatch(/Required/);
    expect(
      validateVariationForm({ ...values, minSignalSol: '1.2' }, limits, '1.5').minSignalSol,
    ).toMatch(/at least the capture filter, 1.5 SOL/);
  });

  it("takes the capture filter from the backend's status", () => {
    const values = formValuesFrom(v('V04').config, null);
    const { limits } = configDefaults;
    expect(validateVariationForm({ ...values, minSignalSol: '1.2' }, limits, '1.0')).toEqual({});
    expect(
      validateVariationForm({ ...values, minSignalSol: '0.9' }, limits, '1.0').minSignalSol,
    ).toBe('Must be at least the capture filter, 1.0 SOL');
  });

  it('posts every field of the configuration', () => {
    const body = variationConfigRequest(formValuesFrom(v('V04').config, null));
    expect(body).toMatchObject({
      takeProfitPercent: '80',
      stopLossPercent: '40',
      minSignalSol: '1.9',
    });
    expect(Object.keys(body)).toHaveLength(16);
  });

  it('places a refused field on the input that shows it', () => {
    expect(
      editFieldReasons({ 'config.takeProfitBps': 'too high', minSignalSol: 'must be set' }),
    ).toEqual({ takeProfitPercent: 'too high', minSignalSol: 'must be set' });
  });

  it('dates a trading-day range as the instants the sweep-run list takes', () => {
    expect(dayFilter('2026-10-29', '2026-10-30', ZONE)).toEqual({
      fromInstant: '2026-10-29T07:00:00.000Z',
      toInstant: '2026-10-31T07:00:00.000Z',
      errors: {},
    });
    expect(dayFilter(null, null, ZONE)).toEqual({ fromInstant: null, toInstant: null, errors: {} });
    expect(dayFilter('2026-02-31', null, ZONE).errors.from).toMatch(/not a real date/);
    const backwards = dayFilter('2026-10-30', '2026-10-29', ZONE);
    expect(backwards.errors.to).toMatch(/before the start/);
    expect(backwards.fromInstant).toBeNull();
  });

  it('explains each edit refusal in plain words', () => {
    const refusal = (code: string, fields: Record<string, string> = {}) =>
      variationEditErrorText(new ApiError(409, code, 'x', fields), 'V04');
    expect(refusal('VARIATION_ARCHIVED')).toMatch(/V04 has been archived/);
    expect(refusal('VARIATION_UNCHANGED')).toMatch(/exactly V04's own/);
    expect(refusal('VARIATION_DUPLICATE', { label: 'V02' })).toMatch(
      /Active variation V02 already/,
    );
    expect(refusal('VARIATION_BASELINE')).toMatch(/agreed baseline/);
    expect(refusal('AUTOMATION_CONFLICT')).toMatch(/at the same moment/);
    expect(refusal('VARIATION_LIMIT_REACHED')).toBe(
      'The plan has reached its limit of variations, so no new one can be saved. Nothing was saved.',
    );
    expect(refusal('CONFIG_INVALID')).toMatch(/refused some settings/);
    expect(variationEditErrorText(new ApiError(0, 'NETWORK_ERROR', 'down'), 'V04')).toMatch(
      /cannot create the variation twice/,
    );
  });
});

describe('outcomes', () => {
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
    expect(captureBudgetFor(400)).toBe(2422);
    expect(captureBudgetFor(null)).toBe(2422);
    expect(captureBudgetFor(50_000)).toBe(20_000);
  });
});

describe('URL input', () => {
  it('accepts real dates only', () => {
    expect(isRealDate('2026-11-01')).toBe(true);
    expect(isRealDate('2028-02-29')).toBe(true);
    expect(isRealDate('2026-02-29')).toBe(false);
    expect(isRealDate('2026-02-31')).toBe(false);
    expect(isRealDate('2026-13-01')).toBe(false);
    expect(isRealDate('2026-1-01')).toBe(false);
    expect(isRealDate(null)).toBe(false);
    expect(dateProblem('2026-02-31')).toBe('2026-02-31 is not a real date.');
    expect(dateProblem('yesterday')).toBe('yesterday is not a date written YYYY-MM-DD.');
    expect(dateProblem('2026-11-01')).toBeNull();
  });

  it('reads a page number, falling back to the first page', () => {
    expect(parsePage('3')).toBe(3);
    expect(parsePage(null)).toBe(1);
    expect(parsePage('abc')).toBe(1);
    expect(parsePage('0')).toBe(1);
    expect(parsePage('-2')).toBe(1);
    expect(parsePage('1.5')).toBe(1);
  });

  it('reads a verdict filter, ignoring unknown values', () => {
    expect(parseEvaluationStatus('NO_SUCCESS')).toBe('NO_SUCCESS');
    expect(parseEvaluationStatus('BOGUS')).toBeNull();
    expect(parseEvaluationStatus(null)).toBeNull();
  });

  it('keeps only valid backfill pre-fill values', () => {
    const ok = parseBackfillPrefill(
      new URLSearchParams('from=2026-10-10&to=2026-10-16&dryRun=false&budget=3150&min=2'),
    );
    expect(ok).toEqual({
      fromDay: '2026-10-10',
      toDay: '2026-10-16',
      dryRun: false,
      budget: '3150',
      minSignalSol: '2',
      ignored: [],
    });
    const bad = parseBackfillPrefill(
      new URLSearchParams('from=2026-02-31&dryRun=maybe&budget=500&min=1.2'),
    );
    expect(bad.fromDay).toBeNull();
    expect(bad.dryRun).toBe(true);
    // 500 is a valid dry-run budget; the capture minimum would not apply.
    expect(bad.budget).toBe('500');
    expect(bad.minSignalSol).toBeNull();
    expect(bad.ignored).toEqual(['dryRun', 'from', 'min']);
    expect(parseBackfillPrefill(new URLSearchParams('dryRun=false&budget=500')).ignored).toEqual([
      'budget',
    ]);
  });
});

describe('retry', () => {
  const REQUEST_ID = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/;

  it('makes a new request id per call that the backend accepts, with room for the per-buy suffix', () => {
    const a = newRetryRequestId(RETRY_ITEM_PREFIX);
    const b = newRetryRequestId(RETRY_ITEM_PREFIX);
    const day = newRetryRequestId(RETRY_DAY_PREFIX);
    expect(a).not.toBe(b);
    for (const id of [a, b, day]) {
      expect(id).toMatch(REQUEST_ID);
      expect(id.length).toBeLessThanOrEqual(55);
    }
    expect(a).toMatch(/^ui-retry-[0-9a-f]{32}$/);
    expect(day).toMatch(/^ui-retry-day-[0-9a-f]{32}$/);
  });

  it('explains every refusal code in plain words', () => {
    expect(retryRefusalText('ITEM_NOT_FAILED')).toMatch(/Only a failed buy/);
    expect(retryRefusalText('RETRY_NOT_ALLOWED', 'BACKFILL_BUDGET')).toMatch(
      /backfill ran out of request budget/,
    );
    expect(retryRefusalText('RETRY_NOT_ALLOWED', 'MISSING_BLOCK_TIME')).toMatch(/no block time/);
    expect(retryRefusalText('RETRY_NOT_ALLOWED', null)).toBe(
      'This kind of failure cannot be retried.',
    );
    expect(retryRefusalText('BACKFILL_NOT_CAPTURING')).toMatch(/still scanning/);
    expect(retryRefusalText('CAPTURE_DISABLED')).toMatch(/Capture is off/);
    expect(retryRefusalText('SOMETHING_NEW')).toBe('Not retried: Something new.');
  });

  it('turns a failed retry request into a readable message', () => {
    expect(
      retryErrorText(
        new ApiError(409, 'RETRY_NOT_ALLOWED', 'not allowed', {
          failureCode: 'MISSING_BLOCK_TIME',
        }),
      ),
    ).toMatch(/no block time/);
    expect(retryErrorText(new ApiError(409, 'ITEM_NOT_FAILED', 'x'))).toMatch(/no longer failed/);
    expect(retryErrorText(new ApiError(409, 'CAPTURE_DISABLED', 'x'))).toMatch(/Capture is off/);
    expect(retryErrorText(new ApiError(404, 'AUTOMATION_ITEM_NOT_FOUND', 'x'))).toBe(
      'This buy no longer exists on the backend.',
    );
    expect(
      retryErrorText(new ApiError(400, 'INVALID_REQUEST', 'requestId must not be blank')),
    ).toBe('The backend refused the retry request: requestId must not be blank');
    expect(retryErrorText(new ApiError(503, 'UNAVAILABLE', 'Backend unreachable'))).toBe(
      'Backend unreachable',
    );
  });

  it('sends a buy back to the step where it failed, from the start when its capture was deleted', () => {
    expect(retryStepOf({ step: 'RATES', failureCode: 'INCOMPLETE' })).toBe('RATES');
    expect(retryStepOf({ step: 'MARKET', failureCode: 'CAPTURE_NOT_FOUND' })).toBe(
      'CREATE_SESSION',
    );
    expect(retryStepOf({ step: 'SWEEP', failureCode: 'SWEEP_FAILED' })).toBe('SWEEP');
  });

  it('counts retries only once there is one', () => {
    expect(retriesText(0)).toBeNull();
    expect(retriesText(1)).toBe('retried 1×');
  });
});

describe('all-pending days', () => {
  const day = (key: string, over: Partial<AutomationDaySummary> = {}): AutomationDaySummary => ({
    day: key,
    from: `${key}T07:00:00Z`,
    to: `${key}T07:00:00Z`,
    covered: true,
    complete: false,
    sessions: 1,
    buys: 3,
    belowFilter: 0,
    captured: 1,
    simulated: 1,
    failed: 0,
    abandoned: 0,
    requestsUsed: 100,
    bestStatus: 'PENDING',
    bestLabel: null,
    pendingDay: key,
    ...over,
  });

  it('says how many days are pending and why, most common reason first', () => {
    const days = [
      day('2026-11-02'),
      day('2026-11-01'),
      day('2026-10-31'),
      day('2026-10-30', { complete: true }),
      day('2026-10-29', { covered: false }),
    ];
    expect(allPendingText(days.slice(1, 3), '2026-11-02', true)).toBe(
      '2 days pending: buys still capturing',
    );
    expect(allPendingText(days, '2026-11-02', true)).toBe(
      '5 days pending: buys still capturing (2), today is still running (1), waiting for earlier days of their 7-day window (1), not covered by discovery (1)',
    );
    expect(allPendingText(days, '2026-11-02', false)).toBe(
      '5 days pending: discovery only, so no buy is captured or replayed',
    );
  });

  it('says nothing once any day has a verdict', () => {
    expect(
      allPendingText(
        [day('2026-11-01'), day('2026-10-31', { bestStatus: 'SUCCESS' })],
        '2026-11-02',
        true,
      ),
    ).toBeNull();
    expect(allPendingText([], '2026-11-02', true)).toBeNull();
  });

  it('reads a date on the automation clock', () => {
    expect(zoneDate('2026-11-02T20:00:00Z', '+05:00')).toBe('3 Nov');
    expect(zoneDate(null, '+05:00')).toBe('—');
  });
});

describe('next try', () => {
  // 2 Nov 2026, 22:00 UTC+5.
  const now = Date.parse('2026-11-02T17:00:00Z');

  it('shows the time today, a date on another day, and overdue once past', () => {
    expect(nextTryText('2026-11-02T18:27:00Z', '+05:00', now)).toBe('next try 23:27');
    expect(nextTryText('2026-11-03T05:27:00Z', '+05:00', now)).toBe('next try 3 Nov, 10:27');
    expect(nextTryText('2026-11-02T16:00:00Z', '+05:00', now)).toBe('next try overdue since 21:00');
    expect(nextTryText('2026-10-31T05:27:00Z', '+05:00', now)).toBe(
      'next try overdue since 31 Oct, 10:27',
    );
  });
});

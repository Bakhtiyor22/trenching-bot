import { describe, expect, it } from 'vitest';
import { CAPTURE_STATUSES, type CaptureStageName, type CaptureSummary } from '../../api/captures';
import { captureOperation } from '../../mocks/captureFixtures';
import { nowCopy } from './captureNow';

const summary: CaptureSummary = {
  status: 'STOPPED_LIMIT',
  reasonCode: null,
  currentStep: { index: 2, stage: 'MARKETS' },
  steps: [],
  progress: { processed: 40, selected: 100, percent: '40' },
  nextAction: {
    kind: 'RESUME_MARKETS',
    stage: 'MARKETS',
    attemptId: null,
    caseId: null,
    maxRequests: 500,
  },
  cases: { total: 1, ready: 0, downloading: 1, blocked: 0 },
  lastProgressAt: null,
};

describe('capture now copy', () => {
  it('explains a market request stop using this run allowance and saved progress', () => {
    expect(nowCopy(summary, captureOperation)).toEqual({
      headline: "Markets stopped after using this run's 400 requests",
      body: 'Nothing is lost: 40 of 100 transactions are saved. Resume to download the remaining 60. After that come rates, then the dataset.',
    });
  });

  it.each(CAPTURE_STATUSES)('provides a headline and explanation for %s', (status) => {
    const copy = nowCopy({ ...summary, status }, captureOperation);
    expect(copy.headline.length).toBeGreaterThan(12);
    expect(copy.body.length).toBeGreaterThan(40);
    expect(copy.headline).not.toMatch(/undefined|null|NaN/);
    expect(copy.body).not.toMatch(/undefined|null|NaN/);
  });

  it.each([
    ['WALLET', 'Wallet needs a check', 'buys'],
    ['MARKETS', 'Markets need a check', 'market window'],
    ['RATES', 'Rates need a check', 'minute prices'],
    ['DATASET', 'Dataset needs a check', 'offline'],
  ] as Array<[CaptureStageName, string, string]>)(
    'explains attention at %s',
    (stage, headline, detail) => {
      const copy = nowCopy(
        { ...summary, status: 'NEEDS_ATTENTION', currentStep: { index: 1, stage } },
        null,
      );
      expect(copy.headline).toBe(headline);
      expect(copy.body).toContain(detail);
    },
  );

  it('uses the next allowance when no operation exists and never gives negative remaining work', () => {
    const copy = nowCopy(
      { ...summary, progress: { processed: 110, selected: 100, percent: '100' } },
      null,
    );
    expect(copy.headline).toContain('500 requests');
    expect(copy.body).toContain('remaining 0');
  });

  it('names a data limit without claiming that the request allowance was used', () => {
    expect(nowCopy({ ...summary, reasonCode: 'BYTE_BUDGET' }, captureOperation).headline).toBe(
      "Markets reached this run's data limit",
    );
  });
});

import { expect, it } from 'vitest';
import type { CaptureSummary, NextActionKind } from '../../api/captures';
import { nextActionHint } from './useNextAction';

const summary = (
  kind: NextActionKind,
  maxRequests: number | null = null,
  status: CaptureSummary['status'] = 'NOT_STARTED',
): CaptureSummary => ({
  status,
  nextAction: { kind, maxRequests, stage: null, attemptId: null, caseId: null },
  reasonCode: null,
  currentStep: { index: 1, stage: 'WALLET' },
  steps: [],
  cases: { total: 0, ready: 0, blocked: 0, downloading: 0 },
  progress: null,
  lastProgressAt: null,
});
it.each([
  ['START_WALLET', 'At most 400 requests'],
  ['RESUME_WALLET', 'At most 400 requests'],
  ['START_MARKETS', 'At most 400 requests'],
  ['RESUME_MARKETS', 'At most 400 requests'],
  ['CAPTURE_RATES', 'At most 400 requests'],
  ['RETRY_WALLET', 'Safe to retry; nothing is lost'],
  ['REGISTER_DATASET', 'Offline; no provider requests'],
  ['START_RUN', 'Uses the agreed strategy by default'],
  ['WAIT', 'Pause or cancel from the capture page'],
  ['VIEW_RESULTS', "Opens this dataset's runs"],
  ['INSPECT', 'Checks saved files; no provider requests'],
  ['NONE', 'No further action needed'],
] as const)('explains %s', (kind, expected) => {
  const budget = [
    'START_WALLET',
    'RESUME_WALLET',
    'START_MARKETS',
    'RESUME_MARKETS',
    'CAPTURE_RATES',
  ].includes(kind)
    ? 400
    : null;
  expect(nextActionHint(summary(kind, budget))).toBe(expected);
});
it('explains an active operation', () =>
  expect(nextActionHint(summary('START_MARKETS', 400, 'RUNNING'))).toBe(
    'Pause or cancel from the capture page',
  ));

import { describe, expect, it } from 'vitest';
import { codeLabel } from '../../lib/codes';
import { knownReasonText, reasonText } from './captureReasons';

describe('Wallet + token reason labels', () => {
  it.each([
    [
      'SAME_SLOT_BUY',
      'Another buy of this token by the wallet is in the same slot as its earliest buy, and the order within a slot is not recorded, so neither can be called the first.',
    ],
    [
      'FIRST_BUY_NOT_LISTED',
      "The wallet's Pump.fun accumulator listing did not include its known buy, so the provider's listing cannot be trusted to show earlier buys for this case.",
    ],
    [
      'QUALIFICATION_PENDING',
      'Dataset exported; qualification has not run yet or did not finish. Use Qualify.',
    ],
    [
      'FIRST_BUY_MISMATCH',
      'The replay started from a different buy than the proven first buy; this is a data problem, not a strategy outcome.',
    ],
    [
      'EXPORT_CHANGED',
      'The exported dataset changed or is missing since export. Export again, then qualify.',
    ],
    ['EXPORT_REQUIRED', 'Export the dataset before qualifying.'],
    ['UNSUPPORTED_SCOPE', 'Qualification is only available for wallet + token sessions.'],
  ])('explains %s', (code, label) => {
    expect(reasonText(code)).toBe(label);
  });
});

describe('RPC credit reason labels', () => {
  it('explains an operation that failed because the last account ran out of credits', () => {
    const text = knownReasonText('CREDITS_EXHAUSTED');
    expect(text).toMatch(/ran out of credits during this run/);
    expect(text).toMatch(/Evidence so far is kept; retry once an account has credits again/);
  });

  it('explains a start refused while no account has credits', () => {
    const text = knownReasonText('RPC_CREDITS_EXHAUSTED');
    expect(text).toMatch(/Every RPC account has used up its credits/);
    expect(text).toMatch(/Nothing was spent or lost/);
  });

  it('gives both codes their words through the shared code label', () => {
    expect(codeLabel('CREDITS_EXHAUSTED')).toBe(knownReasonText('CREDITS_EXHAUSTED'));
    expect(codeLabel('RPC_CREDITS_EXHAUSTED')).toBe(knownReasonText('RPC_CREDITS_EXHAUSTED'));
  });
});

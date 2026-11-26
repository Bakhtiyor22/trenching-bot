import { describe, expect, it } from 'vitest';
import { rpcAccountsFixture } from '../../mocks/rpcAccountFixtures';
import {
  accountLabel,
  accountStateChip,
  awaitsNetworkCheck,
  creditsNote,
  creditsShare,
  cycleEnd,
  cycleEndText,
  daysUntil,
  isCaptureHeld,
  isLowBalance,
  orderedAccounts,
  projectIdVariable,
  rpcUrlVariable,
} from './rpcAccounts';

const NOW = Date.parse('2026-11-22T09:30:00Z');

describe('RPC account model', () => {
  it('names the accounts and the variables that configure them', () => {
    expect(accountLabel('primary')).toBe('Primary');
    expect(accountLabel('backup')).toBe('Backup');
    expect(accountLabel('third_one')).toBe('Third one');
    expect(rpcUrlVariable('primary')).toBe('SOLANA_RPC_URL');
    expect(rpcUrlVariable('backup')).toBe('SOLANA_RPC_BACKUP_URL');
    expect(projectIdVariable('primary')).toBe('HELIUS_PROJECT_ID');
    expect(projectIdVariable('backup')).toBe('HELIUS_BACKUP_PROJECT_ID');
  });

  it('gives every state a word, a glyph and a tone, and says when a state is unknown', () => {
    expect(accountStateChip('ACTIVE')).toEqual({
      label: 'In use',
      tone: 'success',
      glyph: '✓',
      known: true,
    });
    expect(accountStateChip('STANDBY')).toMatchObject({ label: 'Standby', tone: 'neutral' });
    expect(accountStateChip('OUT_OF_CREDITS')).toMatchObject({
      label: 'Out of credits',
      tone: 'attention',
      glyph: '▲',
    });
    expect(accountStateChip('WRONG_NETWORK')).toMatchObject({
      label: 'Wrong network',
      tone: 'failure',
      glyph: '✕',
    });
    expect(accountStateChip('NOT_CONFIGURED')).toMatchObject({
      label: 'Not configured',
      tone: 'neutral',
    });
    expect(accountStateChip('SOMETHING_NEW')).toEqual({
      label: 'Something new',
      tone: 'neutral',
      glyph: '–',
      known: false,
    });
  });

  it('puts the primary first whatever order the accounts arrive in', () => {
    const { accounts } = rpcAccountsFixture('healthy', NOW);
    expect(orderedAccounts([...accounts].reverse()).map((a) => a.name)).toEqual([
      'primary',
      'backup',
    ]);
  });

  it('holds capture only while a configured account exists and none is active', () => {
    expect(isCaptureHeld(rpcAccountsFixture('healthy', NOW))).toBe(false);
    expect(isCaptureHeld(rpcAccountsFixture('backup-active', NOW))).toBe(false);
    expect(isCaptureHeld(rpcAccountsFixture('all-out', NOW))).toBe(true);
    expect(isCaptureHeld(rpcAccountsFixture('backup-unverified', NOW))).toBe(true);
    // No RPC at all is a setup state, not a hold for credits.
    expect(isCaptureHeld(rpcAccountsFixture('unconfigured', NOW))).toBe(false);
  });

  it('tells a backup waiting for its mainnet check from accounts that are out of credits', () => {
    expect(awaitsNetworkCheck(rpcAccountsFixture('backup-unverified', NOW))).toBe(true);
    expect(awaitsNetworkCheck(rpcAccountsFixture('all-out', NOW))).toBe(false);
    // A standby backup behind an active primary is simply waiting its turn.
    expect(awaitsNetworkCheck(rpcAccountsFixture('healthy', NOW))).toBe(false);
  });

  it('reads the share of credits left and clamps it', () => {
    expect(creditsShare(412_300, 1_000_000)).toBeCloseTo(0.4123);
    expect(creditsShare(0, 1_000_000)).toBe(0);
    expect(creditsShare(1_200_000, 1_000_000)).toBe(1);
    expect(creditsShare(null, 1_000_000)).toBeNull();
    expect(creditsShare(10, 0)).toBeNull();
  });

  it('marks a balance low by the backend rule: strictly under ten percent of a limit', () => {
    expect(isLowBalance(99_999, 1_000_000)).toBe(true);
    expect(isLowBalance(100_000, 1_000_000)).toBe(false);
    expect(isLowBalance(0, 1_000_000)).toBe(true);
    expect(isLowBalance(5, 0)).toBe(false);
    expect(isLowBalance(null, 1_000_000)).toBe(false);
  });

  it('writes the note under the credits', () => {
    expect(
      creditsNote({ creditsRemaining: 412_300, creditsUsed: 587_700, creditsLimit: 1_000_000 }),
    ).toBe('41.2% left · 587,700 used this cycle');
    expect(
      creditsNote({ creditsRemaining: 62_480, creditsUsed: 937_520, creditsLimit: 1_000_000 }),
    ).toBe('6.2% left · low: under 10% · 937,520 used this cycle');
    expect(
      creditsNote({ creditsRemaining: 1_250_000, creditsUsed: 0, creditsLimit: 1_000_000 }),
    ).toBe('more than the plan limit: prepaid credits count too · 0 used this cycle');
    expect(creditsNote({ creditsRemaining: null, creditsUsed: null, creditsLimit: null })).toBe('');
    // A whole share has no decimal, and one too small for a decimal is not rounded to nothing.
    expect(
      creditsNote({ creditsRemaining: 1_000_000, creditsUsed: 0, creditsLimit: 1_000_000 }),
    ).toBe('100% left · 0 used this cycle');
    expect(
      creditsNote({ creditsRemaining: 300, creditsUsed: 999_700, creditsLimit: 1_000_000 }),
    ).toBe('under 0.1% left · low: under 10% · 999,700 used this cycle');
    // An empty account says only what it used: the figure and the chip already say it is empty.
    expect(
      creditsNote({ creditsRemaining: 0, creditsUsed: 1_000_000, creditsLimit: 1_000_000 }),
    ).toBe('1,000,000 used this cycle');
  });

  it('counts whole UTC days to the cycle end and refuses a date that does not exist', () => {
    expect(daysUntil('2026-11-30', NOW)).toBe(8);
    expect(daysUntil('2026-11-22', NOW)).toBe(0);
    expect(daysUntil('2026-11-21', NOW)).toBe(-1);
    expect(daysUntil('2026-02-31', NOW)).toBeNull();
    expect(daysUntil('soon', NOW)).toBeNull();
  });

  it('says when the cycle ends as a UTC date with the days left', () => {
    expect(cycleEndText('2026-11-30', NOW)).toBe('30 Nov 2026 (UTC) · in 8 days');
    expect(cycleEndText('2026-11-23', NOW)).toBe('23 Nov 2026 (UTC) · in 1 day');
    expect(cycleEndText('2026-11-22', NOW)).toBe('22 Nov 2026 (UTC) · today');
    expect(cycleEndText('2026-11-20', NOW)).toBe('20 Nov 2026 (UTC) · 2 days ago');
    expect(cycleEndText(null, NOW)).toBe('—');
    expect(cycleEndText('2026-02-31', NOW)).toBe('2026-02-31 (UTC)');
    expect(cycleEnd('2026-11-30', NOW)).toEqual({ date: '30 Nov 2026 (UTC)', when: 'in 8 days' });
    expect(cycleEnd(null, NOW)).toBeNull();
  });
});

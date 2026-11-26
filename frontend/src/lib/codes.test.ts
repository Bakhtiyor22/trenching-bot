import { describe, expect, it } from 'vitest';
import type { UsageProvider, UsagePurpose, UsageTask } from '../api/setup';
import {
  codeLabel,
  providerLabel,
  providerLabels,
  purposeLabel,
  purposeLabels,
  taskLabel,
  taskLabels,
} from './codes';

// Every code the backend's ApiProvider, ApiPurpose and ApiTask enums can report (plan 20261122 added the last ones).
const PROVIDERS: UsageProvider[] = ['SOLANA_RPC', 'BINANCE', 'TELEGRAM', 'HELIUS_ADMIN'];
const PURPOSES: UsagePurpose[] = [
  'READ_TRANSACTION',
  'LIST_SIGNATURES',
  'MAINNET_CHECK',
  'MINT_PROGRAM_CHECK',
  'CONNECTION_CHECK',
  'SLOT_PACE',
  'BLOCK_TIME',
  'MINUTE_RATES',
  'LIVE_RATE',
  'SEND_NOTIFICATION',
  'CREDIT_BALANCE',
  'UNKNOWN',
];
const TASKS: UsageTask[] = [
  'WALLET_STEP',
  'MARKET_STEP',
  'RATES_STEP',
  'CASE_RATES',
  'WALLET_ACTIVITY',
  'CONNECTION_CHECK',
  'LIVE_PRICE',
  'NOTIFICATION',
  'ACCOUNT_BALANCE',
  'UNATTRIBUTED',
];

describe('usage code labels', () => {
  it('has written words for every provider, purpose and task the backend reports', () => {
    for (const provider of PROVIDERS) expect(providerLabels[provider]).toBeTruthy();
    for (const purpose of PURPOSES) expect(purposeLabels[purpose]).toBeTruthy();
    for (const task of TASKS) expect(taskLabels[task]).toBeTruthy();
  });

  it('names the notification and account balance codes in plain words', () => {
    expect(providerLabel('HELIUS_ADMIN')).toBe('Helius admin API');
    expect(providerLabel('TELEGRAM')).toBe('Telegram');
    expect(taskLabel('NOTIFICATION')).toBe('Telegram messages');
    expect(taskLabel('ACCOUNT_BALANCE')).toBe('RPC account balances');
    expect(purposeLabel('SEND_NOTIFICATION')).toMatch(/^Send one Telegram message/);
    expect(purposeLabel('CREDIT_BALANCE')).toMatch(/^Read one RPC account's credits/);
  });

  it('turns a code it does not know into words instead of a blank', () => {
    expect(providerLabel('SOME_NEW_API')).toBe('Some new api');
    expect(purposeLabel('SOME_NEW_PURPOSE')).toBe('Some new purpose');
    expect(taskLabel('SOME_NEW_TASK')).toBe('Some new task');
  });
});

describe('credit code labels', () => {
  it('explains the capture refusal and the provider failure for used-up RPC credits', () => {
    expect(codeLabel('RPC_CREDITS_EXHAUSTED')).toMatch(/Every RPC account has used up its credits/);
    expect(codeLabel('CREDITS_EXHAUSTED')).toMatch(/ran out of credits during this run/);
  });
});

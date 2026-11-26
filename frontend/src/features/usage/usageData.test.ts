import { describe, expect, it } from 'vitest';
import { aggregateUsage, groupUsageCalls, orderedProviders, sortUsageTasks } from './usageData';

describe('usage units', () => {
  it('groups five minute points by hour and provider without merging providers', () => {
    const points = [
      { bucketStart: '2026-10-16T10:05:00Z', provider: 'SOLANA_RPC' as const, requests: 2 },
      { bucketStart: '2026-10-16T10:55:00Z', provider: 'SOLANA_RPC' as const, requests: 3 },
      { bucketStart: '2026-10-16T10:05:00Z', provider: 'BINANCE' as const, requests: 1 },
    ];
    expect(aggregateUsage(points, 3600)).toHaveLength(2);
    expect(aggregateUsage(points, 3600).find((p) => p.provider === 'SOLANA_RPC')?.requests).toBe(5);
    expect(aggregateUsage(points, 300)).toHaveLength(3);
  });
  it('groups provider rows and sorts tasks by requests descending', () => {
    const totals = {
      requests: 2,
      failedRequests: 0,
      rateLimitedResponses: 0,
      responseBytes: 0,
      cacheHits: 0,
    };
    const call = {
      operation: 'test',
      purpose: 'UNKNOWN' as const,
      totals,
      sharePercent: '50',
      lastCallAt: null,
    };
    expect(
      groupUsageCalls([
        { ...call, provider: 'BINANCE' },
        { ...call, provider: 'SOLANA_RPC' },
        { ...call, provider: 'SOLANA_RPC', operation: 'other' },
      ]).map((group) => [group.provider, group.calls.length]),
    ).toEqual([
      ['SOLANA_RPC', 2],
      ['BINANCE', 1],
    ]);
    expect(
      sortUsageTasks([
        { task: 'WALLET_STEP', totals },
        { task: 'MARKET_STEP', totals: { ...totals, requests: 10 } },
      ]).map((task) => task.task),
    ).toEqual(['MARKET_STEP', 'WALLET_STEP']);
  });
  it('lists every provider in a fixed order, a provider it does not know last instead of dropping it', () => {
    const totals = {
      requests: 1,
      failedRequests: 0,
      rateLimitedResponses: 0,
      responseBytes: 0,
      cacheHits: 0,
    };
    const call = {
      operation: 'test',
      purpose: 'UNKNOWN' as const,
      totals,
      sharePercent: '20',
      lastCallAt: null,
    };
    expect(
      groupUsageCalls([
        { ...call, provider: 'NEW_PROVIDER' as never },
        { ...call, provider: 'HELIUS_ADMIN' },
        { ...call, provider: 'TELEGRAM' },
        { ...call, provider: 'SOLANA_RPC' },
      ]).map((group) => group.provider),
    ).toEqual(['SOLANA_RPC', 'TELEGRAM', 'HELIUS_ADMIN', 'NEW_PROVIDER']);
    expect(orderedProviders(['HELIUS_ADMIN', 'BINANCE', 'BINANCE'])).toEqual([
      'BINANCE',
      'HELIUS_ADMIN',
    ]);
  });
});

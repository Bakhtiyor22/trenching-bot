import { describe, expect, it } from 'vitest';
import { aggregateUsage, groupUsageCalls, megabytes, sortUsageTasks } from './usageData';

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
    expect(megabytes(1_250_000)).toBe('1.25 MB');
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
});

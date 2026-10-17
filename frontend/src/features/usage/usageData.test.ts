import { describe, expect, it } from 'vitest';
import { aggregateUsage, megabytes } from './usageData';

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
});

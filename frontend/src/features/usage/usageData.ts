import type { UsagePoint } from '../../api/setup';

export function aggregateUsage(points: UsagePoint[], seconds: number): UsagePoint[] {
  const buckets = new Map<string, UsagePoint>();
  for (const point of points) {
    const bucketStart = new Date(
      Math.floor(Date.parse(point.bucketStart) / (seconds * 1000)) * seconds * 1000,
    ).toISOString();
    const key = `${bucketStart}/${point.provider}`;
    buckets.set(key, {
      bucketStart,
      provider: point.provider,
      requests: (buckets.get(key)?.requests ?? 0) + point.requests,
    });
  }
  return [...buckets.values()].sort(
    (a, b) => a.bucketStart.localeCompare(b.bucketStart) || a.provider.localeCompare(b.provider),
  );
}

export function megabytes(bytes: number): string {
  return `${(bytes / 1_000_000).toLocaleString('en-US', { maximumFractionDigits: 3 })} MB`;
}

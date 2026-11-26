import type { UsagePoint, UsageProvider, UsageReport } from '../../api/setup';
import { capturesApi } from '../../api/captures';

/** The providers in the order every usage view lists them, so a provider keeps its place and its colour. */
export const PROVIDER_ORDER: UsageProvider[] = [
  'SOLANA_RPC',
  'BINANCE',
  'TELEGRAM',
  'HELIUS_ADMIN',
];

/**
 * `present` in {@link PROVIDER_ORDER}; a provider this console does not know yet goes after them, by name, so its
 * requests are listed and not dropped.
 */
export function orderedProviders(present: Iterable<UsageProvider>): UsageProvider[] {
  const all = new Set(present);
  const unknown = [...all].filter((provider) => !PROVIDER_ORDER.includes(provider)).sort();
  return [...PROVIDER_ORDER.filter((provider) => all.has(provider)), ...unknown];
}

export function groupUsageCalls(calls: UsageReport['byCall']) {
  return orderedProviders(calls.map((call) => call.provider)).map((provider) => ({
    provider,
    calls: calls.filter((call) => call.provider === provider),
  }));
}

export function sortUsageTasks(tasks: UsageReport['byTask']) {
  return [...tasks].sort(
    (a, b) => b.totals.requests - a.totals.requests || a.task.localeCompare(b.task),
  );
}

export async function allCaptureChoices(signal: AbortSignal) {
  const first = await capturesApi.list(0, signal);
  const items = [...first.items];
  const pages = Math.ceil(first.total / Math.max(1, first.size));
  for (let page = 1; page < pages; page++) {
    signal.throwIfAborted();
    const next = await capturesApi.list(page, signal);
    items.push(...next.items);
    if (next.items.length === 0) break;
  }
  return [...new Map(items.map((item) => [item.id, item])).values()];
}

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

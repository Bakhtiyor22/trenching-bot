import { useSyncExternalStore } from 'react';
import type { Gauges } from '../../api/types';

/** How long the browser keeps polled gauge samples (plan decision 7). */
export const GAUGE_HISTORY_MS = 30 * 60 * 1000;

export interface GaugeSample {
  /** Sample time (epoch ms of the dashboard's `generatedAt`). */
  t: number;
  heapUsedBytes: number | null;
  heapMaxBytes: number | null;
  httpMeanLatencyMs: number | null;
  /** Requests per second since the previous sample; null for the first sample or after a restart. */
  httpRequestRate: number | null;
  httpRequestCount: number | null;
}

/**
 * In-memory ring of the last 30 minutes of gauge samples. Client-side only: it resets on reload
 * and is not shared between tabs. Kept at module level so it survives navigating away and back.
 */
export class GaugeHistory {
  private samples: GaugeSample[] = [];
  private listeners = new Set<() => void>();

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  snapshot = (): GaugeSample[] => this.samples;

  record(generatedAt: string, gauges: Gauges): void {
    const t = Date.parse(generatedAt);
    if (Number.isNaN(t)) return;
    const last = this.samples.at(-1);
    if (last && t <= last.t) return; // same or older poll result
    let rate: number | null = null;
    if (last && last.httpRequestCount != null && gauges.httpRequestCount != null) {
      const delta = gauges.httpRequestCount - last.httpRequestCount;
      const seconds = (t - last.t) / 1000;
      // A negative delta means the backend restarted and its counter reset.
      rate = delta >= 0 && seconds > 0 ? delta / seconds : null;
    }
    const sample: GaugeSample = {
      t,
      heapUsedBytes: gauges.heapUsedBytes ?? null,
      heapMaxBytes: gauges.heapMaxBytes ?? null,
      httpMeanLatencyMs: gauges.httpMeanLatencyMs ?? null,
      httpRequestCount: gauges.httpRequestCount ?? null,
      httpRequestRate: rate,
    };
    const cutoff = t - GAUGE_HISTORY_MS;
    this.samples = [...this.samples.filter((s) => s.t >= cutoff), sample];
    this.listeners.forEach((listener) => listener());
  }

  clear(): void {
    this.samples = [];
    this.listeners.forEach((listener) => listener());
  }
}

export const gaugeHistory = new GaugeHistory();

export function useGaugeHistory(history: GaugeHistory = gaugeHistory): GaugeSample[] {
  return useSyncExternalStore(history.subscribe, history.snapshot, history.snapshot);
}

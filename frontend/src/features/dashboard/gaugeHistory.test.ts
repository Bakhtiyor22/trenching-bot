import { describe, expect, it } from 'vitest';
import type { Gauges } from '../../api/types';
import { GAUGE_HISTORY_MS, GaugeHistory } from './gaugeHistory';

const gauges = (count: number | null): Gauges => ({
  heapUsedBytes: 100,
  heapMaxBytes: 1000,
  uptimeMs: 1,
  httpRequestCount: count,
  cpuUsage: null,
  httpMeanLatencyMs: 5,
});

describe('GaugeHistory', () => {
  it('derives the request rate from successive counts and ignores repeated polls', () => {
    const history = new GaugeHistory();
    history.record('2026-10-03T10:00:00Z', gauges(100));
    history.record('2026-10-03T10:00:05Z', gauges(110));
    history.record('2026-10-03T10:00:05Z', gauges(999));
    const samples = history.snapshot();
    expect(samples).toHaveLength(2);
    expect(samples[0]?.httpRequestRate).toBeNull();
    expect(samples[1]?.httpRequestRate).toBe(2);
  });

  it('drops samples older than 30 minutes and treats a counter reset as unknown rate', () => {
    const history = new GaugeHistory();
    const start = Date.parse('2026-10-03T10:00:00Z');
    history.record(new Date(start).toISOString(), gauges(500));
    history.record(new Date(start + GAUGE_HISTORY_MS + 1000).toISOString(), gauges(3));
    const samples = history.snapshot();
    expect(samples).toHaveLength(1);
    expect(samples[0]?.httpRequestRate).toBeNull();
  });
});

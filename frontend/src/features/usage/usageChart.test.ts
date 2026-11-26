import { describe, expect, it } from 'vitest';
import type { UsageReport } from '../../api/setup';
import { terminal, terminalLight } from '../../theme';
import {
  bucketWord,
  buildUsageChartOption,
  providerColor,
  usageBucketSeconds,
  usageBuckets,
} from './usageChart';

const totals = {
  requests: 0,
  failedRequests: 0,
  rateLimitedResponses: 0,
  responseBytes: 0,
  cacheHits: 0,
};

function report(overrides: Partial<UsageReport>): UsageReport {
  return {
    from: '2026-11-03T10:00:00Z',
    to: '2026-11-03T11:00:00Z',
    bucketSeconds: 300,
    recordingStartedAt: '2026-11-01T00:00:00Z',
    totals,
    byCall: [],
    byTask: [],
    series: [],
    pacing: [],
    ...overrides,
  };
}

type BarSeries = { name: string; data: Array<number | null>; itemStyle: { color: string } };

function seriesOf(option: ReturnType<typeof buildUsageChartOption>): BarSeries[] {
  return option.series as BarSeries[];
}

function onlySeries(option: ReturnType<typeof buildUsageChartOption>): BarSeries {
  const [first, ...rest] = seriesOf(option);
  if (!first || rest.length > 0) throw new Error('expected exactly one series');
  return first;
}

describe('usage chart option', () => {
  it('puts every five-minute bucket of the last hour on the axis, quiet ones as zero', () => {
    const usage = report({
      series: [
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'SOLANA_RPC', requests: 4 },
        { bucketStart: '2026-11-03T10:40:00Z', provider: 'SOLANA_RPC', requests: 2 },
      ],
    });
    expect(usageBucketSeconds(usage, 'LAST_HOUR')).toBe(300);
    const buckets = usageBuckets(usage, 'LAST_HOUR', 300);
    expect(buckets).toHaveLength(13);
    expect(buckets[0]).toBe('2026-11-03T10:00:00.000Z');
    expect(buckets[12]).toBe('2026-11-03T11:00:00.000Z');

    const option = buildUsageChartOption(usage, 'LAST_HOUR', terminal);
    expect((option.xAxis as { data: string[] }).data).toHaveLength(13);
    const solana = onlySeries(option);
    expect(solana.name).toBe('Solana RPC');
    expect(solana.data).toEqual([0, 4, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0]);
  });

  it('builds hourly buckets for a day and marks buckets before recording started as not recorded', () => {
    const usage = report({
      from: '2026-11-02T11:00:00Z',
      to: '2026-11-03T11:00:00Z',
      recordingStartedAt: '2026-11-03T09:30:00Z',
      series: [{ bucketStart: '2026-11-03T10:15:00Z', provider: 'BINANCE', requests: 3 }],
    });
    expect(usageBucketSeconds(usage, 'LAST_24_HOURS')).toBe(3600);
    const binance = onlySeries(buildUsageChartOption(usage, 'LAST_24_HOURS', terminal));
    expect(binance.data).toHaveLength(25);
    // 11:00 … 08:00 end before 09:30: unknown, not zero. 09:00 overlaps recording, so it counts.
    expect(binance.data.slice(0, 22).every((value) => value === null)).toBe(true);
    expect(binance.data.slice(22)).toEqual([0, 3, 0]);
  });

  it('starts "All recorded" when recording began and switches to days over a long stretch', () => {
    const short = report({
      from: '1970-01-01T00:00:00Z',
      recordingStartedAt: '2026-11-03T08:00:00Z',
      series: [{ bucketStart: '2026-11-03T08:10:00Z', provider: 'SOLANA_RPC', requests: 1 }],
    });
    expect(usageBucketSeconds(short, 'ALL')).toBe(3600);
    expect(usageBuckets(short, 'ALL', 3600)).toHaveLength(4);

    const long = report({
      from: '1970-01-01T00:00:00Z',
      recordingStartedAt: '2026-09-01T00:00:00Z',
      series: [{ bucketStart: '2026-09-02T08:10:00Z', provider: 'SOLANA_RPC', requests: 1 }],
    });
    const seconds = usageBucketSeconds(long, 'ALL');
    expect(seconds).toBe(86_400);
    expect(bucketWord(seconds)).toBe('day');
    expect(usageBuckets(long, 'ALL', seconds)).toHaveLength(64);
  });

  it('colours providers from the scheme tokens in a fixed order', () => {
    const usage = report({
      series: [
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'BINANCE', requests: 1 },
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'SOLANA_RPC', requests: 1 },
      ],
    });
    const series = seriesOf(buildUsageChartOption(usage, 'LAST_HOUR', terminalLight));
    expect(series.map((s) => s.name)).toEqual(['Solana RPC', 'Binance']);
    expect(series.map((s) => s.itemStyle.color)).toEqual([
      terminalLight.series[0],
      terminalLight.series[1],
    ]);
    expect(providerColor('TELEGRAM', terminal)).toBe(terminal.series[2]);
  });

  it('draws the admin API and a provider it does not know in the neutral series step, never dropping them', () => {
    const usage = report({
      series: [
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'HELIUS_ADMIN', requests: 2 },
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'TELEGRAM', requests: 1 },
        { bucketStart: '2026-11-03T10:05:00Z', provider: 'NEW_PROVIDER' as never, requests: 4 },
      ],
    });
    const series = seriesOf(buildUsageChartOption(usage, 'LAST_HOUR', terminal));
    expect(series.map((s) => s.name)).toEqual(['Telegram', 'Helius admin API', 'New provider']);
    expect(series.map((s) => s.itemStyle.color)).toEqual([
      terminal.series[2],
      terminal.seriesNeutral,
      terminal.seriesNeutral,
    ]);
    expect(providerColor('HELIUS_ADMIN', terminalLight)).toBe(terminalLight.seriesNeutral);
    // Four providers wrap the legend to a second row on a phone: the plot starts lower, clear of the axis name.
    const top = (option: ReturnType<typeof buildUsageChartOption>) =>
      (option.grid as { top: number }).top;
    const four = report({
      series: (['SOLANA_RPC', 'BINANCE', 'TELEGRAM', 'HELIUS_ADMIN'] as const).map((provider) => ({
        bucketStart: '2026-11-03T10:05:00Z',
        provider,
        requests: 1,
      })),
    });
    expect(top(buildUsageChartOption(four, 'LAST_HOUR', terminal))).toBe(67);
    expect(top(buildUsageChartOption(usage, 'LAST_HOUR', terminal))).toBe(45);
    // The neutral step is its own colour: it never borrows a status colour or one of the three series slots.
    for (const tokens of [terminal, terminalLight]) {
      expect([
        ...tokens.series,
        tokens.gain,
        tokens.loss,
        tokens.rejected,
        tokens.accent,
      ]).not.toContain(tokens.seriesNeutral);
    }
  });
});

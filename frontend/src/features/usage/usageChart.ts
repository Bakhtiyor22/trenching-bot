import type { EChartsOption } from 'echarts';
import type { UsagePeriod, UsageProvider, UsageReport } from '../../api/setup';
import { providerLabels } from '../../lib/codes';
import { formatCount } from '../../lib/format';
import type { TerminalTokens } from '../../theme';
import { aggregateUsage } from './usageData';

const FIVE_MINUTES = 300;
const HOUR = 3600;
const DAY = 86_400;
/** Above this many hourly bars ("All recorded" over a long stretch) the chart switches to days. */
const MAX_HOURLY_BUCKETS = 24 * 31;

/** The chart's providers in a fixed order, so a provider keeps its colour across periods. */
const PROVIDER_ORDER: UsageProvider[] = ['SOLANA_RPC', 'BINANCE', 'TELEGRAM'];

/** Each provider's colour: the validated three-series slots (blue, orange, aqua) of the scheme in use. */
export function providerColor(provider: UsageProvider, tokens: TerminalTokens): string {
  return tokens.series[PROVIDER_ORDER.indexOf(provider)] ?? tokens.neutral;
}

/** Where the chart's time axis starts: the period start, or for "All recorded" when recording began. */
function rangeStart(report: UsageReport, period: UsagePeriod): number {
  const from = Date.parse(report.from);
  if (period !== 'ALL') return from;
  const firstPoint = report.series.reduce(
    (earliest, point) => Math.min(earliest, Date.parse(point.bucketStart)),
    Number.POSITIVE_INFINITY,
  );
  const recording = report.recordingStartedAt
    ? Date.parse(report.recordingStartedAt)
    : Number.POSITIVE_INFINITY;
  const start = Math.min(firstPoint, recording);
  return Number.isFinite(start) ? Math.max(from, start) : from;
}

function bucketCount(start: number, end: number, seconds: number): number {
  const step = seconds * 1000;
  return Math.floor(end / step) - Math.floor(start / step) + 1;
}

/** Bar width in seconds: five minutes for the last hour, hours otherwise, days for a long "All recorded". */
export function usageBucketSeconds(report: UsageReport, period: UsagePeriod): number {
  if (period === 'LAST_HOUR') return FIVE_MINUTES;
  const end = Date.parse(report.to);
  return bucketCount(rangeStart(report, period), end, HOUR) > MAX_HOURLY_BUCKETS ? DAY : HOUR;
}

/** "5 minutes", "hour" or "day", for "Requests sent per …". */
export function bucketWord(seconds: number): string {
  return seconds === FIVE_MINUTES ? '5 minutes' : seconds === DAY ? 'day' : 'hour';
}

/**
 * Every bucket start of the period, aligned like {@link aggregateUsage}, so a quiet bucket stays on
 * the axis instead of vanishing.
 */
export function usageBuckets(report: UsageReport, period: UsagePeriod, seconds: number): string[] {
  const step = seconds * 1000;
  const first = Math.floor(rangeStart(report, period) / step) * step;
  const last = Math.floor(Date.parse(report.to) / step) * step;
  const buckets: string[] = [];
  for (let time = first; time <= last; time += step) buckets.push(new Date(time).toISOString());
  return buckets;
}

const clock = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
const dayMonth = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' });

/** Axis label of a bucket in local time: the clock within a day, the date beside it over longer periods. */
export function bucketLabel(iso: string, period: UsagePeriod, seconds: number): string {
  const date = new Date(iso);
  if (seconds === DAY) return dayMonth.format(date);
  return period === 'LAST_HOUR' || period === 'LAST_24_HOURS'
    ? clock.format(date)
    : `${dayMonth.format(date)} ${clock.format(date)}`;
}

/** A tooltip value: a count, or "not recorded" for a bucket before recording started. */
function requestsText(value: unknown): string {
  return typeof value === 'number' ? `${formatCount(value)} requests` : 'not recorded';
}

/**
 * The stacked "requests per bucket" bar chart. Every bucket of the period is on the axis: a quiet
 * bucket is 0 (no bar, a visible gap) and a bucket wholly before recording started is null.
 */
export function buildUsageChartOption(
  report: UsageReport,
  period: UsagePeriod,
  tokens: TerminalTokens,
): EChartsOption {
  const seconds = usageBucketSeconds(report, period);
  const buckets = usageBuckets(report, period, seconds);
  const points = aggregateUsage(report.series, seconds);
  const counts = new Map(points.map((point) => [`${point.bucketStart}/${point.provider}`, point.requests]));
  const providers = PROVIDER_ORDER.filter((provider) => points.some((point) => point.provider === provider));
  const recordingStart = report.recordingStartedAt ? Date.parse(report.recordingStartedAt) : null;
  const beforeRecording = (bucket: string) =>
    recordingStart != null && Date.parse(bucket) + seconds * 1000 <= recordingStart;
  return {
    // A polled chart: motion would only delay reading, and a re-render must not replay it.
    animation: false,
    tooltip: { trigger: 'axis', valueFormatter: requestsText },
    legend: { top: 0 },
    grid: { left: 50, right: 20, top: 45, bottom: 40 },
    xAxis: {
      type: 'category',
      data: buckets.map((bucket) => bucketLabel(bucket, period, seconds)),
    },
    yAxis: { type: 'value', minInterval: 1, name: 'Requests' },
    series: providers.map((provider) => ({
      name: providerLabels[provider],
      type: 'bar',
      stack: 'requests',
      itemStyle: { color: providerColor(provider, tokens) },
      data: buckets.map((bucket) =>
        beforeRecording(bucket) ? null : (counts.get(`${bucket}/${provider}`) ?? 0),
      ),
    })),
  };
}

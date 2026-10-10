import type { EChartsOption } from 'echarts';
import type { RecentRun } from '../../api/types';
import { INSIDE_ZOOM } from '../../charts/priceChart';
import { addDecimals, decimalSign, formatDecimal, toChartNumber } from '../../lib/decimal';
import { formatLocalTime, mintLabel, shortId } from '../../lib/format';
import { terminal } from '../../theme';
import type { GaugeSample } from './gaugeHistory';

/** A step of the cumulative P&L line; keeps the exact strings for the tooltip. */
interface CumulativeDatum {
  value: [number, number | null];
  runId: string;
  quoteMint: string;
  createdAt: string;
  step: string;
  total: string;
}

/** Below this many runs the zoom slider adds nothing; wheel and pinch zoom stay on. */
const SLIDER_MIN_POINTS = 10;

/**
 * Running total of realized P&L over the given runs, oldest → newest, one step line per quote mint
 * so different mints are never added together. Totals are summed exactly; only the plotted
 * coordinate is a float.
 */
export function cumulativePnlSteps(recent: RecentRun[]): Map<string, CumulativeDatum[]> {
  const ordered = [...recent].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const byMint = new Map<string, CumulativeDatum[]>();
  for (const run of ordered) {
    const steps = byMint.get(run.quoteMint) ?? [];
    const previous = steps.at(-1)?.total ?? '0';
    const total = addDecimals(previous, run.realizedPnl);
    steps.push({
      value: [Date.parse(run.createdAt), toChartNumber(total)],
      runId: run.id,
      quoteMint: run.quoteMint,
      createdAt: run.createdAt,
      step: run.realizedPnl,
      total,
    });
    byMint.set(run.quoteMint, steps);
  }
  return byMint;
}

function signColor(value: string): string {
  const sign = decimalSign(value);
  return sign > 0 ? terminal.gain : sign < 0 ? terminal.loss : terminal.dimmed;
}

/** Anchors that make a line start from zero before its first run and hold its total until now. */
interface AnchorDatum {
  value: [number, number | null];
  symbol: 'none';
}

/** How far before the first run the zero anchor sits, as a share of the plotted time span. */
const ORIGIN_LEAD = 0.04;

function anchored(steps: CumulativeDatum[], start: number, now: number): Array<CumulativeDatum | AnchorDatum> {
  const last = steps.at(-1);
  if (!last) return steps;
  return [
    { value: [start, 0], symbol: 'none' },
    ...steps,
    ...(now > last.value[0] ? [{ value: [now, last.value[1]] as [number, number | null], symbol: 'none' as const }] : []),
  ];
}

export function cumulativePnlOption(recent: RecentRun[], now: number = Date.now()): EChartsOption {
  const byMint = cumulativePnlSteps(recent);
  const times = recent.map((r) => Date.parse(r.createdAt));
  const first = Math.min(...times);
  const end = Math.max(now, ...times);
  const start = first - Math.max((end - first) * ORIGIN_LEAD, 60_000);
  const mints = [...byMint.keys()];
  const points = recent.length;
  return {
    grid: { left: 8, right: 16, top: mints.length > 1 ? 36 : 12, bottom: points >= SLIDER_MIN_POINTS ? 44 : 8, containLabel: true },
    legend: mints.length > 1 ? { top: 0, data: mints.map(mintLabel) } : undefined,
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const d = (params as { data?: Partial<CumulativeDatum> }).data;
        if (!d?.runId || !d.quoteMint || !d.createdAt || !d.step || !d.total) return '';
        const unit = mintLabel(d.quoteMint);
        return [
          `<span style="color:${terminal.dimmed}">${formatLocalTime(d.createdAt)}</span>`,
          `Run <b>${shortId(d.runId)}</b>`,
          `Step <span style="color:${signColor(d.step)}">${formatDecimal(d.step)}</span> ${unit}`,
          `Total <b style="color:${signColor(d.total)}">${formatDecimal(d.total)}</b> ${unit}`,
          `<i style="color:${terminal.dimmed}">Click to open the run</i>`,
        ].join('<br/>');
      },
    },
    xAxis: { type: 'time', splitLine: { show: false } },
    yAxis: { type: 'value', scale: false, name: mints.length === 1 ? mintLabel(mints[0]!) : undefined },
    dataZoom: [
      INSIDE_ZOOM,
      ...(points >= SLIDER_MIN_POINTS ? [{ type: 'slider' as const, filterMode: 'none' as const, height: 18, bottom: 8 }] : []),
    ],
    series: mints.map((mint, index) => ({
      type: 'line' as const,
      name: mintLabel(mint),
      step: 'end' as const,
      cursor: 'pointer',
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { width: 2, color: index === 0 ? terminal.gain : undefined },
      itemStyle: index === 0 ? { color: terminal.gain } : undefined,
      areaStyle: index === 0 ? { color: terminal.gain, opacity: 0.08 } : undefined,
      markLine: { silent: true, symbol: 'none', data: [{ yAxis: 0 }], lineStyle: { color: '#3A4651', type: 'dashed' as const }, label: { show: false } },
      data: anchored(byMint.get(mint) ?? [], start, end),
    })),
  };
}

/** Run id behind a clicked step. */
export function runIdFromClick(params: unknown): string | null {
  const datum = (params as { data?: { runId?: string } | null }).data;
  return datum?.runId ?? null;
}

export function sparklineOption(
  samples: GaugeSample[],
  pick: (s: GaugeSample) => number | null,
  { color, unit, digits = 1 }: { color: string; unit: string; digits?: number },
): EChartsOption {
  return {
    grid: { left: 4, right: 4, top: 6, bottom: 4, containLabel: false },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => (typeof value === 'number' ? `${value.toFixed(digits)} ${unit}` : '—'),
    },
    xAxis: { type: 'time', show: false },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        type: 'line',
        showSymbol: false,
        smooth: true,
        connectNulls: false,
        lineStyle: { width: 1.5, color },
        areaStyle: { color, opacity: 0.1 },
        data: samples.map((s) => [s.t, pick(s)]),
      },
    ],
  };
}

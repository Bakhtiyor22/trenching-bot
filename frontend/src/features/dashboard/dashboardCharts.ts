import type { EChartsOption } from 'echarts';
import type { RecentRun } from '../../api/types';
import { INSIDE_ZOOM } from '../../charts/priceChart';
import { addDecimals, decimalSign, formatSignedDecimal, toChartNumber } from '../../lib/decimal';
import { formatLocalTime, mintLabel, shortId } from '../../lib/format';
import { terminal, type TerminalTokens } from '../../theme';
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

function signColor(value: string, t: TerminalTokens): string {
  const sign = decimalSign(value);
  return sign > 0 ? t.gain : sign < 0 ? t.loss : t.dimmed;
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

/**
 * The cumulative P&L step chart. A line takes the colour of the sign of its latest total (loss red,
 * gain teal); further quote mints use the identity slots. The zero line is the one emphasised gridline
 * and each line ends with its exact signed total.
 */
export function cumulativePnlOption(
  recent: RecentRun[],
  now: number = Date.now(),
  t: TerminalTokens = terminal,
): EChartsOption {
  const byMint = cumulativePnlSteps(recent);
  const times = recent.map((r) => Date.parse(r.createdAt));
  const first = Math.min(...times);
  const end = Math.max(now, ...times);
  const start = first - Math.max((end - first) * ORIGIN_LEAD, 60_000);
  const mints = [...byMint.keys()];
  const points = recent.length;
  return {
    grid: { left: 8, right: 120, top: mints.length > 1 ? 36 : 16, bottom: points >= SLIDER_MIN_POINTS ? 44 : 8, containLabel: true },
    legend: mints.length > 1 ? { top: 0, data: mints.map(mintLabel) } : undefined,
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const d = (params as { data?: Partial<CumulativeDatum> }).data;
        if (!d?.runId || !d.quoteMint || !d.createdAt || !d.step || !d.total) return '';
        const unit = mintLabel(d.quoteMint);
        return [
          `<span style="color:${t.dimmed}">${formatLocalTime(d.createdAt)}</span>`,
          `Run <b>${shortId(d.runId)}</b>`,
          `Step <span style="color:${signColor(d.step, t)}">${formatSignedDecimal(d.step)}</span> ${unit}`,
          `Total <b style="color:${signColor(d.total, t)}">${formatSignedDecimal(d.total)}</b> ${unit}`,
          `<i style="color:${t.dimmed}">Click to open the run</i>`,
        ].join('<br/>');
      },
    },
    xAxis: { type: 'time', splitLine: { show: false } },
    yAxis: { type: 'value', scale: false, name: mints.length === 1 ? mintLabel(mints[0]!) : undefined },
    dataZoom: [
      INSIDE_ZOOM,
      ...(points >= SLIDER_MIN_POINTS ? [{ type: 'slider' as const, filterMode: 'none' as const, height: 18, bottom: 8 }] : []),
    ],
    series: mints.map((mint, index) => {
      const steps = byMint.get(mint) ?? [];
      const total = steps.at(-1)?.total ?? '0';
      const color = index === 0 ? signColor(total, t) : t.series[(index - 1) % t.series.length]!;
      return {
        type: 'line' as const,
        name: mintLabel(mint),
        step: 'end' as const,
        cursor: 'pointer',
        symbol: 'circle',
        symbolSize: 8,
        animation: false,
        lineStyle: { width: 2, color },
        itemStyle: { color, borderColor: t.panel, borderWidth: 2 },
        areaStyle: index === 0 ? { color, opacity: 0.08, origin: 0 } : undefined,
        endLabel: {
          show: true,
          formatter: () => `${formatSignedDecimal(total, { trim: true })} ${mintLabel(mint)}`,
          color: t.text,
          fontFamily: 'JetBrains Mono, monospace',
          fontSize: 11,
          distance: 6,
        },
        markLine: {
          silent: true,
          symbol: 'none',
          data: [{ yAxis: 0 }],
          lineStyle: { color: t.lineStrong, type: 'solid' as const, width: 1 },
          label: { show: false },
        },
        data: anchored(steps, start, end),
      };
    }),
  };
}

/** Run id behind a clicked step. */
export function runIdFromClick(params: unknown): string | null {
  const datum = (params as { data?: { runId?: string } | null }).data;
  return datum?.runId ?? null;
}

/** A runtime sparkline: a quiet grey line, no axes, the latest point in the accent; the value itself is shown as text. */
export function sparklineOption(
  samples: GaugeSample[],
  pick: (s: GaugeSample) => number | null,
  { color, unit, digits = 1, latest }: { color: string; unit: string; digits?: number; latest?: string },
): EChartsOption {
  const points = samples.map((s) => [s.t, pick(s)] as [number, number | null]);
  const last = [...points].reverse().find((p) => p[1] != null);
  return {
    grid: { left: 4, right: 6, top: 6, bottom: 4, containLabel: false },
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
        smooth: false,
        connectNulls: false,
        animation: false,
        lineStyle: { width: 1.5, color },
        data: points,
      },
      ...(latest && last
        ? [
            {
              type: 'scatter' as const,
              silent: true,
              animation: false,
              symbolSize: 7,
              itemStyle: { color: latest },
              tooltip: { show: false },
              data: [last],
            },
          ]
        : []),
    ],
  };
}

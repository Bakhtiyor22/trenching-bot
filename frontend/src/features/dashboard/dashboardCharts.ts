import type { EChartsOption } from 'echarts';
import type { RecentRun, RejectionCount } from '../../api/types';
import { decimalSign, formatDecimal, toChartNumber } from '../../lib/decimal';
import { formatLocalTime, humanizeCode, mintLabel, shortId } from '../../lib/format';
import type { GaugeSample } from './gaugeHistory';

export const POSITIVE_COLOR = '#12b886';
export const NEGATIVE_COLOR = '#fa5252';
export const NEUTRAL_COLOR = '#868e96';

export function rejectionsOption(rejections: RejectionCount[]): EChartsOption {
  return {
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rejections.map((r) => humanizeCode(r.reason)),
    },
    series: [
      {
        type: 'bar',
        name: 'Rejections',
        data: rejections.map((r) => r.count),
        itemStyle: { color: '#fd7e14', borderRadius: [0, 4, 4, 0] },
        barMaxWidth: 22,
        label: { show: true, position: 'right' },
      },
    ],
  };
}

interface PnlDatum {
  value: number | null;
  exact: string;
  runId: string;
  quoteMint: string;
  createdAt: string;
  itemStyle: { color: string };
}

/**
 * Realized P&L of recent runs, oldest → newest. One series per quote mint so P&L in different
 * mints is never presented as one quantity; bars stack so each run still has one bar.
 */
export function recentPnlOption(recent: RecentRun[]): EChartsOption {
  const runs = [...recent].reverse();
  const mints = [...new Set(runs.map((r) => r.quoteMint))];
  return {
    grid: { left: 8, right: 16, top: mints.length > 1 ? 36 : 16, bottom: 8, containLabel: true },
    legend: mints.length > 1 ? { top: 0, data: mints.map(mintLabel) } : undefined,
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const datum = (params as { data?: PnlDatum }).data;
        if (!datum) return '';
        return [
          `Run ${shortId(datum.runId)}`,
          formatLocalTime(datum.createdAt),
          `Realized P&L: <b>${formatDecimal(datum.exact)}</b> ${mintLabel(datum.quoteMint)}`,
          '<i>Click to open the run</i>',
        ].join('<br/>');
      },
    },
    xAxis: { type: 'category', data: runs.map((r) => shortId(r.id)), axisLabel: { rotate: 45 } },
    yAxis: { type: 'value', scale: false, name: mints.length === 1 ? mintLabel(mints[0]!) : undefined },
    series: mints.map((mint) => ({
      type: 'bar' as const,
      name: mintLabel(mint),
      stack: 'pnl',
      barMaxWidth: 28,
      cursor: 'pointer',
      data: runs.map((r): PnlDatum | null => {
        if (r.quoteMint !== mint) return null;
        const sign = decimalSign(r.realizedPnl);
        return {
          value: toChartNumber(r.realizedPnl),
          exact: r.realizedPnl,
          runId: r.id,
          quoteMint: r.quoteMint,
          createdAt: r.createdAt,
          itemStyle: { color: sign > 0 ? POSITIVE_COLOR : sign < 0 ? NEGATIVE_COLOR : NEUTRAL_COLOR },
        };
      }),
    })),
  };
}

/** Run id behind a clicked P&L bar. */
export function runIdFromClick(params: unknown): string | null {
  const datum = (params as { data?: PnlDatum | null }).data;
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
      valueFormatter: (value) =>
        typeof value === 'number' ? `${value.toFixed(digits)} ${unit}` : '—',
    },
    xAxis: { type: 'time', show: false },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        type: 'line',
        showSymbol: false,
        smooth: true,
        connectNulls: false,
        lineStyle: { width: 2, color },
        areaStyle: { color, opacity: 0.12 },
        data: samples.map((s) => [s.t, pick(s)]),
      },
    ],
  };
}

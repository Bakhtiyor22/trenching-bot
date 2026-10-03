import type { EChartsOption, SeriesOption } from 'echarts';
import { pairKey } from '../../api/endpoints';
import type { Observation, Scenario } from '../../api/types';
import { itemTooltip, observationSeries, slotAxes } from '../../charts/priceChart';
import { formatDecimal, toChartNumber } from '../../lib/decimal';
import { formatSlot, humanizeCode, offsetLabel } from '../../lib/format';

export const ENTRY_COLOR = '#12b886';
export const EXIT_COLOR = '#f76707';
export const REJECTED_COLOR = '#fa5252';
export const SOURCE_COLOR = '#868e96';

export interface PriceMarker {
  slot: number;
  /** Exact price string (shown in tooltips); plotted via `toChartNumber`. */
  price: string;
  label: string;
  tooltip: string;
}

export interface SlotMarker {
  slot: number;
  label: string;
}

export interface TimelineMarkers {
  sourceBuys: SlotMarker[];
  entries: PriceMarker[];
  exits: PriceMarker[];
  rejections: SlotMarker[];
}

/** Markers for the scenarios of one pair: source buys, entry fills, exits and rejected targets. */
export function timelineMarkers(scenarios: Scenario[]): TimelineMarkers {
  const sourceSlots = [...new Set(scenarios.map((s) => s.sourceSlot))].sort((a, b) => a - b);
  const entries: PriceMarker[] = [];
  const exits: PriceMarker[] = [];
  const rejections: SlotMarker[] = [];
  for (const s of scenarios) {
    const offset = offsetLabel(s.entryOffset);
    if (s.status === 'REJECTED' || !s.fill) {
      rejections.push({
        slot: s.targetSlot,
        label: `${offset} rejected: ${humanizeCode(s.rejectionReason)}`,
      });
      continue;
    }
    entries.push({
      slot: s.fill.actualSlot,
      price: s.fill.executionPrice,
      label: offset,
      tooltip: [
        `<b>Entry ${offset}</b> (source slot ${formatSlot(s.sourceSlot)})`,
        `Target slot ${formatSlot(s.targetSlot)}, filled at slot ${formatSlot(s.fill.actualSlot)}`,
        `Execution price <b>${formatDecimal(s.fill.executionPrice)}</b>`,
        `Quantity ${formatDecimal(s.fill.quantity)} · cost ${formatDecimal(s.fill.costBasis)}`,
      ].join('<br/>'),
    });
    const close = s.position?.close;
    if (close) {
      exits.push({
        slot: close.slot,
        price: close.executionPrice,
        label: `exit ${offset}`,
        tooltip: [
          `<b>Exit ${offset}</b> at slot ${formatSlot(close.slot)} (${humanizeCode(close.reason)})`,
          `Execution price <b>${formatDecimal(close.executionPrice)}</b>`,
          `Net proceeds ${formatDecimal(close.netProceeds)}`,
          `Realized P&L <b>${formatDecimal(close.realizedPnl)}</b>`,
        ].join('<br/>'),
      });
    }
  }
  return {
    sourceBuys: sourceSlots.map((slot) => ({ slot, label: 'Source buy' })),
    entries,
    exits,
    rejections,
  };
}

function markerSeries(name: string, markers: PriceMarker[], color: string, rotate = 0): SeriesOption {
  return {
    type: 'scatter',
    name,
    symbol: 'triangle',
    symbolRotate: rotate,
    symbolSize: 14,
    itemStyle: { color, borderColor: '#fff', borderWidth: 1 },
    z: 5,
    label: {
      show: true,
      position: rotate ? 'bottom' : 'top',
      formatter: (params: unknown) => (params as { data?: { marker?: string } }).data?.marker ?? '',
      fontWeight: 'bold',
      color,
    },
    data: markers.map((m) => ({ value: [m.slot, toChartNumber(m.price)], marker: m.label, tip: m.tooltip })),
  };
}

/**
 * Price vs slot for one pair's observations, with the scenario markers. The x-axis is padded to
 * include every marker, because ECharts does not widen the axis for mark lines.
 */
export function timelineOption(observations: Observation[], scenarios: Scenario[]): EChartsOption {
  const markers = timelineMarkers(scenarios);
  const slots = [
    ...observations.map((o) => o.slot),
    ...markers.sourceBuys.map((m) => m.slot),
    ...markers.entries.map((m) => m.slot),
    ...markers.exits.map((m) => m.slot),
    ...markers.rejections.map((m) => m.slot),
  ];
  const min = slots.length ? Math.min(...slots) : 0;
  const max = slots.length ? Math.max(...slots) : 1;
  const pad = Math.max(1, Math.round((max - min) * 0.04));
  const axes = slotAxes();

  const verticalLines: SeriesOption = {
    type: 'line',
    name: 'Rejected target',
    data: [],
    itemStyle: { color: REJECTED_COLOR },
    markLine: {
      symbol: 'none',
      silent: false,
      data: [
        ...markers.sourceBuys.map((m) => ({
          xAxis: m.slot,
          name: m.label,
          lineStyle: { color: SOURCE_COLOR, type: 'solid' as const, width: 2 },
          label: { formatter: m.label, color: SOURCE_COLOR, position: 'insideEndTop' as const },
        })),
        ...markers.rejections.map((m) => ({
          xAxis: m.slot,
          name: m.label,
          lineStyle: { color: REJECTED_COLOR, type: 'dashed' as const, width: 1.5 },
          label: { formatter: m.label, color: REJECTED_COLOR, position: 'insideEndBottom' as const },
        })),
      ],
    },
  };

  return {
    grid: { left: 12, right: 32, top: 48, bottom: 64, containLabel: true },
    legend: { top: 0 },
    tooltip: { trigger: 'item', formatter: itemTooltip },
    ...axes,
    xAxis: { ...(axes.xAxis as object), min: min - pad, max: max + pad },
    series: [
      ...observationSeries(observations),
      markerSeries('Entry fills', markers.entries, ENTRY_COLOR),
      markerSeries('Exits', markers.exits, EXIT_COLOR, 180),
      verticalLines,
    ],
  } as EChartsOption;
}

/** The distinct pairs traded in a run, in scenario order. */
export function scenarioPairs(scenarios: Scenario[]): string[] {
  return [...new Set(scenarios.map((s) => pairKey(s.pair)))];
}

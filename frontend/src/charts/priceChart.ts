import type { EChartsOption, SeriesOption } from 'echarts';
import type { Observation } from '../api/types';
import { formatDecimal, toChartNumber } from '../lib/decimal';
import { formatSlot, humanizeCode } from '../lib/format';

export const QUALITY_STYLE: Record<string, { color: string; symbol: string }> = {
  LAST_TRADE: { color: '#228be6', symbol: 'circle' },
  MIDPOINT: { color: '#7950f2', symbol: 'diamond' },
  INVALID: { color: '#fa5252', symbol: 'triangle' },
};

/** A chart point that keeps the exact strings for tooltips. */
export interface ObservationPoint {
  value: [number, number | null];
  observation: Observation;
}

function point(observation: Observation): ObservationPoint {
  return { value: [observation.slot, toChartNumber(observation.price)], observation };
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    const entities: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;',
    };
    return entities[character] ?? character;
  });
}

export function observationTooltip(o: Observation): string {
  return [
    `<b>Observation</b> ${escapeHtml(o.id)}`,
    `Slot ${formatSlot(o.slot)} (price slot ${formatSlot(o.priceSlot)})`,
    `Price <b>${formatDecimal(o.price)}</b>`,
    `Liquidity ${o.liquidityQuote == null ? 'unknown' : formatDecimal(o.liquidityQuote)}`,
    `Quality ${escapeHtml(humanizeCode(o.quality))} · ${escapeHtml(o.source)}`,
  ].join('<br/>');
}

/**
 * Price-vs-slot series: a line through valid observations plus one scatter series per quality, so
 * the legend shows quality and INVALID points stand out (they are not part of the line).
 */
export function observationSeries(observations: Observation[]): SeriesOption[] {
  const sorted = [...observations].sort((a, b) => a.slot - b.slot || a.id.localeCompare(b.id));
  const qualities = [...new Set(sorted.map((o) => o.quality))];
  return [
    {
      type: 'line',
      name: 'Price',
      data: sorted.map((observation): ObservationPoint => ({
        value: [
          observation.slot,
          observation.quality === 'INVALID' ? null : toChartNumber(observation.price),
        ],
        observation,
      })),
      connectNulls: false,
      showSymbol: false,
      lineStyle: { width: 1.5, color: '#74c0fc' },
      emphasis: { disabled: true },
      tooltip: { show: false },
      z: 1,
    },
    ...qualities.map((quality): SeriesOption => ({
      type: 'scatter',
      name: humanizeCode(quality),
      data: sorted.filter((o) => o.quality === quality).map(point),
      symbol: QUALITY_STYLE[quality]?.symbol ?? 'circle',
      symbolSize: 7,
      itemStyle: { color: QUALITY_STYLE[quality]?.color ?? '#868e96' },
      z: 2,
    })),
  ];
}

export function slotAxes(): Pick<EChartsOption, 'xAxis' | 'yAxis' | 'dataZoom'> {
  return {
    xAxis: {
      type: 'value',
      name: 'Slot',
      nameLocation: 'middle',
      nameGap: 28,
      scale: true,
      minInterval: 1,
      axisLabel: { formatter: (v: number) => formatSlot(v) },
    },
    yAxis: { type: 'value', name: 'Price', scale: true },
    dataZoom: [
      { type: 'inside', xAxisIndex: 0, filterMode: 'none' },
      { type: 'slider', xAxisIndex: 0, filterMode: 'none', height: 18, bottom: 6 },
    ],
  };
}

/** Tooltip formatter for item tooltips whose data carries an `observation` or a `tip` string. */
export function itemTooltip(params: unknown): string {
  const data = (params as { data?: { observation?: Observation; tip?: string } }).data;
  if (data?.tip) return data.tip;
  if (data?.observation) return observationTooltip(data.observation);
  return '';
}

export function priceChartOption(observations: Observation[]): EChartsOption {
  return {
    grid: { left: 12, right: 24, top: 40, bottom: 64, containLabel: true },
    legend: { top: 0 },
    tooltip: { trigger: 'item', formatter: itemTooltip },
    ...slotAxes(),
    series: observationSeries(observations),
  };
}

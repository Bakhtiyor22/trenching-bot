import type { EChartsOption, SeriesOption } from 'echarts';
import type { Observation } from '../api/types';
import { compactDecimal, formatDecimal, toChartNumber } from '../lib/decimal';
import { formatSlot, humanizeCode } from '../lib/format';
import { terminal } from '../theme';

export const QUALITY_STYLE: Record<string, { color: string; symbol: string }> = {
  LAST_TRADE: { color: terminal.accent, symbol: 'circle' },
  MIDPOINT: { color: terminal.violet, symbol: 'diamond' },
  INVALID: { color: terminal.loss, symbol: 'triangle' },
};

/** Significant digits shown on a price axis label. */
const AXIS_PRICE_DIGITS = 6;

/** A price axis label without exponent notation, tiny values in the compact subscript form. */
export function priceAxisLabel(value: number): string {
  const plain = value.toLocaleString('en-US', { maximumSignificantDigits: AXIS_PRICE_DIGITS, useGrouping: false });
  return compactDecimal(plain);
}

/** A chart point that keeps the exact strings for tooltips. */
export interface ObservationPoint {
  value: [number, number | null];
  observation: Observation;
  /** An extra tooltip line, e.g. the rate a converted price used. */
  note?: string;
}

/** An optional extra tooltip line per observation. */
export type ObservationNote = (observation: Observation) => string | null;

function point(observation: Observation, note?: ObservationNote): ObservationPoint {
  const text = note?.(observation);
  return { value: [observation.slot, toChartNumber(observation.price)], observation, ...(text ? { note: text } : {}) };
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

export function observationTooltip(o: Observation, note?: string): string {
  return [
    `<b>Observation</b> ${escapeHtml(o.id)}`,
    `Slot ${formatSlot(o.slot)} (price slot ${formatSlot(o.priceSlot)})`,
    `Price <b>${formatDecimal(o.price)}</b>`,
    `Liquidity ${o.liquidityQuote == null ? 'unknown' : formatDecimal(o.liquidityQuote)}`,
    `Quality ${escapeHtml(humanizeCode(o.quality))} · ${escapeHtml(o.source)}`,
    ...(note ? [escapeHtml(note)] : []),
  ].join('<br/>');
}

/**
 * Price-vs-slot series: a line through valid observations plus one scatter series per quality, so
 * the legend shows quality and INVALID points stand out (they are not part of the line).
 */
export function observationSeries(observations: Observation[], note?: ObservationNote): SeriesOption[] {
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
      lineStyle: { width: 1.5, color: terminal.accent, opacity: 0.85 },
      emphasis: { disabled: true },
      tooltip: { show: false },
      z: 1,
    },
    ...qualities.map((quality): SeriesOption => ({
      type: 'scatter',
      name: humanizeCode(quality),
      data: sorted.filter((o) => o.quality === quality).map((o) => point(o, note)),
      symbol: QUALITY_STYLE[quality]?.symbol ?? 'circle',
      symbolSize: 7,
      itemStyle: { color: QUALITY_STYLE[quality]?.color ?? '#868e96' },
      z: 2,
    })),
  ];
}

/**
 * Zoom inside a chart: Ctrl + wheel zooms and dragging pans, so a plain wheel still scrolls the page
 * past the chart.
 */
export const INSIDE_ZOOM = { type: 'inside', filterMode: 'none', zoomOnMouseWheel: 'ctrl', moveOnMouseWheel: false } as const;

/** [priceLabel] names the price axis, e.g. "Price (USDT)". */
export function slotAxes(priceLabel = 'Price'): Pick<EChartsOption, 'xAxis' | 'yAxis' | 'dataZoom'> {
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
    yAxis: { type: 'value', name: priceLabel, scale: true, axisLabel: { formatter: priceAxisLabel } },
    dataZoom: [
      { ...INSIDE_ZOOM, xAxisIndex: 0 },
      { type: 'slider', xAxisIndex: 0, filterMode: 'none', height: 18, bottom: 6 },
    ],
  };
}

/** Tooltip formatter for item tooltips whose data carries an `observation` or a `tip` string. */
export function itemTooltip(params: unknown): string {
  const data = (params as { data?: { observation?: Observation; tip?: string; note?: string } }).data;
  if (data?.tip) return data.tip;
  if (data?.observation) return observationTooltip(data.observation, data.note);
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

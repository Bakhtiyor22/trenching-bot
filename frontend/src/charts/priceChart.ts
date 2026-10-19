import type { EChartsOption, SeriesOption } from 'echarts';
import type { ChartResponse, Observation } from '../api/types';
import { compactDecimal, formatDecimal, toChartNumber } from '../lib/decimal';
import { formatSlot, humanizeCode } from '../lib/format';
import { terminal } from '../theme';
import { secondsFrom } from './marketSummary';

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

export type ChartView = 'mcap' | 'price';
const CAPTURED_TRADERS = 'Captured traders';
const OTHER_TRADER_COLOR = '#5C6B78';
export type MarketChartStyle = 'line' | 'candles';

export interface MarketPoint {
  time: number;
  value: number | null;
  exact: string | null;
}

export function mcapAxisLabel(value: number): string {
  const magnitude = Math.abs(value);
  const [divisor, suffix] = magnitude >= 1e9 ? [1e9, 'b'] : magnitude >= 1e6 ? [1e6, 'm'] : magnitude >= 1e3 ? [1e3, 'k'] : [1, ''];
  return `$${(value / divisor).toLocaleString('en-US', { maximumFractionDigits: 1, useGrouping: false })}${suffix}`;
}

/**
 * One point per bucket with trades, at its close, keeping the exact string. A bucket without trades adds no point: the last trade's
 * price holds until the next trade, so the line steps across it. Only a close without a value (a minute with no rate) breaks it.
 */
export function chartMarketPoints(response: ChartResponse, view: ChartView): MarketPoint[] {
  return [...response.candles]
    .sort((a, b) => Date.parse(a.bucketStart) - Date.parse(b.bucketStart))
    .map((candle) => {
      const exact = candle[view]?.close ?? null;
      return { time: Date.parse(candle.bucketStart), value: toChartNumber(exact), exact };
    });
}

/** The point in force at [time]: the last one at or before it, else the first. */
export function marketPointAt(points: MarketPoint[], time: number): MarketPoint | null {
  let held = points[0] ?? null;
  for (const point of points) if (point.time <= time) held = point;
  return held;
}

/** Tick spacings in seconds; the axis takes the smallest that keeps it to [MAX_TIME_TICKS] labels. */
const SECOND_STEPS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1_800, 3_600, 7_200, 14_400, 43_200, 86_400];
const MAX_TIME_TICKS = 6;

/** Ticks between [min] and [max] at whole multiples of a round number of seconds from [origin], so one of them is `0s`. */
export function secondTicks(min: number, max: number, origin: number): number[] {
  const span = (max - min) / 1000;
  const step = (SECOND_STEPS.find((seconds) => span / seconds <= MAX_TIME_TICKS) ?? SECOND_STEPS.at(-1)!) * 1000;
  const ticks: number[] = [];
  for (let k = Math.ceil((min - origin) / step); origin + k * step <= max; k++) ticks.push(origin + k * step);
  return ticks;
}

/** Interactive Decu and paper markers are real buttons in the HTML overlay. */
export function chartMarkerSeries(response: ChartResponse, view: ChartView): SeriesOption[] {
  const series: SeriesOption[] = [{
    type: 'scatter', name: 'Other traders', symbol: 'circle', symbolSize: 6,
    silent: true, tooltip: { show: false }, emphasis: { disabled: true }, z: 3,
    data: response.markers.items.filter((marker) => !marker.isTrackedWallet).flatMap((marker) => {
      const coordinate = toChartNumber(marker[view]);
      return coordinate == null ? [] : [{
        value: [Date.parse(marker.time), coordinate],
        itemStyle: { color: marker.side === 'BUY' ? OTHER_TRADER_COLOR : terminal.ground, borderColor: OTHER_TRADER_COLOR, borderWidth: 1, opacity: 1 },
      }];
    }),
  }];
  // Cluster payloads contain time bounds and counts, but no price coordinate.
  if (response.markers.clusters.length) series.push({
    type: 'scatter', name: CAPTURED_TRADERS, xAxisIndex: 1, yAxisIndex: 1,
    symbol: 'circle', symbolSize: 25,
    itemStyle: { color: terminal.raised, borderColor: OTHER_TRADER_COLOR, borderWidth: 1 },
    label: { show: true, position: 'inside', color: terminal.text, formatter: (params: unknown) => String((params as { data?: { cluster?: { count: number } } }).data?.cluster?.count ?? '') },
    tooltip: { show: false },
    data: response.markers.clusters.map((cluster) => ({ value: [Date.parse(cluster.firstTime), 0.5], cluster })),
  });
  return series;
}

export function marketAxisDomain(values: number[]): { min: number; max: number } {
  if (!values.length) return { min: 0, max: 1 };
  const min = Math.min(...values);
  const max = Math.max(...values);
  const padding = Math.max(Math.abs(min) * 0.1, Math.abs(max) * 0.1, (max - min) * 0.1, Number.MIN_VALUE);
  return { min: min - padding, max: max + padding };
}

/** [origin] is the time the axis counts seconds from, the cycle's first buy; the range start when omitted. */
export function chartCandleOption(response: ChartResponse, requestedView: ChartView, style: MarketChartStyle = 'line', origin?: number | null): EChartsOption {
  const view = requestedView === 'mcap' && !response.valuationBasis.available ? 'price' : requestedView;
  const points = chartMarketPoints(response, view);
  const valid = points.filter((point): point is MarketPoint & { value: number } => point.value != null);
  const sparse = valid.length < 3;
  const color = (valid.at(-1)?.value ?? 0) >= (valid[0]?.value ?? 0) ? terminal.gain : terminal.loss;
  const rail = response.markers.clusters.length > 0;
  const min = Math.min(Date.parse(response.range.from), ...points.map((point) => point.time));
  const max = Math.max(Date.parse(response.range.to), min + response.bucketSeconds * 1000);
  const zero = origin ?? min;
  const xAxis = {
    type: 'time' as const, min, max,
    axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false },
    axisLabel: { color: terminal.dimmed, customValues: secondTicks(min, max, zero), formatter: (value: number) => secondsFrom(value, zero) },
    axisPointer: { show: true, snap: false, lineStyle: { type: 'dashed' as const, color: terminal.dimmed }, label: { show: false } },
  };
  const yValues = [
    ...valid.map((point) => point.value),
    ...response.markers.items.flatMap((marker) => { const value = toChartNumber(marker[view]); return value == null ? [] : [value]; }),
    ...response.paperFills.flatMap((fill) => { const value = toChartNumber(fill[view]); return value == null ? [] : [value]; }),
    ...(style === 'candles' && !sparse ? response.candles.flatMap((candle) => [toChartNumber(candle[view]?.low), toChartNumber(candle[view]?.high)].filter((value): value is number => value != null)) : []),
  ];
  const domain = marketAxisDomain(yValues);
  const label = view === 'mcap' ? mcapAxisLabel : priceAxisLabel;
  const yAxis = {
    type: 'value' as const, position: 'right' as const, name: view === 'mcap' ? 'Mcap (USDT)' : 'Price (SOL)', nameTextStyle: { color: terminal.dimmed },
    scale: true, ...domain, interval: (domain.max - domain.min) / 3, splitNumber: 3,
    axisLabel: { formatter: label, color: terminal.dimmed, margin: 12 },
    axisLine: { show: false }, axisTick: { show: false },
    splitLine: { show: true, lineStyle: { type: 'dashed' as const, color: terminal.line } },
  };
  let market: SeriesOption;
  const sparseLevels: SeriesOption[] = [];
  if (sparse) {
    market = {
      type: 'scatter', name: 'Market', symbol: 'circle', symbolSize: 6, silent: true,
      itemStyle: { color }, data: valid.map((point) => ({ value: [point.time, point.value], exact: point.exact })),
    };
    // Real two-point lines also work at tiny SOL prices where ECharts markLine drops levels.
    for (const value of new Set(valid.map((point) => point.value))) sparseLevels.push({
      type: 'line', name: 'Sparse market level', data: [[min, value], [max, value]],
      showSymbol: false, silent: true, lineStyle: { type: 'dashed', color, width: 1 },
      endLabel: { show: true, formatter: label(value), color, align: 'right', distance: -6 },
      tooltip: { show: false }, emphasis: { disabled: true }, z: 0,
    });
  } else if (style === 'candles') {
    market = {
      type: 'candlestick', name: 'Market', layout: 'horizontal', large: false,
      dimensions: ['time', 'open', 'close', 'low', 'high'], encode: { x: 'time', y: ['open', 'close', 'low', 'high'] }, barMaxWidth: 14,
      itemStyle: { color: terminal.gain, color0: terminal.loss, borderColor: terminal.gain, borderColor0: terminal.loss },
      data: response.candles.flatMap((candle) => {
        const values = candle[view];
        if (values == null) return [];
        const coordinates = [values.open, values.close, values.low, values.high].map(toChartNumber);
        return coordinates.some((value) => value == null) ? [] : [{ value: [Date.parse(candle.bucketStart), ...coordinates] }];
      }),
    };
  } else {
    const hasIsolatedPoints = points.some((point, index) => point.value != null && points[index - 1]?.value == null && points[index + 1]?.value == null);
    market = {
      type: 'line', name: 'Market', data: points.map((point) => ({ value: [point.time, point.value], exact: point.exact })),
      connectNulls: false, step: 'end', showSymbol: hasIsolatedPoints, symbol: 'circle', symbolSize: 6,
      lineStyle: { width: 2, color }, itemStyle: { color }, emphasis: { disabled: true },
    };
  }
  return {
    animation: false, useUTC: true,
    grid: rail ? [{ left: 12, right: 68, top: 34, bottom: 92 }, { left: 12, right: 68, bottom: 38, height: 26 }] : { left: 12, right: 68, top: 34, bottom: 38 },
    tooltip: { show: false, trigger: 'axis' },
    axisPointer: { show: true, triggerOn: 'mousemove|click', link: [{ xAxisIndex: 'all' }] },
    xAxis: rail ? [{ ...xAxis, axisLabel: { show: false } }, { ...xAxis, gridIndex: 1, axisPointer: { show: false } }] : xAxis,
    yAxis: rail ? [yAxis, { type: 'value', gridIndex: 1, min: 0, max: 1, name: 'Trade times', nameTextStyle: { color: terminal.dimmed }, axisLabel: { show: false }, splitLine: { show: false } }] : yAxis,
    dataZoom: [{ ...INSIDE_ZOOM, xAxisIndex: rail ? [0, 1] : 0 }],
    series: [market, ...sparseLevels, ...chartMarkerSeries(response, view)],
  };
}

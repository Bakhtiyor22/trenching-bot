import type { EChartsOption, SeriesOption } from 'echarts';
import type { ChartResponse, ChartTick, Observation, ObservedChartMarker } from '../api/types';
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
  const plain = value.toLocaleString('en-US', {
    maximumSignificantDigits: AXIS_PRICE_DIGITS,
    useGrouping: false,
  });
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
  return {
    value: [observation.slot, toChartNumber(observation.price)],
    observation,
    ...(text ? { note: text } : {}),
  };
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
export function observationSeries(
  observations: Observation[],
  note?: ObservationNote,
): SeriesOption[] {
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
export const INSIDE_ZOOM = {
  type: 'inside',
  filterMode: 'none',
  zoomOnMouseWheel: 'ctrl',
  moveOnMouseWheel: false,
} as const;

/** [priceLabel] names the price axis, e.g. "Price (USDT)". */
export function slotAxes(
  priceLabel = 'Price',
): Pick<EChartsOption, 'xAxis' | 'yAxis' | 'dataZoom'> {
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
    yAxis: {
      type: 'value',
      name: priceLabel,
      scale: true,
      axisLabel: { formatter: priceAxisLabel },
    },
    dataZoom: [
      { ...INSIDE_ZOOM, xAxisIndex: 0 },
      { type: 'slider', xAxisIndex: 0, filterMode: 'none', height: 18, bottom: 6 },
    ],
  };
}

/** Tooltip formatter for item tooltips whose data carries an `observation` or a `tip` string. */
export function itemTooltip(params: unknown): string {
  const data = (params as { data?: { observation?: Observation; tip?: string; note?: string } })
    .data;
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
  tick?: ChartTick;
}

export interface MarketChartConfig {
  window?: { from: number; to: number };
  extent?: { from: number; to: number };
  fullPage?: boolean;
  drawing?: boolean;
}

export function plotTime(item: { time: string; estimatedTime?: string }): number {
  return Date.parse(item.estimatedTime ?? item.time);
}

/** Block-time bounds also contain the later estimated instants and bucket closes we plot. */
export function chartExtent(response: ChartResponse): { from: number; to: number } {
  const bounds = response.extent
    ? [Date.parse(response.extent.firstTime), Date.parse(response.extent.lastTime)]
    : [Date.parse(response.range.from), Date.parse(response.range.to)];
  const coordinates = [
    ...bounds,
    ...response.markers.items.map(plotTime),
    ...response.paperFills.map(plotTime),
    ...(response.ticks?.included
      ? response.ticks.items.map(plotTime)
      : response.candles.flatMap((candle) => [
          Date.parse(candle.bucketStart),
          Date.parse(candle.bucketStart) + response.bucketSeconds * 1000,
        ])),
  ].filter(Number.isFinite);
  const from = coordinates.length ? Math.min(...coordinates) : 0;
  const to = coordinates.length ? Math.max(...coordinates) : from;
  return { from, to: Math.max(to, from + 2000) };
}

/** A recorded window immediately after an unrecorded remainder of a tracked trade's slot. */
export function unrecordedStart(response: ChartResponse, marker: ObservedChartMarker) {
  if (!marker.isTrackedWallet) return null;
  const coverage = response.coverage?.find(
    (window) => window.fromSlot > marker.slot && window.fromSlot - marker.slot <= 1,
  );
  if (!coverage) return null;
  const tick = response.ticks?.items.find(
    (item) => item.slot >= coverage.fromSlot && item.slot <= coverage.throughSlot,
  );
  if (tick) return { ...coverage, time: plotTime(tick), price: tick.price, mcap: tick.mcap };
  const candle = response.candles.find(
    (item) =>
      Date.parse(item.bucketStart) + response.bucketSeconds * 1000 >= Date.parse(marker.time),
  );
  return candle
    ? {
        ...coverage,
        time: Date.parse(candle.bucketStart) + response.bucketSeconds * 1000,
        price: candle.price.close,
        mcap: candle.mcap?.close ?? null,
      }
    : null;
}

export function mcapAxisLabel(value: number): string {
  const magnitude = Math.abs(value);
  const [divisor, suffix] =
    magnitude >= 1e9
      ? [1e9, 'b']
      : magnitude >= 1e6
        ? [1e6, 'm']
        : magnitude >= 1e3
          ? [1e3, 'k']
          : [1, ''];
  return `$${(value / divisor).toLocaleString('en-US', { maximumFractionDigits: 1, useGrouping: false })}${suffix}`;
}

/**
 * One point per bucket with trades, at its close, keeping the exact string. A bucket without trades adds no point: the last trade's
 * price holds until the next trade, so the line steps across it. Only a close without a value (a minute with no rate) breaks it.
 */
export function chartMarketPoints(response: ChartResponse, view: ChartView): MarketPoint[] {
  if (response.ticks?.included)
    return response.ticks.items.map((tick) => ({
      time: plotTime(tick),
      value: toChartNumber(tick[view]),
      exact: tick[view],
      tick,
    }));
  return [...response.candles]
    .sort((a, b) => Date.parse(a.bucketStart) - Date.parse(b.bucketStart))
    .map((candle) => {
      const exact = candle[view]?.close ?? null;
      return {
        time: Date.parse(candle.bucketStart) + response.bucketSeconds * 1000,
        value: toChartNumber(exact),
        exact,
      };
    });
}

/** The point in force at [time]: the last one at or before it. */
export function marketPointAt(points: MarketPoint[], time: number): MarketPoint | null {
  let held: MarketPoint | null = null;
  for (const point of points) if (point.time <= time) held = point;
  return held;
}

/** Tick spacings in seconds; the axis takes the smallest that keeps it to [MAX_TIME_TICKS] labels. */
const SECOND_STEPS = [
  0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1_800, 3_600, 7_200, 14_400, 43_200,
  86_400,
];
const MAX_TIME_TICKS = 6;

/** Ticks between [min] and [max] at whole multiples of a round number of seconds from [origin], so one of them is `0s`. */
export function secondTicks(
  min: number,
  max: number,
  origin: number,
  maxTicks = MAX_TIME_TICKS,
): number[] {
  const span = (max - min) / 1000;
  const step =
    (SECOND_STEPS.find((seconds) => span / seconds < maxTicks) ?? SECOND_STEPS.at(-1)!) * 1000;
  const ticks: number[] = [];
  for (let k = Math.ceil((min - origin) / step); origin + k * step <= max; k++)
    ticks.push(origin + k * step);
  return ticks;
}

/** Interactive Decu and paper markers are real buttons in the HTML overlay. */
export function chartMarkerSeries(response: ChartResponse, view: ChartView): SeriesOption[] {
  const series: SeriesOption[] = [
    {
      type: 'scatter',
      name: 'Other traders',
      symbol: 'circle',
      symbolSize: 6,
      silent: true,
      tooltip: { show: false },
      emphasis: { disabled: true },
      z: 3,
      data: response.markers.items
        .filter((marker) => !marker.isTrackedWallet)
        .flatMap((marker) => {
          const coordinate = toChartNumber(marker[view]);
          return coordinate == null
            ? []
            : [
                {
                  value: [plotTime(marker), coordinate],
                  itemStyle: {
                    color: marker.side === 'BUY' ? OTHER_TRADER_COLOR : terminal.ground,
                    borderColor: OTHER_TRADER_COLOR,
                    borderWidth: 1,
                    opacity: 1,
                  },
                },
              ];
        }),
    },
  ];
  // Cluster payloads contain time bounds and counts, but no price coordinate.
  if (response.markers.clusters.length)
    series.push({
      type: 'scatter',
      name: CAPTURED_TRADERS,
      xAxisIndex: 1,
      yAxisIndex: 1,
      symbol: 'circle',
      symbolSize: 25,
      itemStyle: { color: terminal.raised, borderColor: OTHER_TRADER_COLOR, borderWidth: 1 },
      label: {
        show: true,
        position: 'inside',
        color: terminal.text,
        formatter: (params: unknown) =>
          String((params as { data?: { cluster?: { count: number } } }).data?.cluster?.count ?? ''),
      },
      tooltip: { show: false },
      data: response.markers.clusters.map((cluster) => ({
        value: [Date.parse(cluster.firstTime), 0.5],
        cluster,
      })),
    });
  return series;
}

export function marketAxisDomain(values: number[]): { min: number; max: number } {
  if (!values.length) return { min: 0, max: 1 };
  const min = Math.min(...values);
  const max = Math.max(...values);
  const padding = Math.max(
    Math.abs(min) * 0.1,
    Math.abs(max) * 0.1,
    (max - min) * 0.1,
    Number.MIN_VALUE,
  );
  return { min: min - padding, max: max + padding };
}

/** [origin] is the time the axis counts seconds from, the cycle's first buy; the range start when omitted. */
export function chartCandleOption(
  response: ChartResponse,
  requestedView: ChartView,
  style: MarketChartStyle = 'line',
  origin?: number | null,
  config: MarketChartConfig = {},
): EChartsOption {
  const view =
    requestedView === 'mcap' && !response.valuationBasis.available ? 'price' : requestedView;
  const points = chartMarketPoints(response, view);
  const valid = points.filter(
    (point): point is MarketPoint & { value: number } => point.value != null,
  );
  const sparse = !response.ticks?.included && valid.length < 3;
  const color =
    (valid.at(-1)?.value ?? 0) >= (valid[0]?.value ?? 0) ? terminal.gain : terminal.loss;
  const rail = response.markers.clusters.length > 0;
  const extent = config.extent ?? chartExtent(response);
  const extentMin = extent.from;
  const extentMax = extent.to;
  const padding = Math.max(1, (extentMax - extentMin) * 0.02);
  const min = extentMin - padding;
  const max = extentMax + padding;
  const window = config.window ?? extent;
  const inView = (time: number) => time >= window.from && time <= window.to;
  const visible = valid.filter((point) => inView(point.time));
  const held = style === 'line' ? marketPointAt(points, window.from) : null;
  const ticks = secondTicks(
    window.from,
    window.to,
    origin ?? extentMin,
    config.fullPage ? 8 : MAX_TIME_TICKS,
  );
  const fractional = ticks.length > 1 && (ticks[1] ?? 0) - (ticks[0] ?? 0) < 1000;
  const zero = origin ?? extentMin;
  const xAxis = {
    type: 'time' as const,
    min,
    max,
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { show: false },
    axisLabel: {
      color: terminal.dimmed,
      customValues: ticks,
      formatter: (value: number) => secondsFrom(value, zero, fractional),
    },
    axisPointer: {
      show: true,
      triggerTooltip: false,
      snap: false,
      lineStyle: { type: 'dashed' as const, color: terminal.dimmed },
      label: { show: false },
    },
  };
  const yValues = [
    ...(held?.value != null ? [held.value] : []),
    ...visible.map((point) => point.value),
    ...response.markers.items
      .filter((marker) => inView(plotTime(marker)))
      .flatMap((marker) => {
        const value = toChartNumber(marker[view]);
        return value == null ? [] : [value];
      }),
    ...response.paperFills
      .filter((fill) => inView(plotTime(fill)))
      .flatMap((fill) => {
        const value = toChartNumber(fill[view]);
        return value == null ? [] : [value];
      }),
    ...(style === 'candles' && !sparse
      ? response.candles
          .filter(
            (candle) =>
              Date.parse(candle.bucketStart) <= window.to &&
              Date.parse(candle.bucketStart) + response.bucketSeconds * 1000 >= window.from,
          )
          .flatMap((candle) =>
            [toChartNumber(candle[view]?.low), toChartNumber(candle[view]?.high)].filter(
              (value): value is number => value != null,
            ),
          )
      : []),
  ];
  const domain = marketAxisDomain(yValues);
  const label = view === 'mcap' ? mcapAxisLabel : priceAxisLabel;
  const yAxis = {
    type: 'value' as const,
    position: 'right' as const,
    name: view === 'mcap' ? 'Mcap (USDT)' : 'Price (SOL)',
    nameTextStyle: { color: terminal.dimmed },
    scale: true,
    ...domain,
    interval: (domain.max - domain.min) / 3,
    splitNumber: 3,
    axisLabel: { formatter: label, color: terminal.dimmed, margin: 12 },
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { show: true, lineStyle: { type: 'dashed' as const, color: terminal.line } },
  };
  let market: SeriesOption;
  const sparseLevels: SeriesOption[] = [];
  if (sparse) {
    market = {
      type: 'scatter',
      name: 'Market',
      symbol: 'circle',
      symbolSize: 6,
      silent: true,
      itemStyle: { color },
      data: valid.map((point) => ({ value: [point.time, point.value], exact: point.exact })),
    };
    // Real two-point lines also work at tiny SOL prices where ECharts markLine drops levels.
    points.forEach((point, index) => {
      if (point.value == null) return;
      const end = Math.min(points[index + 1]?.time ?? max, max);
      if (end <= point.time) return;
      sparseLevels.push({
        type: 'line',
        name: 'Sparse market level',
        data: [
          [point.time, point.value],
          [end, point.value],
        ],
        showSymbol: false,
        silent: true,
        lineStyle: { type: 'dashed', color, width: 1 },
        endLabel: {
          show: true,
          formatter: label(point.value),
          color,
          align: 'right',
          distance: -6,
        },
        tooltip: { show: false },
        emphasis: { disabled: true },
        z: 0,
      });
    });
  } else if (style === 'candles') {
    market = {
      type: 'candlestick',
      name: 'Market',
      layout: 'horizontal',
      large: false,
      dimensions: ['time', 'open', 'close', 'low', 'high'],
      encode: { x: 'time', y: ['open', 'close', 'low', 'high'] },
      barMaxWidth: 14,
      itemStyle: {
        color: terminal.gain,
        color0: terminal.loss,
        borderColor: terminal.gain,
        borderColor0: terminal.loss,
      },
      data: response.candles.flatMap((candle) => {
        const values = candle[view];
        if (values == null) return [];
        const coordinates = [values.open, values.close, values.low, values.high].map(toChartNumber);
        return coordinates.some((value) => value == null)
          ? []
          : [{ value: [Date.parse(candle.bucketStart), ...coordinates] }];
      }),
    };
  } else {
    const hasIsolatedPoints = points.some(
      (point, index) =>
        point.value != null && points[index - 1]?.value == null && points[index + 1]?.value == null,
    );
    market = {
      type: 'line',
      name: 'Market',
      data: points.map((point) => ({ value: [point.time, point.value], exact: point.exact })),
      connectNulls: false,
      step: 'end',
      showSymbol: response.ticks?.included ? visible.length < 120 : hasIsolatedPoints,
      symbol: 'circle',
      symbolSize: response.ticks?.included ? 4 : 6,
      lineStyle: { width: 2, color },
      itemStyle: { color },
      emphasis: { disabled: true },
    };
  }
  return {
    animation: false,
    useUTC: true,
    grid: rail
      ? [
          { left: 12, right: 68, top: 34, bottom: 92 },
          { left: 12, right: 68, bottom: 38, height: 26 },
        ]
      : { left: 12, right: 68, top: 34, bottom: 38 },
    tooltip: { show: false, trigger: 'none', triggerOn: 'none' },
    axisPointer: {
      show: true,
      triggerTooltip: false,
      triggerOn: 'mousemove|click',
      link: [{ xAxisIndex: 'all' }],
    },
    xAxis: rail
      ? [
          { ...xAxis, axisLabel: { show: false } },
          { ...xAxis, gridIndex: 1, axisPointer: { show: false } },
        ]
      : xAxis,
    yAxis: rail
      ? [
          yAxis,
          {
            type: 'value',
            gridIndex: 1,
            min: 0,
            max: 1,
            name: 'Trade times',
            nameTextStyle: { color: terminal.dimmed },
            axisLabel: { show: false },
            splitLine: { show: false },
          },
        ]
      : yAxis,
    dataZoom: [
      {
        ...INSIDE_ZOOM,
        xAxisIndex: rail ? [0, 1] : 0,
        startValue: window.from,
        endValue: window.to,
        minValueSpan: Math.min(2000, extentMax - extentMin),
        zoomOnMouseWheel: config.drawing ? false : config.fullPage ? true : 'ctrl',
        moveOnMouseMove: !config.drawing,
        disabled: config.drawing ?? false,
      },
    ],
    series: [market, ...sparseLevels, ...chartMarkerSeries(response, view)],
  };
}

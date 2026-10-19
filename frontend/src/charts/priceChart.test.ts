import type { LineSeriesOption } from 'echarts';
import { describe, expect, it } from 'vitest';
import { sampleObservations } from '../mocks/fixtures';
import type { ChartResponse } from '../api/types';
import { chartCandleOption, chartMarketPoints, chartMarkerSeries, marketPointAt, mcapAxisLabel, observationSeries, observationTooltip, priceAxisLabel, secondTicks } from './priceChart';

const time = '2026-10-15T00:00:00Z';
const ohlc = { open: '1.00000000000001', close: '2', low: '1', high: '3' };
const chart: ChartResponse = {
  bucketSeconds: 60, range: { from: time, to: '2026-10-15T00:05:00Z' }, trackedWallet: 'wallet',
  valuationBasis: { available: true, label: '<basis>', supply: '1000000000', supplySource: 'supply', quoteCurrency: 'USDT', rateSource: '<rate>', rateGranularity: 'MINUTE', unavailableReason: null },
  candles: [
    { bucketStart: time, observationCount: 1, price: ohlc, mcap: ohlc, mcapMissingPoints: 0, mcapUnsupportedPoints: 0 },
    { bucketStart: '2026-10-15T00:01:00Z', observationCount: 1, price: ohlc, mcap: null, mcapMissingPoints: 1, mcapUnsupportedPoints: 2 },
    { bucketStart: '2026-10-15T00:04:00Z', observationCount: 1, price: ohlc, mcap: ohlc, mcapMissingPoints: 0, mcapUnsupportedPoints: 0 },
  ],
  markers: { available: true, unavailableReason: null, mode: 'ITEMS', total: 1, nextCursor: null, clusters: [], items: [
    { kind: 'OBSERVED', observationId: 'obs', trader: '<wallet>', isTrackedWallet: true, side: 'BUY', solLamports: '1000000001', tokenRaw: '1234567', price: '1.00000000000001', mcap: '25.00000000000001', slot: 1, time, signature: null, source: '<source>' },
  ] },
  paperFills: [{ kind: 'PAPER', phase: 'ENTRY', side: 'BUY', intentId: 'intent', time, slot: 1, price: '2.000000000001', mcap: '25.00000000000001', quantity: '123.000000000001', reason: null }],
};

describe('time-axis candle chart', () => {
  it('defaults to a quiet market line and removes the zoom slider and tooltip', () => {
    const option = chartCandleOption(chart, 'price');
    expect((option.series as Array<Record<string, unknown>>)[0]).toMatchObject({ type: 'line', connectNulls: false, step: 'end', lineStyle: { width: 2, color: '#3DDC97' } });
    expect(option.tooltip).toMatchObject({ show: false });
    expect(option.grid).toMatchObject({ right: 68 });
    expect(option.yAxis).toMatchObject({ position: 'right', splitNumber: 3, splitLine: { lineStyle: { type: 'dashed' } } });
    expect(option.dataZoom).toMatchObject([{ type: 'inside', zoomOnMouseWheel: 'ctrl' }]);
    expect(option.legend).toBeUndefined();
  });
  it('formats compact dollar labels', () => {
    expect([10000, 1200000, 3400000000, 1.25].map(mcapAxisLabel)).toEqual(['$10k', '$1.2m', '$3.4b', '$1.3']);
  });

  it('retains exact closes, holds the price across buckets without trades and breaks only at null rates', () => {
    expect(chartMarketPoints(chart, 'mcap').map((p) => [p.time, p.value, p.exact])).toEqual([
      [Date.parse(time), 2, '2'],
      [Date.parse('2026-10-15T00:01:00Z'), null, null],
      [Date.parse('2026-10-15T00:04:00Z'), 2, '2'],
    ]);
    const points = chartMarketPoints({ ...chart, candles: [...chart.candles].reverse() }, 'mcap');
    expect(points).toEqual(chartMarketPoints(chart, 'mcap'));
  });

  it('reads the value in force at a time from the last point at or before it', () => {
    const points = chartMarketPoints(chart, 'price');
    expect(marketPointAt(points, Date.parse('2026-10-15T00:03:59Z'))?.time).toBe(Date.parse('2026-10-15T00:01:00Z'));
    expect(marketPointAt(points, Date.parse('2026-10-15T00:04:00Z'))?.time).toBe(Date.parse('2026-10-15T00:04:00Z'));
    expect(marketPointAt(points, Date.parse(time) - 1)?.time).toBe(Date.parse(time));
    expect(marketPointAt([], 0)).toBeNull();
  });

  it('labels the time axis in seconds from the first buy and uses a dashed crosshair', () => {
    const firstBuy = Date.parse('2026-10-15T00:01:07Z');
    const option = chartCandleOption(chart, 'price', 'line', firstBuy);
    const axis = option.xAxis as { min: number; max: number; axisLabel: { customValues: number[]; formatter: (v: number) => string } };
    expect(axis.axisLabel.customValues.map(axis.axisLabel.formatter)).toEqual(['-60s', '0s', '+60s', '+120s', '+180s']);
    expect(option.xAxis).toMatchObject({ axisPointer: { lineStyle: { type: 'dashed' } } });
    const fromStart = chartCandleOption(chart, 'price').xAxis as typeof axis;
    expect(fromStart.axisLabel.customValues.map(fromStart.axisLabel.formatter)).toEqual(['0s', '+60s', '+120s', '+180s', '+240s', '+300s']);
  });

  it('spaces second ticks at the smallest round step that keeps six labels or fewer', () => {
    const origin = Date.parse(time);
    expect(secondTicks(origin, origin + 5_000, origin).map((t) => (t - origin) / 1000)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(secondTicks(origin - 200_000, origin + 380_000, origin).map((t) => (t - origin) / 1000)).toEqual([-120, 0, 120, 240, 360]);
  });

  it('uses red for a falling market and supports optional OHLC candles', () => {
    const falling = { ...chart, candles: chart.candles.map((c, i) => ({ ...c, price: { ...ohlc, close: String(3 - i) } })) };
    expect((chartCandleOption(falling, 'price').series as Array<Record<string, unknown>>)[0]).toMatchObject({ lineStyle: { color: '#FF6B6B' } });
    const option = chartCandleOption(chart, 'price', 'candles');
    expect((option.series as Array<Record<string, unknown>>)[0]).toMatchObject({ type: 'candlestick', data: expect.arrayContaining([{ value: [Date.parse(time), 1.00000000000001, 2, 1, 3] }]) });
  });

  it('uses sparse points on labelled dashed levels and at least ten percent axis padding', () => {
    const response = { ...chart, markers: { ...chart.markers, items: [] }, paperFills: [] };
    const option = chartCandleOption(response, 'mcap', 'candles');
    const series = option.series as Array<Record<string, unknown>>;
    expect(series[0]).toMatchObject({ type: 'scatter' });
    expect(series[1]).toMatchObject({ type: 'line', lineStyle: { type: 'dashed' }, endLabel: { formatter: '$2' }, data: [[Date.parse(time), 2], [Date.parse(chart.range.to), 2]] });
    expect(option.yAxis).toMatchObject({ min: 1.8, max: 2.2 });
    const empty = chartCandleOption({ ...response, candles: [] }, 'mcap');
    expect(empty.yAxis).toMatchObject({ min: 0, max: 1 });
  });

  it('steps across buckets without trades and marks only points cut off by missing rates', () => {
    const candle = chart.candles[0];
    if (!candle) throw new Error('Fixture needs one candle');
    const spaced = { ...chart, candles: Array.from({ length: 3 }, (_, index) => ({ ...candle, bucketStart: new Date(Date.parse(time) + index * 120_000).toISOString() })) };
    expect((chartCandleOption(spaced, 'mcap').series as Array<Record<string, unknown>>)[0]).toMatchObject({ type: 'line', step: 'end', showSymbol: false });
    const unrated = { ...spaced, candles: spaced.candles.map((item, index) => (index === 1 ? item : { ...item, mcap: null })) };
    const withRate = { ...unrated, candles: [...unrated.candles, { ...candle, bucketStart: '2026-10-15T00:08:00Z' }, { ...candle, bucketStart: '2026-10-15T00:09:00Z' }] };
    expect((chartCandleOption(withRate, 'mcap').series as Array<Record<string, unknown>>)[0]).toMatchObject({ type: 'line', showSymbol: true, connectNulls: false });
  });

  it('falls back to price when no valuation is available', () => {
    const option = chartCandleOption({ ...chart, valuationBasis: { ...chart.valuationBasis, available: false } }, 'mcap');
    expect(option.yAxis).toMatchObject({ name: 'Price (SOL)' });
    expect((option.series as Array<Record<string, unknown>>)[0]).toMatchObject({ type: 'line' });
  });

  it('leaves tracked and paper markers to the HTML overlay and draws noninteractive grey other traders', () => {
    const tracked = chart.markers.items[0]!;
    const response = { ...chart, markers: { ...chart.markers, items: [tracked, { ...tracked, observationId: 'buy', isTrackedWallet: false }, { ...tracked, observationId: 'sell', side: 'SELL' as const, isTrackedWallet: false }] } };
    const series = chartMarkerSeries(response, 'mcap') as Array<{ data: Array<{ value: number[]; itemStyle: { color: string; borderColor: string } }> }>;
    expect(series).toHaveLength(1);
    expect(series[0]).toMatchObject({ silent: true, symbolSize: 6 });
    expect(series[0]!.data).toHaveLength(2);
    expect(series[0]!.data[0]!.value).toEqual([Date.parse(time), 25.00000000000001]);
    expect(series[0]!.data[0]!.itemStyle).toMatchObject({ color: '#5C6B78', borderColor: '#5C6B78' });
    expect(series[0]!.data[1]!.itemStyle).toMatchObject({ color: '#0A0D10', borderColor: '#5C6B78' });
  });

  it('keeps clusters on a time rail without inventing price coordinates', () => {
    const cluster = { bucketStart: time, firstTime: time, lastTime: time, firstSlot: 1, lastSlot: 1, count: 20, buyCount: 12, sellCount: 8 };
    const response = { ...chart, markers: { ...chart.markers, mode: 'CLUSTERED' as const, clusters: [cluster] } };
    const series = chartMarkerSeries(response, 'mcap') as Array<{ name: string; data: Array<{ value: number[] }> }>;
    expect(series.find((s) => s.name === 'Captured traders')).toMatchObject({ xAxisIndex: 1, yAxisIndex: 1, data: [{ value: [Date.parse(time), 0.5], cluster }] });
    expect(chartCandleOption(response, 'mcap').xAxis).toMatchObject([{ type: 'time' }, { type: 'time', gridIndex: 1 }]);
  });
});

describe('price chart', () => {
  it('escapes imported source text in the HTML tooltip', () => {
    const observation = {
      ...sampleObservations[0]!,
      source: '<img src=x onerror="alert(1)"> & fixture',
    };
    const tooltip = observationTooltip(observation);
    expect(tooltip).not.toContain('<img');
    expect(tooltip).toContain('&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; fixture');
    expect(tooltip).toContain('Price <b>0.000000095</b>');
  });

  it('breaks the price line at invalid observations instead of joining across them', () => {
    const observations = sampleObservations.slice(0, 3).map((observation, index) => ({
      ...observation,
      quality: index === 1 ? 'INVALID' : 'LAST_TRADE',
    }));
    const line = observationSeries(observations)[0] as LineSeriesOption;
    const data = line.data as Array<{ value: [number, number | null] }>;
    expect(data.map((point) => point.value)).toEqual([
      [1001, 0.000000095],
      [1002, null],
      [1003, 0.000000086],
    ]);
    expect(line.connectNulls).toBe(false);
  });

  it('labels the price axis without exponents, tiny prices in the compact form', () => {
    expect(priceAxisLabel(1.15e-7)).toBe('0.0₆115');
    expect(priceAxisLabel(0.000000065)).toBe('0.0₇65');
    expect(priceAxisLabel(212.34)).toBe('212.34');
  });
});

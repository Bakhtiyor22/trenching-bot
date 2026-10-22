import { useCallback, useEffect, useRef } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import { clampRange, panRange, zoomRange, type TimeRange } from '../features/results/chartRange';
import type { ChartView, MarketChartStyle } from './priceChart';

export type ChartLayer = 'decu' | 'levels' | 'paper' | 'traders' | 'watchlist';
const LAYERS: ChartLayer[] = ['decu', 'levels', 'paper', 'traders', 'watchlist'];
const CHART_PARAMS = ['pair', 'from', 'to', 'view', 'style', 'layers', 'action', 'fill'];

export interface ChartViewOptions {
  kind: 'run' | 'dataset';
  extent?: TimeRange | null;
  resetRange?: TimeRange | null;
}

export function chartSearchParams(params: URLSearchParams, pair?: string): string {
  const next = new URLSearchParams();
  for (const key of CHART_PARAMS) {
    const value = params.get(key);
    if (value !== null) next.set(key, value);
  }
  if (pair) next.set('pair', pair);
  const query = next.toString();
  return query ? `?${query}` : '';
}

export function readChartWindow(params: URLSearchParams): TimeRange | null {
  const from = Date.parse(params.get('from') ?? '');
  const to = Date.parse(params.get('to') ?? '');
  return Number.isFinite(from) && Number.isFinite(to) && from < to ? { from, to } : null;
}

export function followChartExtent(
  range: TimeRange,
  previous: TimeRange,
  next: TimeRange,
): TimeRange {
  const following = Math.abs(range.to - previous.to) < 1;
  return clampRange(
    following ? { from: next.to - (range.to - range.from), to: next.to } : range,
    next,
  );
}

export function useChartView({ kind, extent, resetRange }: ChartViewOptions) {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const requested = readChartWindow(params);
  const fallback = resetRange ?? extent ?? null;
  const extentFrom = extent?.from;
  const extentTo = extent?.to;
  const requestedFrom = requested?.from;
  const requestedTo = requested?.to;
  const fallbackFrom = fallback?.from;
  const fallbackTo = fallback?.to;
  const scope = `${kind}:${location.pathname}:${params.get('pair') ?? ''}`;
  const range = requested ?? fallback;
  const window = range && extent ? clampRange(range, extent) : range;
  const metric: ChartView = params.get('view') === 'price' ? 'price' : 'mcap';
  const style: MarketChartStyle = params.get('style') === 'candles' ? 'candles' : 'line';
  const defaults: ChartLayer[] =
    kind === 'run' ? ['decu', 'levels', 'paper', 'watchlist'] : ['decu', 'levels', 'watchlist'];
  const storedLayers = params.get('layers');
  const layers =
    storedLayers === null
      ? defaults
      : LAYERS.filter((layer) => storedLayers.split(',').includes(layer));
  const setWindow = useCallback(
    (next: TimeRange | null) => {
      const clamped =
        next && extentFrom !== undefined && extentTo !== undefined
          ? clampRange(next, { from: extentFrom, to: extentTo })
          : next;
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          if (clamped) {
            updated.set('from', new Date(clamped.from).toISOString());
            updated.set('to', new Date(clamped.to).toISOString());
          } else {
            updated.delete('from');
            updated.delete('to');
          }
          return updated;
        },
        { replace: true },
      );
    },
    [extentFrom, extentTo, setParams],
  );
  const write = useCallback(
    (key: string, value: string) => {
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          updated.set(key, value);
          return updated;
        },
        { replace: true },
      );
    },
    [setParams],
  );
  const initialized = useRef<string | null>(null);
  useEffect(() => {
    if (initialized.current === scope || extentFrom === undefined || extentTo === undefined) return;
    initialized.current = scope;
    if (
      requestedFrom === undefined &&
      requestedTo === undefined &&
      fallbackFrom !== undefined &&
      fallbackTo !== undefined
    ) {
      setWindow(
        clampRange({ from: fallbackFrom, to: fallbackTo }, { from: extentFrom, to: extentTo }),
      );
    }
  }, [
    scope,
    extentFrom,
    extentTo,
    requestedFrom,
    requestedTo,
    fallbackFrom,
    fallbackTo,
    setWindow,
  ]);
  const previousExtent = useRef<TimeRange | null>(null);
  useEffect(() => {
    const previous = previousExtent.current;
    const nextExtent =
      extentFrom !== undefined && extentTo !== undefined
        ? { from: extentFrom, to: extentTo }
        : null;
    previousExtent.current = nextExtent;
    if (
      !nextExtent ||
      !previous ||
      requestedFrom === undefined ||
      requestedTo === undefined ||
      (previous.from === nextExtent.from && previous.to === nextExtent.to)
    )
      return;
    const next = followChartExtent({ from: requestedFrom, to: requestedTo }, previous, nextExtent);
    if (next.from !== requestedFrom || next.to !== requestedTo) setWindow(next);
  }, [extentFrom, extentTo, requestedFrom, requestedTo, setWindow]);
  return {
    window,
    setWindow,
    metric,
    setMetric: (next: ChartView) => write('view', next),
    style,
    setStyle: (next: MarketChartStyle) => write('style', next),
    layers,
    setLayers: (next: ChartLayer[]) =>
      write('layers', LAYERS.filter((layer) => next.includes(layer)).join(',')),
    fit: () => extent && setWindow(extent),
    reset: () => setWindow(resetRange ?? extent ?? null),
    zoom: (factor: number, anchor?: number) =>
      window && extent && setWindow(zoomRange(window, factor, extent, anchor)),
    pan: (fraction: number) => window && extent && setWindow(panRange(window, fraction, extent)),
  };
}

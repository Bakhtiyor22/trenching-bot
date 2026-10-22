import {
  ActionIcon,
  Box,
  Button,
  Card,
  Checkbox,
  Divider,
  Group,
  Modal,
  Popover,
  ScrollArea,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import type { ECharts } from 'echarts';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useChartView, type ChartLayer } from '../charts/chartViewState';
import { chartContainsPoint } from '../charts/chartGeometry';
import { useChartDrawings, type DrawingTool } from '../charts/chartDrawings';
import { api } from '../api/endpoints';
import { useDatasetChart, useRunChart } from '../api/queries';
import type { ChartRequest, ChartResponse, Scenario } from '../api/types';
import {
  actionKey,
  markerLabel,
  type MarketAction,
  type MarkerAnchor,
} from '../charts/marketMarkers';
import {
  cycleStart,
  marketChange,
  marketRange,
  marketTime,
  marketValue,
  secondsFrom,
  timeInRange,
  type MarketRangePreset,
  type MarketTimeRange,
} from '../charts/marketSummary';
import {
  chartCandleOption,
  chartExtent,
  chartMarketPoints,
  marketPointAt,
  plotTime,
} from '../charts/priceChart';
import { readWatchlist, WATCHLIST_KEY, WATCHLIST_LIMIT } from '../charts/tradeWallets';
import { chartTimelineOption } from '../features/runs/timelineChart';
import { clampRange } from '../features/results/chartRange';
import { isSolanaAddress } from '../lib/base58';
import { mintLabel, shortId } from '../lib/format';
import { FONT_MONO, terminal } from '../theme';
import { Chart } from './Chart';
import { ChartDrawingLayer } from './ChartDrawingLayer';
import { CopyValue } from './DisplayValues';
import { MarketActionCard } from './MarketActionCard';
import { MarketMarkerOverlay } from './MarketMarkerOverlay';
import { EmptyState, ErrorState, LoadingState } from './StateViews';
import classes from './TradeChart.module.css';

interface TradeChartProps {
  kind: 'run' | 'dataset';
  id: string;
  pair: string;
  trackedWallet?: string | null;
  initialChart?: ChartResponse;
  scenarios?: Scenario[];
  cycle?: Scenario | null;
  onOpenAction?: (action: MarketAction) => void;
  range?: MarketTimeRange;
  view?: 'mcap' | 'price';
  onViewChange?: (view: 'mcap' | 'price') => void;
  onRangeChange?: (range: MarketTimeRange) => void;
  height?: number | string;
  expanded?: boolean;
  onData?: (data: ChartResponse, pair: string) => void;
  emptyActions?: ReactNode;
  onToggleFullscreen?: () => void;
  fullscreen?: boolean;
}
/** The backend's bucket cap: ranges up to about half an hour get one-second buckets. */
const MAX_BUCKETS = 2_000;
const RANGE_OPTIONS: { value: MarketRangePreset; label: string }[] = [
  { value: 'cycle', label: 'Cycle' },
  { value: '5m', label: '5m' },
  { value: '15m', label: '15m' },
  { value: '1h', label: '1H' },
  { value: 'all', label: 'All' },
];
function useChartData(
  kind: TradeChartProps['kind'],
  id: string,
  request: ChartRequest,
  enabled = true,
) {
  const run = useRunChart(id, request, enabled && kind === 'run');
  const dataset = useDatasetChart(id, request, enabled && kind === 'dataset');
  return kind === 'run' ? run : dataset;
}
function ChartState({ children }: { children: React.ReactNode }) {
  return (
    <Card component="section" padding="lg" style={{ minHeight: 600 }}>
      {children}
    </Card>
  );
}

export function TradeChart(props: TradeChartProps) {
  const discovery = useQuery({
    queryKey: [props.kind, props.id, 'chart-wallet', props.pair],
    queryFn: ({ signal }) =>
      (props.kind === 'run' ? api.runChart : api.datasetChart)(
        props.id,
        { pair: props.pair, maxBuckets: MAX_BUCKETS, tickLimit: 5000, markerLimit: 1 },
        signal,
      ),
    enabled: props.trackedWallet === undefined,
    staleTime: Infinity,
  });
  if (props.trackedWallet === undefined && discovery.isPending)
    return (
      <ChartState>
        <LoadingState label="Loading chart…" />
      </ChartState>
    );
  if (props.trackedWallet === undefined && discovery.isError)
    return (
      <ChartState>
        <ErrorState error={discovery.error} onRetry={() => void discovery.refetch()} />
      </ChartState>
    );
  return (
    <TradeChartView
      key={`${props.kind}/${props.id}/${props.pair}`}
      {...props}
      trackedWallet={props.trackedWallet ?? discovery.data?.trackedWallet ?? null}
    />
  );
}

function focusAction(key: string | null) {
  if (!key) return;
  let marker = document.getElementById(`market-action-${encodeURIComponent(key)}`);
  if (!marker)
    marker =
      [...document.querySelectorAll<HTMLElement>('[data-market-action-keys]')].find((element) => {
        try {
          return (JSON.parse(element.dataset.marketActionKeys ?? '[]') as string[]).includes(key);
        } catch {
          return false;
        }
      }) ?? null;
  marker?.focus();
}
function filterRange(data: ChartResponse, range: MarketTimeRange | null): ChartResponse {
  if (!range) return data;
  return {
    ...data,
    range,
    ticks: {
      ...data.ticks,
      items: data.ticks.items.filter((item) => timeInRange(item.estimatedTime, range)),
    },
    candles: data.candles.filter(
      (candle) =>
        Date.parse(candle.bucketStart) + data.bucketSeconds * 1000 > Date.parse(range.from) &&
        Date.parse(candle.bucketStart) <= Date.parse(range.to),
    ),
    markers: {
      ...data.markers,
      items: data.markers.items.filter((item) => timeInRange(item.estimatedTime, range)),
      clusters: data.markers.clusters.filter(
        (item) =>
          timeInRange(item.firstTime, range) ||
          timeInRange(item.lastTime, range) ||
          (Date.parse(item.firstTime) < Date.parse(range.from) &&
            Date.parse(item.lastTime) > Date.parse(range.to)),
      ),
    },
    paperFills: data.paperFills.filter((item) => timeInRange(item.estimatedTime, range)),
  };
}

function TradeChartView({
  kind,
  id,
  pair,
  scenarios = [],
  cycle,
  trackedWallet,
  initialChart,
  onOpenAction,
  range: controlledRange,
  view: controlledView,
  onViewChange,
  onRangeChange,
  height = 420,
  expanded = false,
  onData,
  emptyActions,
}: TradeChartProps & { trackedWallet: string | null }) {
  const [params, setParams] = useSearchParams();
  const [watchlist, setWatchlist] = useState(readWatchlist);
  const [wallet, setWallet] = useState('');
  const [inputError, setInputError] = useState<string | null>(null);
  const [storageError, setStorageError] = useState(false);
  const [preset, setPreset] = useState<MarketRangePreset>('cycle');
  const [fullscreen, setFullscreen] = useState(false);
  const [tool, setTool] = useState<DrawingTool>('pointer');
  const [clearOpen, setClearOpen] = useState(false);
  const expandButton = useRef<HTMLButtonElement>(null);
  const drawingState = useChartDrawings(kind, id, pair);
  const layers = params.get('layers')?.split(',') ?? [
    'decu',
    'levels',
    'watchlist',
    ...(kind === 'run' ? ['paper'] : []),
  ];
  const allTraders = layers.includes('traders');
  const paperLayer = layers.includes('paper');
  const decuLayer = layers.includes('decu');
  const levelsLayer = layers.includes('levels');
  const watchlistLayer = layers.includes('watchlist');
  const [layersOpen, setLayersOpen] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [stack, setStack] = useState<{ actions: MarketAction[]; anchor: MarkerAnchor } | null>(
    null,
  );
  const [chart, setChart] = useState<ECharts | null>(null);
  const [positions, setPositions] = useState<Map<string, MarkerAnchor>>(new Map());
  const [fallbackAnchor, setFallbackAnchor] = useState<MarkerAnchor>({ x: 180, y: 240 });
  const [hoverTime, setHoverTime] = useState<number | null>(null);
  const chartHost = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const wallets = [
    ...new Set([...(trackedWallet ? [trackedWallet] : []), ...(watchlistLayer ? watchlist : [])]),
  ];
  const walletFilter = allTraders || wallets.length === 0 ? {} : { wallet: wallets };
  const full = useChartData(
    kind,
    id,
    { pair, ...walletFilter, maxBuckets: MAX_BUCKETS, tickLimit: 5000 },
    initialChart === undefined || allTraders || (watchlistLayer && watchlist.length > 0),
  );
  const fullData = full.data ?? initialChart;
  const extent = fullData ? chartExtent(fullData) : null;
  const cycleRange = fullData ? marketRange(fullData, 'cycle', cycle?.sourceSlot) : null;
  const chartView = useChartView({
    kind,
    extent,
    resetRange: cycleRange
      ? { from: Date.parse(cycleRange.from), to: Date.parse(cycleRange.to) }
      : extent,
  });
  const { metric, setMetric, style, setStyle } = chartView;
  const window = controlledRange
    ? { from: Date.parse(controlledRange.from), to: Date.parse(controlledRange.to) }
    : chartView.window;
  const range = window
    ? { from: new Date(window.from).toISOString(), to: new Date(window.to).toISOString() }
    : null;
  const [fetchFrom] = useDebouncedValue(range?.from, 250);
  const [fetchTo] = useDebouncedValue(range?.to, 250);
  const needsWindowData = fullData !== undefined && !fullData.ticks.included && range !== null;
  const ranged = useChartData(
    kind,
    id,
    {
      pair,
      ...walletFilter,
      maxBuckets: MAX_BUCKETS,
      tickLimit: 5000,
      ...(needsWindowData ? { from: fetchFrom, to: fetchTo } : {}),
    },
    needsWindowData && fetchFrom !== undefined && fetchTo !== undefined,
  );
  const response = needsWindowData ? (ranged.data ?? fullData) : fullData;
  const allActions: MarketAction[] = [
    ...(fullData?.markers.items.filter((item) => item.isTrackedWallet) ?? []),
    ...(kind === 'run' ? (fullData?.paperFills ?? []) : []),
  ].sort((a, b) => plotTime(a) - plotTime(b) || a.slot - b.slot);
  const selectedKey = params.get('action') ?? params.get('fill');
  const selected = allActions.find((action) => actionKey(action) === selectedKey) ?? null;
  const matchingPreset = (candidate: MarketRangePreset) => {
    if (!fullData || !extent || !window) return false;
    const candidateRange = marketRange(fullData, candidate, cycle?.sourceSlot);
    const expected = candidateRange
      ? clampRange(
          { from: Date.parse(candidateRange.from), to: Date.parse(candidateRange.to) },
          extent,
        )
      : extent;
    return expected.from === window.from && expected.to === window.to;
  };
  // A preset clamped to a short recording can equal the whole of it; that window is All unless the preset was chosen.
  const activePreset = matchingPreset(preset)
    ? preset
    : matchingPreset('all')
      ? 'all'
      : (RANGE_OPTIONS.find((item) => matchingPreset(item.value))?.value ?? null);
  useEffect(() => {
    if (response) onData?.(response, pair);
  }, [response, onData, pair]);
  const data = response ?? null;
  const inWindow = data ? filterRange(data, range) : null;
  const view = data?.valuationBasis.available ? (controlledView ?? metric) : 'price';
  const points = data ? chartMarketPoints(data, view) : [];
  const validPoints = points.filter(
    (point) =>
      point.exact !== null && (!window || (point.time >= window.from && point.time <= window.to)),
  );
  // The line steps, so the value under the cursor is the last trade's at or before it.
  const held = hoverTime === null ? null : marketPointAt(points, hoverTime);
  const hovered = held;
  const origin = fullData ? cycleStart(fullData, cycle?.sourceSlot) : null;
  const zero = origin ?? (data?.range.from ? Date.parse(data.range.from) : null);
  const span = window ? window.to - window.from : Infinity;
  const hoverLabel = (time: number) => {
    const precise = span < 120_000;
    const clock = precise ? `${new Date(time).toISOString().slice(11, 21)} UTC` : marketTime(time);
    const tick = marketPointAt(points, time)?.tick;
    return `${zero === null ? '' : `${secondsFrom(time, zero, precise)} · `}${clock}${tick ? ` · slot ${tick.slot.toLocaleString('en-US')} · #${tick.indexInSlot + 1} in slot` : ''}`;
  };
  const displayed = hovered ?? validPoints.at(-1);
  const change =
    validPoints[0]?.exact && displayed?.exact
      ? marketChange(validPoints[0].exact, displayed.exact)
      : null;
  const decuActions = allActions.filter(
    (action) => action.kind === 'OBSERVED' && timeInRange(action.estimatedTime, range),
  );
  const decuTotal = allActions.filter((action) => action.kind === 'OBSERVED').length;
  const selectedIndex = decuActions.findIndex((action) => actionKey(action) === selectedKey);
  const visible: ChartResponse | null = data
    ? {
        ...data,
        paperFills: paperLayer && kind === 'run' ? data.paperFills : [],
        markers: {
          ...data.markers,
          items: data.markers.items.filter((item) =>
            item.isTrackedWallet
              ? decuLayer
              : allTraders || (watchlistLayer && watchlist.includes(item.trader)),
          ),
          clusters:
            allTraders || (watchlistLayer && watchlist.length > 0) ? data.markers.clusters : [],
        },
      }
    : null;
  const overlayActions = visible
    ? [...visible.markers.items.filter((item) => item.isTrackedWallet), ...visible.paperFills].sort(
        (a, b) => plotTime(a) - plotTime(b) || a.slot - b.slot,
      )
    : [];
  const option = visible
    ? kind === 'run'
      ? chartTimelineOption(
          visible,
          view,
          levelsLayer ? scenarios.filter((s) => `${s.pair.base}/${s.pair.quote}` === pair) : [],
          style,
          origin,
          {
            window: window ?? undefined,
            extent: extent ?? undefined,
            fullPage: expanded || fullscreen,
            drawing: tool !== 'pointer',
          },
        )
      : chartCandleOption(visible, view, style, origin, {
          window: window ?? undefined,
          extent: extent ?? undefined,
          fullPage: expanded || fullscreen,
          drawing: tool !== 'pointer',
        })
    : {};

  const writeSelection = useCallback(
    (action: MarketAction | null) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          next.delete('action');
          next.delete('fill');
          if (action) next.set(action.kind === 'OBSERVED' ? 'action' : 'fill', actionKey(action));
          return next;
        },
        { replace: true, state: null },
      );
    },
    [setParams],
  );
  const close = useCallback(
    (returnFocus = true) => {
      writeSelection(null);
      setStack(null);
      if (returnFocus) focusAction(selectedKey);
    },
    [selectedKey, writeSelection],
  );
  const select = (action: MarketAction, anchor?: MarkerAnchor) => {
    if (anchor) setFallbackAnchor(anchor);
    setStack(null);
    writeSelection(actionKey(action) === selectedKey ? null : action);
  };
  const step = (by: number) => {
    if (!decuActions.length) return;
    const index =
      selectedIndex < 0
        ? by > 0
          ? 0
          : decuActions.length - 1
        : (selectedIndex + by + decuActions.length) % decuActions.length;
    const action = decuActions[index];
    if (action) {
      setStack(null);
      writeSelection(action);
    }
  };
  const onReady = useCallback((instance: ECharts) => setChart(instance), []);
  const onDispose = useCallback(() => setChart(null), []);
  const onPositions = useCallback((next: Map<string, MarkerAnchor>) => setPositions(next), []);

  useEffect(() => {
    const element = chartHost.current;
    if (!element) return;
    const measure = () => {
      const next = element.getBoundingClientRect().width;
      if (next > 0) setWidth(next);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [full.isPending]);
  useEffect(() => {
    if (!fullscreen) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [fullscreen]);
  useEffect(() => {
    if (!fullscreen) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || tool !== 'pointer' || clearOpen || layersOpen || infoOpen)
        return;
      event.preventDefault();
      setFullscreen(false);
      requestAnimationFrame(() => expandButton.current?.focus());
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [fullscreen, tool, clearOpen, layersOpen, infoOpen]);
  useEffect(() => {
    const host = chartHost.current;
    if (!host || !chart || expanded || fullscreen || tool !== 'pointer') return;
    const wheel = (event: WheelEvent) => {
      if (!event.metaKey || event.ctrlKey || chart.isDisposed()) return;
      const bounds = host.getBoundingClientRect();
      const pixel = [event.clientX - bounds.left, event.clientY - bounds.top];
      if (!chartContainsPoint(chart, pixel)) return;
      const value = chart.convertFromPixel({ xAxisIndex: 0, yAxisIndex: 0 }, pixel) as number[];
      event.preventDefault();
      event.stopImmediatePropagation();
      chartView.zoom(Math.exp(Math.max(-1, Math.min(1, event.deltaY * 0.002))), value[0]);
    };
    host.addEventListener('wheel', wheel, { passive: false, capture: true });
    return () => host.removeEventListener('wheel', wheel, { capture: true });
  }, [chart, expanded, fullscreen, tool, chartView]);
  useEffect(() => {
    if (!chart || chart.isDisposed()) return;
    const leave = () => setHoverTime(null);
    chart.getZr().on('globalout', leave);
    return () => {
      if (!chart.isDisposed()) chart.getZr().off('globalout', leave);
    };
  }, [chart]);
  useEffect(() => {
    if (!selected && !stack) return;
    const outside = (event: PointerEvent) => {
      if (
        event.target instanceof Element &&
        event.target.closest(
          '[data-market-action], [data-market-action-card], [data-market-control]',
        )
      )
        return;
      close(false);
    };
    const keyboard = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return;
      const typing =
        event.target instanceof HTMLTextAreaElement ||
        (event.target instanceof HTMLInputElement &&
          !['radio', 'checkbox', 'button'].includes(event.target.type));
      if (typing) return;
      if (event.key === 'Escape') {
        if (fullscreen || tool !== 'pointer') return;
        event.preventDefault();
        close();
      }
      if (
        selected &&
        (event.key === 'ArrowLeft' || event.key === 'ArrowRight') &&
        !(event.target instanceof Element && event.target.closest('[data-chart-plot]'))
      ) {
        event.preventDefault();
        step(event.key === 'ArrowRight' ? 1 : -1);
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', keyboard);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', keyboard);
    };
  });

  function updateWatchlist(next: string[]) {
    setWatchlist(next);
    try {
      localStorage.setItem(WATCHLIST_KEY, JSON.stringify(next));
      setStorageError(false);
    } catch {
      setStorageError(true);
    }
  }
  function addWallet() {
    const address = wallet.trim();
    if (!isSolanaAddress(address)) {
      setInputError('Enter a valid base58 Solana wallet address');
      return;
    }
    if (watchlist.includes(address) || address === trackedWallet) {
      setInputError('Wallet is already shown');
      return;
    }
    if (watchlist.length >= WATCHLIST_LIMIT) {
      setInputError(`At most ${WATCHLIST_LIMIT} watched wallets`);
      return;
    }
    updateWatchlist([...watchlist, address]);
    setWallet('');
    setInputError(null);
  }
  function chooseRange(next: MarketRangePreset) {
    const nextRange = fullData ? marketRange(fullData, next, cycle?.sourceSlot) : null;
    setHoverTime(null);
    setStack(null);
    setPreset(next);
    if (next === 'all') chartView.fit();
    else if (next === 'cycle') chartView.reset();
    else if (nextRange)
      chartView.setWindow({ from: Date.parse(nextRange.from), to: Date.parse(nextRange.to) });
  }
  function requestRange(from: number, to: number) {
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) return;
    const next = { from: new Date(from).toISOString(), to: new Date(to).toISOString() };
    chartView.setWindow({ from, to });
    onRangeChange?.(next);
    setStack(null);
    setHoverTime(null);
  }
  function setLayer(layer: ChartLayer, checked: boolean) {
    chartView.setLayers(
      checked
        ? [...chartView.layers.filter((item) => item !== layer), layer]
        : chartView.layers.filter((item) => item !== layer),
    );
  }

  if (full.isPending && !fullData)
    return (
      <ChartState>
        <LoadingState label="Loading chart…" />
      </ChartState>
    );
  if (full.isError)
    return (
      <ChartState>
        <ErrorState
          error={full.error}
          title="Could not load chart"
          onRetry={() => void full.refetch()}
        />
      </ChartState>
    );
  if (!data || !visible) return null;
  const basis = data.valuationBasis;
  const missing = data.candles.reduce((sum, candle) => sum + candle.mcapMissingPoints, 0);
  const unsupported = data.candles.reduce((sum, candle) => sum + candle.mcapUnsupportedPoints, 0);
  const [base = '', quote = ''] = pair.split('/');
  const anchor = selectedKey ? (positions.get(selectedKey) ?? fallbackAnchor) : fallbackAnchor;
  const hoverPixel =
    hovered?.value != null && chart && !chart.isDisposed()
      ? (chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [
          hovered.time,
          hovered.value,
        ]) as number[])
      : null;
  const selectedCycle =
    selected?.kind === 'OBSERVED'
      ? scenarios.find((item) => item.sourceSlot === selected.slot)
      : undefined;

  return (
    <Card
      component="section"
      padding="lg"
      className={`${classes.root} ${fullscreen ? classes.fullPage : ''}`}
      aria-label="Market and trades"
    >
      {fullscreen && (
        <Group justify="flex-end" mb="xs" data-market-control data-chart-exit>
          <Button
            variant="default"
            onClick={() => {
              setFullscreen(false);
              requestAnimationFrame(() => expandButton.current?.focus());
            }}
          >
            Exit full page · Esc
          </Button>
        </Group>
      )}
      <Group justify="space-between" gap="sm" wrap="wrap" mb="md">
        <Tooltip
          zIndex={1200}
          label="Market cap and recorded trades over time. Ctrl + scroll to zoom; drag the chart to pan. Price in SOL shows strategy levels."
        >
          <Title order={3} fz={15} className={classes.title}>
            Market &amp; trades ·{' '}
            <span>
              {mintLabel(base)} / {mintLabel(quote)}
            </span>
          </Title>
        </Tooltip>
        <Group gap="md" wrap="wrap" data-market-control>
          <Group gap={5} wrap="nowrap" aria-label="Decu actions stepper">
            <Text size="xs" c="dimmed">
              Decu actions
            </Text>
            <ActionIcon
              aria-label="Previous Decu action"
              variant="subtle"
              color="gray"
              disabled={!data.markers.available || !decuActions.length}
              onClick={() => step(-1)}
            >
              ‹
            </ActionIcon>
            <Text size="xs" ff="monospace" miw={48} ta="center">
              {!data.markers.available
                ? '— / —'
                : `${selectedIndex < 0 ? '–' : selectedIndex + 1} / ${decuActions.length}`}
            </Text>
            <ActionIcon
              aria-label="Next Decu action"
              variant="subtle"
              color="gray"
              disabled={!data.markers.available || !decuActions.length}
              onClick={() => step(1)}
            >
              ›
            </ActionIcon>
          </Group>
          <SegmentedControl
            size="xs"
            aria-label="Chart metric"
            value={view}
            onChange={(next) => {
              setMetric(next as 'mcap' | 'price');
              onViewChange?.(next as 'mcap' | 'price');
              setHoverTime(null);
            }}
            data={[
              { label: 'Market cap', value: 'mcap', disabled: !basis.available },
              { label: 'Price (SOL)', value: 'price' },
            ]}
          />
        </Group>
      </Group>
      <Group align="center" gap="sm">
        <Text
          className={classes.value}
          style={{ fontFamily: FONT_MONO }}
          title={displayed?.exact ?? undefined}
        >
          {marketValue(displayed?.exact ?? null, view)}
        </Text>
        <Popover
          opened={infoOpen}
          onChange={setInfoOpen}
          position="bottom-start"
          width={320}
          shadow="md"
          zIndex={1100}
        >
          <Popover.Target>
            <ActionIcon
              aria-label="Valuation information"
              variant="default"
              radius="xl"
              size="sm"
              onClick={() => {
                setInfoOpen(!infoOpen);
                setLayersOpen(false);
              }}
            >
              ⓘ
            </ActionIcon>
          </Popover.Target>
          <Popover.Dropdown data-market-control>
            <Stack gap="xs">
              <Text size="sm" fw={600}>
                {basis.label}
              </Text>
              <Text size="xs">
                Supply: {basis.supply ?? 'unavailable'} · {basis.supplySource ?? 'unavailable'}
              </Text>
              <Text size="xs">
                Rate: {basis.rateSource ?? 'unavailable'} · {basis.rateGranularity ?? 'unavailable'}
              </Text>
              <Text size="xs">
                {missing} points without a rate · {unsupported} unsupported
              </Text>
              {trackedWallet && (
                <Group gap="xs">
                  <Text size="xs">{shortId(trackedWallet)} · pinned</Text>
                  <CopyValue value={trackedWallet} label="Copy tracked wallet" />
                </Group>
              )}
            </Stack>
          </Popover.Dropdown>
        </Popover>
      </Group>
      <Group gap="xs" mb="xs" align="center" className={classes.change}>
        {change && (
          <>
            <Text
              span
              size="xs"
              className={classes.changeChip}
              style={{
                color: change.up ? terminal.gain : terminal.loss,
                background: change.up ? '#3DDC9715' : '#FF6B6B15',
              }}
            >
              {change.up ? '▲' : '▼'} {change.percent ?? '—'}%
            </Text>
            <Text size="xs" c={change.up ? terminal.gain : terminal.loss}>
              {change.up ? '+' : ''}
              {marketValue(change.absolute, view)}
            </Text>
          </>
        )}
        <Text size="xs" c="dimmed">
          {hovered
            ? `at ${hoverLabel(hovered.time)}`
            : activePreset === 'cycle'
              ? 'over this cycle'
              : activePreset && activePreset !== 'all'
                ? `over ${activePreset === '1h' ? '1H' : activePreset}`
                : 'over this range'}
        </Text>
      </Group>
      {!basis.available && (
        <Text size="xs" c={terminal.accent} mb="xs">
          Mcap unavailable: {basis.unavailableReason}. Showing Price (SOL).
        </Text>
      )}
      {ranged.isError && (
        <ErrorState
          error={ranged.error}
          title="Could not refresh chart; showing the previous view"
          onRetry={() => void ranged.refetch()}
        />
      )}
      <Group justify="space-between" gap="sm" mt="sm" wrap="wrap" data-market-control>
        {
          <Group className={classes.rangeTrack} gap={2} wrap="nowrap">
            {RANGE_OPTIONS.map((item) => (
              <button
                key={item.value}
                className={classes.rangePill}
                aria-pressed={activePreset === item.value}
                onClick={() => chooseRange(item.value)}
              >
                {item.label}
              </button>
            ))}
          </Group>
        }
        <Group gap="xs">
          <Button size="compact-xs" variant="default" onClick={() => chooseRange('all')}>
            Fit
          </Button>
          <Button size="compact-xs" variant="default" onClick={() => chooseRange('cycle')}>
            Reset
          </Button>
          <ActionIcon
            aria-label="Zoom in"
            title="Zoom in (+)"
            variant="default"
            onClick={() => chartView.zoom(0.5)}
          >
            +
          </ActionIcon>
          <ActionIcon
            aria-label="Zoom out"
            title="Zoom out (-)"
            variant="default"
            onClick={() => chartView.zoom(2)}
          >
            −
          </ActionIcon>
          {(['pointer', 'trend', 'measure'] as const).map((value) => (
            <Tooltip
              zIndex={1200}
              key={value}
              label={
                value === 'pointer' ? 'Pointer (Esc)' : value === 'trend' ? 'Trend line' : 'Measure'
              }
            >
              <ActionIcon
                aria-label={
                  value === 'pointer' ? 'Pointer' : value === 'trend' ? 'Trend line' : 'Measure'
                }
                aria-pressed={tool === value}
                variant={tool === value ? 'filled' : 'default'}
                onClick={() => setTool(value)}
              >
                {value === 'pointer' ? '↖' : value === 'trend' ? '╱' : '▧'}
              </ActionIcon>
            </Tooltip>
          ))}
          <ActionIcon
            aria-label="Clear all drawings"
            title="Clear all drawings"
            variant="default"
            disabled={!drawingState.drawings.length}
            onClick={() => setClearOpen(true)}
          >
            ⌫
          </ActionIcon>
          {!fullscreen && (
            <ActionIcon
              ref={expandButton}
              aria-label="Open full-page chart"
              title="Expand"
              variant="default"
              radius="xl"
              onClick={() => setFullscreen(true)}
            >
              ⛶
            </ActionIcon>
          )}
          <ActionIcon
            aria-label={style === 'line' ? 'Show candles' : 'Show line'}
            title={style === 'line' ? 'Show candles' : 'Show line'}
            variant="default"
            radius="xl"
            onClick={() => setStyle(style === 'line' ? 'candles' : 'line')}
          >
            {style === 'line' ? '▥' : '⌁'}
          </ActionIcon>
          <Popover
            opened={layersOpen}
            onChange={setLayersOpen}
            position="top-end"
            width={320}
            shadow="md"
            zIndex={1100}
          >
            <Popover.Target>
              <ActionIcon
                aria-label="Layers"
                title="Layers"
                variant="default"
                radius="xl"
                onClick={() => {
                  setLayersOpen(!layersOpen);
                  setInfoOpen(false);
                }}
              >
                ⋯
              </ActionIcon>
            </Popover.Target>
            <Popover.Dropdown data-market-control>
              <Stack gap="sm">
                <Checkbox
                  label="Decu actions"
                  checked={decuLayer}
                  onChange={(event) => setLayer('decu', event.currentTarget.checked)}
                />
                <Checkbox
                  label="Strategy levels"
                  checked={levelsLayer}
                  onChange={(event) => setLayer('levels', event.currentTarget.checked)}
                />
                {kind === 'run' && (
                  <Checkbox
                    label="Bot paper fills"
                    checked={paperLayer}
                    onChange={(event) => {
                      setLayer('paper', event.currentTarget.checked);
                      if (!event.currentTarget.checked && selected?.kind === 'PAPER') close(false);
                    }}
                  />
                )}
                <Checkbox
                  label="All captured traders"
                  checked={allTraders}
                  onChange={(event) => setLayer('traders', event.currentTarget.checked)}
                />
                <Checkbox
                  label="Watchlist"
                  checked={watchlistLayer}
                  onChange={(event) => setLayer('watchlist', event.currentTarget.checked)}
                />
                <Divider />
                <Text size="xs" c="dimmed">
                  Watchlist wallets · {watchlist.length} / 20
                </Text>
                <Group align="flex-start" gap="xs">
                  <TextInput
                    label="Watchlist wallet"
                    value={wallet}
                    onChange={(event) => setWallet(event.currentTarget.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') addWallet();
                    }}
                    error={inputError}
                    style={{ flex: 1 }}
                  />
                  <Button size="xs" mt={24} onClick={addWallet}>
                    Add wallet
                  </Button>
                </Group>
                {(storageError || drawingState.storageError) && (
                  <Text size="xs" role="status">
                    Browser storage unavailable; changes last for this visit.
                  </Text>
                )}
                <ScrollArea.Autosize mah={160}>
                  {watchlist.map((address) => (
                    <Group key={address} justify="space-between" gap={4} wrap="nowrap">
                      <Text size="xs" title={address}>
                        {shortId(address)}
                      </Text>
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        aria-label={`Remove ${address}`}
                        onClick={() =>
                          updateWatchlist(watchlist.filter((item) => item !== address))
                        }
                      >
                        Remove
                      </Button>
                    </Group>
                  ))}
                </ScrollArea.Autosize>
              </Stack>
            </Popover.Dropdown>
          </Popover>
        </Group>
      </Group>
      <Box
        ref={chartHost}
        className={classes.plot}
        data-chart-plot
        tabIndex={0}
        aria-label="Interactive market chart"
        style={{ height: fullscreen ? 'max(60vh, calc(100dvh - 360px))' : height }}
        aria-busy={needsWindowData && ranged.isFetching}
        onDoubleClick={(event) => {
          const rect = event.currentTarget.getBoundingClientRect();
          if (
            chart &&
            chartContainsPoint(chart, [event.clientX - rect.left, event.clientY - rect.top])
          )
            chooseRange('all');
        }}
        onKeyDown={(event) => {
          if (
            event.defaultPrevented ||
            event.target instanceof HTMLInputElement ||
            event.target instanceof HTMLTextAreaElement ||
            (event.target instanceof Element && event.target.closest('[data-market-action-card]'))
          )
            return;
          const actions: Record<string, () => void> = {
            '+': () => chartView.zoom(0.5),
            '=': () => chartView.zoom(0.5),
            '-': () => chartView.zoom(2),
            ArrowLeft: () => chartView.pan(-0.25),
            ArrowRight: () => chartView.pan(0.25),
            '0': () => chooseRange('cycle'),
            f: () => chooseRange('all'),
            F: () => chooseRange('all'),
          };
          const action = actions[event.key];
          if (action) {
            event.preventDefault();
            action();
          }
        }}
      >
        <Chart
          height="100%"
          ariaLabel="Market cap and trades over time"
          option={option}
          notMerge={false}
          replaceMerge={['series', 'grid', 'xAxis', 'yAxis']}
          onReady={onReady}
          onDispose={onDispose}
          onEvents={{
            datazoom: (event) => {
              const params = event as {
                start?: number;
                end?: number;
                startValue?: number;
                endValue?: number;
                batch?: { start?: number; end?: number; startValue?: number; endValue?: number }[];
              };
              const zoom = params.batch?.[0] ?? params;
              const axis = (Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis) as {
                min: number;
                max: number;
              };
              requestRange(
                zoom.startValue ?? axis.min + ((axis.max - axis.min) * (zoom.start ?? 0)) / 100,
                zoom.endValue ?? axis.min + ((axis.max - axis.min) * (zoom.end ?? 100)) / 100,
              );
            },
            updateAxisPointer: (event) => {
              const value = (
                event as { axesInfo?: { axisDim?: string; value?: number }[] }
              ).axesInfo?.find((item) => item.axisDim === 'x' || item.axisDim === undefined)?.value;
              if (value !== undefined && Number.isFinite(value)) setHoverTime(value);
            },
            globalout: () => setHoverTime(null),
            click: (event) => {
              const cluster = (
                event as { data?: { cluster?: { firstTime: string; lastTime: string } } }
              ).data?.cluster;
              if (cluster)
                requestRange(
                  Date.parse(cluster.firstTime),
                  Math.max(Date.parse(cluster.firstTime) + 1, Date.parse(cluster.lastTime)),
                );
            },
          }}
        />
        <MarketMarkerOverlay
          actions={overlayActions}
          view={view}
          chart={chart}
          response={data}
          selectedKey={selectedKey}
          onSelect={select}
          onStack={(actions, position) => {
            writeSelection(null);
            setStack({ actions, anchor: position });
          }}
          onPositions={onPositions}
        />
        <ChartDrawingLayer
          chart={chart}
          drawings={drawingState.drawings}
          onChange={drawingState.setDrawings}
          tool={tool}
          onToolChange={setTool}
          metric={view}
          points={points}
        />
        {emptyActions &&
          inWindow?.candles.length === 0 &&
          inWindow.ticks.items.length === 0 &&
          !ranged.isFetching && (
            <Box
              data-market-control
              style={{
                position: 'absolute',
                inset: '15% 8%',
                zIndex: 7,
                background: terminal.panel,
                overflow: 'auto',
              }}
            >
              <EmptyState
                title={`No trades between ${new Date(range?.from ?? data.range.from).toLocaleString(undefined, { hour12: false })} and ${new Date(range?.to ?? data.range.to).toLocaleString(undefined, { hour12: false })}`}
                description="Nobody traded this token in the selected range."
                action={emptyActions}
              />
            </Box>
          )}
        {hovered && hoverPixel && Number.isFinite(hoverPixel[0]) && (
          <>
            <span
              className={classes.hoverDot}
              style={{
                left: hoverPixel[0],
                top: hoverPixel[1],
                borderColor: change?.up === false ? terminal.loss : terminal.gain,
              }}
            />
            <span
              className={classes.timePill}
              style={{ left: Math.max(80, Math.min(width - 80, hoverPixel[0] ?? 0)) }}
            >
              {hoverLabel(hovered.time)}
            </span>
          </>
        )}
        {selected && (
          <MarketActionCard
            action={selected}
            response={data}
            index={Math.max(1, selectedIndex + 1)}
            total={decuActions.length}
            firstBuy={
              selectedCycle !== undefined ||
              (selected.kind === 'OBSERVED' && selected.slot === cycle?.sourceSlot)
            }
            positionClosed={
              selected.kind === 'OBSERVED' &&
              selected.side === 'SELL' &&
              cycle?.position?.state === 'CLOSED'
            }
            view={view}
            anchor={anchor}
            width={width}
            onClose={() => close()}
            onOpenDetails={
              onOpenAction
                ? () => {
                    onOpenAction(selected);
                    close(false);
                  }
                : undefined
            }
          />
        )}
        {stack && (
          <Box
            data-market-action-card
            role="dialog"
            aria-label="Stacked Decu actions"
            className={classes.stack}
            style={{
              width: Math.min(300, width - 16),
              left: Math.max(8, Math.min(width - 308, stack.anchor.x - 150)),
              top: stack.anchor.y < 230 ? stack.anchor.y + 26 : stack.anchor.y - 26,
              transform: stack.anchor.y < 230 ? undefined : 'translateY(-100%)',
            }}
          >
            <Group justify="space-between">
              <Text size="sm" fw={600}>
                {stack.actions.length} Decu actions
              </Text>
              <ActionIcon
                aria-label="Close stacked actions"
                variant="subtle"
                onClick={() => close()}
              >
                ×
              </ActionIcon>
            </Group>
            <Stack gap={4} mt="xs">
              {stack.actions.map((action) => (
                <Button
                  key={actionKey(action)}
                  variant="subtle"
                  size="xs"
                  onClick={() => select(action, stack.anchor)}
                >
                  {markerLabel(action)}
                </Button>
              ))}
              <Button
                variant="subtle"
                size="xs"
                onClick={() => {
                  const times = stack.actions.map(plotTime);
                  requestRange(Math.min(...times) - 1000, Math.max(...times) + 1000);
                }}
              >
                Zoom to these →
              </Button>
            </Stack>
          </Box>
        )}
      </Box>
      {span < 60_000 && (
        <Text size="xs" c="dimmed" mt="xs">
          {data.millisPerSlot != null
            ? `Block times are whole seconds. Inside a second, trades are placed by slot at the recorded ${data.millisPerSlot} ms per slot; trades of one slot are in their on-chain order but share one instant.`
            : 'Block times are whole seconds; positions inside a second are not known.'}
          {inWindow &&
            new Set(
              inWindow.ticks.items.filter((tick) => !tick.slotOrderProven).map((tick) => tick.slot),
            ).size > 0 &&
            ` ${new Set(inWindow.ticks.items.filter((tick) => !tick.slotOrderProven).map((tick) => tick.slot)).size} slots have no proven trade order.`}
        </Text>
      )}
      {validPoints.length < 3 && (
        <Text className={classes.status} size="xs">
          Not enough market data to draw a line in this range.
        </Text>
      )}
      {!data.markers.available && (
        <Text className={classes.status} size="xs">
          Decu's actions can't be placed: this dataset was recorded before trader data (v3).
        </Text>
      )}
      {drawingState.storageError && (
        <Text size="xs" role="status">
          Browser storage unavailable; changes last for this visit.
        </Text>
      )}
      <Modal
        data-market-control
        zIndex={1200}
        opened={clearOpen}
        onClose={() => setClearOpen(false)}
        title="Clear all drawings?"
        centered
      >
        <Text size="sm">Remove all trend lines and measurements for this pair?</Text>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={() => setClearOpen(false)}>
            Cancel
          </Button>
          <Button
            color="red"
            onClick={() => {
              drawingState.setDrawings([]);
              setClearOpen(false);
            }}
          >
            Clear all
          </Button>
        </Group>
      </Modal>
      <Group justify="space-between" gap="xs" mt="sm" className={classes.legend}>
        <Group gap="md">
          <Text size="xs">
            <span className={classes.buyLegend}>B</span> Decu buy
          </Text>
          <Text size="xs">
            <span className={classes.sellLegend}>S</span> Decu sell
          </Text>
          {paperLayer && kind === 'run' && (
            <Text size="xs">
              <span style={{ color: terminal.accent }}>◇</span> Bot paper fill
            </Text>
          )}
        </Group>
        <Text size="xs">
          {decuActions.length} Decu actions in view
          {decuTotal > decuActions.length
            ? ` · ${decuTotal - decuActions.length} outside this range`
            : ''}
        </Text>
      </Group>
      {visible.markers.clusters.length > 0 && (
        <Text size="xs" c="dimmed" mt={4}>
          Other-trader clusters show trade times; their prices are unavailable until expanded.
        </Text>
      )}
    </Card>
  );
}

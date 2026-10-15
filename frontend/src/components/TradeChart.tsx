import { ActionIcon, Box, Button, Card, Checkbox, Divider, Group, Popover, ScrollArea, SegmentedControl, Stack, Text, TextInput, Title, Tooltip } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { ECharts } from 'echarts';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/endpoints';
import { useDatasetChart, useRunChart } from '../api/queries';
import type { ChartRequest, ChartResponse, Scenario } from '../api/types';
import { actionKey, markerLabel, type MarketAction, type MarkerAnchor } from '../charts/marketMarkers';
import { marketChange, marketRange, marketTime, marketValue, timeInRange, type MarketRangePreset, type MarketTimeRange } from '../charts/marketSummary';
import { chartCandleOption, chartMarketPoints } from '../charts/priceChart';
import { readWatchlist, WATCHLIST_KEY, WATCHLIST_LIMIT } from '../charts/tradeWallets';
import { chartTimelineOption } from '../features/runs/timelineChart';
import { isSolanaAddress } from '../lib/base58';
import { mintLabel, shortId } from '../lib/format';
import { FONT_MONO, terminal } from '../theme';
import { Chart } from './Chart';
import { CopyValue } from './DisplayValues';
import { MarketActionCard } from './MarketActionCard';
import { MarketMarkerOverlay } from './MarketMarkerOverlay';
import { ErrorState, LoadingState } from './StateViews';
import classes from './TradeChart.module.css';

interface TradeChartProps {
  kind: 'run' | 'dataset';
  id: string;
  pair: string;
  trackedWallet?: string | null;
  scenarios?: Scenario[];
  cycle?: Scenario | null;
  onOpenAction?: (action: MarketAction) => void;
}
const RANGE_OPTIONS: { value: MarketRangePreset; label: string }[] = [
  { value: 'cycle', label: 'Cycle' }, { value: '5m', label: '5m' },
  { value: '15m', label: '15m' }, { value: '1h', label: '1H' }, { value: 'all', label: 'All' },
];
function useChartData(kind: TradeChartProps['kind'], id: string, request: ChartRequest, enabled = true) {
  const run = useRunChart(id, request, enabled && kind === 'run');
  const dataset = useDatasetChart(id, request, enabled && kind === 'dataset');
  return kind === 'run' ? run : dataset;
}
function ChartState({ children }: { children: React.ReactNode }) {
  return <Card component="section" padding="lg" style={{ minHeight: 600 }}>{children}</Card>;
}

export function TradeChart(props: TradeChartProps) {
  const discovery = useQuery({
    queryKey: [props.kind, props.id, 'chart-wallet', props.pair],
    queryFn: ({ signal }) => (props.kind === 'run' ? api.runChart : api.datasetChart)(props.id, { pair: props.pair, markerLimit: 1 }, signal),
    enabled: props.trackedWallet === undefined,
    staleTime: Infinity,
  });
  if (props.trackedWallet === undefined && discovery.isPending) return <ChartState><LoadingState label="Loading chart…" /></ChartState>;
  if (props.trackedWallet === undefined && discovery.isError) return <ChartState><ErrorState error={discovery.error} onRetry={() => void discovery.refetch()} /></ChartState>;
  return <TradeChartView key={`${props.kind}/${props.id}/${props.pair}`} {...props} trackedWallet={props.trackedWallet ?? discovery.data?.trackedWallet ?? null} />;
}

function focusAction(key: string | null) {
  if (!key) return;
  let marker = document.getElementById(`market-action-${encodeURIComponent(key)}`);
  if (!marker) marker = [...document.querySelectorAll<HTMLElement>('[data-market-action-keys]')].find((element) => {
    try { return (JSON.parse(element.dataset.marketActionKeys ?? '[]') as string[]).includes(key); }
    catch { return false; }
  }) ?? null;
  marker?.focus();
}
function filterRange(data: ChartResponse, range: MarketTimeRange | null): ChartResponse {
  if (!range) return data;
  return { ...data, range,
    candles: data.candles.filter((candle) => Date.parse(candle.bucketStart) + data.bucketSeconds * 1000 > Date.parse(range.from) && Date.parse(candle.bucketStart) <= Date.parse(range.to)),
    markers: { ...data.markers, items: data.markers.items.filter((item) => timeInRange(item.time, range)), clusters: data.markers.clusters.filter((item) => timeInRange(item.firstTime, range) || timeInRange(item.lastTime, range) || (Date.parse(item.firstTime) < Date.parse(range.from) && Date.parse(item.lastTime) > Date.parse(range.to))) },
    paperFills: data.paperFills.filter((item) => timeInRange(item.time, range)),
  };
}

function TradeChartView({ kind, id, pair, scenarios = [], cycle, trackedWallet, onOpenAction }: TradeChartProps & { trackedWallet: string | null }) {
  const [params, setParams] = useSearchParams();
  const [watchlist, setWatchlist] = useState(readWatchlist);
  const [wallet, setWallet] = useState('');
  const [inputError, setInputError] = useState<string | null>(null);
  const [storageError, setStorageError] = useState(false);
  const [allTraders, setAllTraders] = useState(false);
  const [paperLayer, setPaperLayer] = useState(kind === 'run');
  const [metric, setMetric] = useState<'mcap' | 'price'>('mcap');
  const [style, setStyle] = useState<'line' | 'candles'>('line');
  const [preset, setPreset] = useState<MarketRangePreset>('cycle');
  const [zoomRange, setZoomRange] = useState<MarketTimeRange | null>(null);
  const [layersOpen, setLayersOpen] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [stack, setStack] = useState<{ actions: MarketAction[]; anchor: MarkerAnchor } | null>(null);
  const [chart, setChart] = useState<ECharts | null>(null);
  const [positions, setPositions] = useState<Map<string, MarkerAnchor>>(new Map());
  const [fallbackAnchor, setFallbackAnchor] = useState<MarkerAnchor>({ x: 180, y: 240 });
  const [hoverTime, setHoverTime] = useState<number | null>(null);
  const chartHost = useRef<HTMLDivElement>(null);
  const zoomTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [width, setWidth] = useState(640);
  const wallets = [...new Set([...(trackedWallet ? [trackedWallet] : []), ...watchlist])];
  const walletFilter = allTraders || wallets.length === 0 ? {} : { wallet: wallets };
  const full = useChartData(kind, id, { pair, ...walletFilter });
  const fullData = full.data;
  const allActions: MarketAction[] = [
    ...(fullData?.markers.items.filter((item) => item.isTrackedWallet) ?? []),
    ...(kind === 'run' ? fullData?.paperFills ?? [] : []),
  ].sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.slot - b.slot);
  const selectedKey = params.get('action') ?? params.get('fill');
  const selected = allActions.find((action) => actionKey(action) === selectedKey) ?? null;
  const configuredRange = fullData ? zoomRange ?? marketRange(fullData, preset, cycle?.sourceSlot) : null;
  const widenForSelection = selected !== null && !timeInRange(selected.time, configuredRange);
  const range = widenForSelection ? null : configuredRange;
  const activePreset = widenForSelection ? 'all' : zoomRange ? null : preset;
  const ranged = useChartData(kind, id, { pair, ...walletFilter, ...range }, fullData !== undefined);
  const response = ranged.data ?? fullData;
  const data = response ? filterRange(response, range) : null;
  const view = data?.valuationBasis.available ? metric : 'price';
  const points = data ? chartMarketPoints(data, view) : [];
  const validPoints = points.filter((point) => point.exact !== null);
  const hovered = hoverTime === null || !points.length ? null : points.reduce((best, point) => Math.abs(point.time - hoverTime) < Math.abs(best.time - hoverTime) ? point : best, points[0]!);
  const displayed = hovered ?? validPoints.at(-1);
  const change = validPoints[0]?.exact && displayed?.exact ? marketChange(validPoints[0].exact, displayed.exact) : null;
  const decuActions = allActions.filter((action) => action.kind === 'OBSERVED' && timeInRange(action.time, range));
  const decuTotal = allActions.filter((action) => action.kind === 'OBSERVED').length;
  const selectedIndex = decuActions.findIndex((action) => actionKey(action) === selectedKey);
  const visible: ChartResponse | null = data ? { ...data, paperFills: paperLayer && kind === 'run' ? data.paperFills : [], markers: { ...data.markers, items: data.markers.items.filter((item) => item.isTrackedWallet || allTraders || watchlist.includes(item.trader)), clusters: allTraders || watchlist.length > 0 ? data.markers.clusters : [] } } : null;
  const overlayActions = visible ? [...visible.markers.items.filter((item) => item.isTrackedWallet), ...visible.paperFills].sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.slot - b.slot) : [];
  const option = visible ? kind === 'run' ? chartTimelineOption(visible, view, scenarios.filter((s) => `${s.pair.base}/${s.pair.quote}` === pair), style) : chartCandleOption(visible, view, style) : {};

  const writeSelection = useCallback((action: MarketAction | null) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete('action'); next.delete('fill');
      if (action) next.set(action.kind === 'OBSERVED' ? 'action' : 'fill', actionKey(action));
      return next;
    }, { replace: true, state: null });
  }, [setParams]);
  const close = useCallback((returnFocus = true) => {
    writeSelection(null); setStack(null);
    if (returnFocus) focusAction(selectedKey);
  }, [selectedKey, writeSelection]);
  const select = (action: MarketAction, anchor?: MarkerAnchor) => {
    if (anchor) setFallbackAnchor(anchor);
    setStack(null);
    writeSelection(actionKey(action) === selectedKey ? null : action);
  };
  const step = (by: number) => {
    if (!decuActions.length) return;
    const index = selectedIndex < 0 ? by > 0 ? 0 : decuActions.length - 1 : (selectedIndex + by + decuActions.length) % decuActions.length;
    const action = decuActions[index];
    if (action) { setStack(null); writeSelection(action); }
  };
  const onReady = useCallback((instance: ECharts) => setChart(instance), []);
  const onDispose = useCallback(() => setChart(null), []);
  const onPositions = useCallback((next: Map<string, MarkerAnchor>) => setPositions(next), []);

  useEffect(() => {
    const element = chartHost.current;
    if (!element) return;
    const measure = () => { const next = element.getBoundingClientRect().width; if (next > 0) setWidth(next); };
    measure(); const observer = new ResizeObserver(measure); observer.observe(element);
    return () => observer.disconnect();
  }, [full.isPending]);
  useEffect(() => () => { if (zoomTimer.current) clearTimeout(zoomTimer.current); }, []);
  useEffect(() => {
    if (!chart || chart.isDisposed()) return;
    const leave = () => setHoverTime(null);
    chart.getZr().on('globalout', leave);
    return () => { if (!chart.isDisposed()) chart.getZr().off('globalout', leave); };
  }, [chart]);
  useEffect(() => {
    if (!selected && !stack) return;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Element && event.target.closest('[data-market-action], [data-market-action-card], [data-market-control]')) return;
      close(false);
    };
    const keyboard = (event: KeyboardEvent) => {
      const typing = event.target instanceof HTMLTextAreaElement || (event.target instanceof HTMLInputElement && !['radio', 'checkbox', 'button'].includes(event.target.type));
      if (typing) return;
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      if (selected && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) { event.preventDefault(); step(event.key === 'ArrowRight' ? 1 : -1); }
    };
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', keyboard);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', keyboard); };
  });

  function updateWatchlist(next: string[]) {
    setWatchlist(next);
    try { localStorage.setItem(WATCHLIST_KEY, JSON.stringify(next)); setStorageError(false); }
    catch { setStorageError(true); }
  }
  function addWallet() {
    const address = wallet.trim();
    if (!isSolanaAddress(address)) { setInputError('Enter a valid base58 Solana wallet address'); return; }
    if (watchlist.includes(address) || address === trackedWallet) { setInputError('Wallet is already shown'); return; }
    if (watchlist.length >= WATCHLIST_LIMIT) { setInputError(`At most ${WATCHLIST_LIMIT} watched wallets`); return; }
    updateWatchlist([...watchlist, address]); setWallet(''); setInputError(null);
  }
  function chooseRange(next: MarketRangePreset) {
    if (zoomTimer.current) clearTimeout(zoomTimer.current);
    const nextRange = fullData ? marketRange(fullData, next, cycle?.sourceSlot) : null;
    if (selected && !timeInRange(selected.time, nextRange)) close(false);
    setHoverTime(null); setStack(null); setZoomRange(null); setPreset(next);
  }
  function requestRange(from: number, to: number) {
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) return;
    const next = { from: new Date(from).toISOString(), to: new Date(to).toISOString() };
    if (selected && !timeInRange(selected.time, next)) close(false);
    setStack(null); setHoverTime(null);
    if (zoomTimer.current) clearTimeout(zoomTimer.current);
    zoomTimer.current = setTimeout(() => setZoomRange(next), 300);
  }

  if (full.isPending) return <ChartState><LoadingState label="Loading chart…" /></ChartState>;
  if (full.isError) return <ChartState><ErrorState error={full.error} title="Could not load chart" onRetry={() => void full.refetch()} /></ChartState>;
  if (!data || !visible) return null;
  const basis = data.valuationBasis;
  const missing = data.candles.reduce((sum, candle) => sum + candle.mcapMissingPoints, 0);
  const unsupported = data.candles.reduce((sum, candle) => sum + candle.mcapUnsupportedPoints, 0);
  const [base = '', quote = ''] = pair.split('/');
  const anchor = selectedKey ? positions.get(selectedKey) ?? fallbackAnchor : fallbackAnchor;
  const hoverPixel = hovered?.value != null && chart && !chart.isDisposed() ? chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [hovered.time, hovered.value]) as number[] : null;
  const selectedCycle = selected?.kind === 'OBSERVED' ? scenarios.find((item) => item.sourceSlot === selected.slot) : undefined;

  return <Card component="section" padding="lg" className={classes.root} aria-label="Market and trades">
    <Group justify="space-between" gap="sm" wrap="wrap" mb="md">
      <Tooltip label="Market cap and recorded trades over time. Ctrl + scroll to zoom; drag the chart to pan. Price in SOL shows strategy levels.">
        <Title order={3} fz={15} className={classes.title}>Market &amp; trades · <span>{mintLabel(base)} / {mintLabel(quote)}</span></Title>
      </Tooltip>
      <Group gap="md" wrap="wrap" data-market-control>
        <Group gap={5} wrap="nowrap" aria-label="Decu actions stepper">
          <Text size="xs" c="dimmed">Decu actions</Text>
          <ActionIcon aria-label="Previous Decu action" variant="subtle" color="gray" disabled={!data.markers.available || !decuActions.length} onClick={() => step(-1)}>‹</ActionIcon>
          <Text size="xs" ff="monospace" miw={48} ta="center">{!data.markers.available ? '— / —' : `${selectedIndex < 0 ? '–' : selectedIndex + 1} / ${decuActions.length}`}</Text>
          <ActionIcon aria-label="Next Decu action" variant="subtle" color="gray" disabled={!data.markers.available || !decuActions.length} onClick={() => step(1)}>›</ActionIcon>
        </Group>
        <SegmentedControl size="xs" aria-label="Chart metric" value={view} onChange={(next) => { setMetric(next as 'mcap' | 'price'); setHoverTime(null); }} data={[{ label: 'Mcap', value: 'mcap', disabled: !basis.available }, { label: 'Price (SOL)', value: 'price' }]} />
      </Group>
    </Group>
    <Group align="center" gap="sm">
      <Text className={classes.value} style={{ fontFamily: FONT_MONO }} title={displayed?.exact ?? undefined}>{marketValue(displayed?.exact ?? null, view)}</Text>
      <Popover opened={infoOpen} onChange={setInfoOpen} position="bottom-start" width={320} shadow="md">
        <Popover.Target><ActionIcon aria-label="Valuation information" variant="default" radius="xl" size="sm" onClick={() => { setInfoOpen(!infoOpen); setLayersOpen(false); }}>ⓘ</ActionIcon></Popover.Target>
        <Popover.Dropdown><Stack gap="xs"><Text size="sm" fw={600}>{basis.label}</Text><Text size="xs">Supply: {basis.supply ?? 'unavailable'} · {basis.supplySource ?? 'unavailable'}</Text><Text size="xs">Rate: {basis.rateSource ?? 'unavailable'} · {basis.rateGranularity ?? 'unavailable'}</Text><Text size="xs">{missing} points without a rate · {unsupported} unsupported</Text>{trackedWallet && <Group gap="xs"><Text size="xs">{shortId(trackedWallet)} · pinned</Text><CopyValue value={trackedWallet} label="Copy tracked wallet" /></Group>}</Stack></Popover.Dropdown>
      </Popover>
    </Group>
    <Group gap="xs" mb="xs" align="center" className={classes.change}>
      {change && <><Text span size="xs" className={classes.changeChip} style={{ color: change.up ? terminal.gain : terminal.loss, background: change.up ? '#3DDC9715' : '#FF6B6B15' }}>{change.up ? '▲' : '▼'} {change.percent ?? '—'}%</Text><Text size="xs" c={change.up ? terminal.gain : terminal.loss}>{change.up ? '+' : ''}{marketValue(change.absolute, view)}</Text></>}
      <Text size="xs" c="dimmed">{hovered ? `at ${marketTime(hovered.time)}` : activePreset === 'cycle' ? 'over this cycle' : activePreset && activePreset !== 'all' ? `over ${activePreset === '1h' ? '1H' : activePreset}` : 'over this range'}</Text>
    </Group>
    {!basis.available && <Text size="xs" c={terminal.accent} mb="xs">Mcap unavailable: {basis.unavailableReason}. Showing Price (SOL).</Text>}
    {ranged.isError ? <Box mih={420}><ErrorState error={ranged.error} title="Could not load chart" onRetry={() => void ranged.refetch()} /></Box> : <Box ref={chartHost} className={classes.plot} aria-busy={ranged.isFetching}>
      <Chart height={420} ariaLabel="Market cap and trades over time" option={option} onReady={onReady} onDispose={onDispose} onEvents={{
        datazoom: (event) => {
          const params = event as { start?: number; end?: number; startValue?: number; endValue?: number; batch?: { start?: number; end?: number; startValue?: number; endValue?: number }[] };
          const zoom = params.batch?.[0] ?? params;
          const axis = (Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis) as { min: number; max: number };
          requestRange(zoom.startValue ?? axis.min + (axis.max - axis.min) * (zoom.start ?? 0) / 100, zoom.endValue ?? axis.min + (axis.max - axis.min) * (zoom.end ?? 100) / 100);
        },
        updateAxisPointer: (event) => { const value = (event as { axesInfo?: { axisDim?: string; value?: number }[] }).axesInfo?.find((item) => item.axisDim === 'x' || item.axisDim === undefined)?.value; if (value !== undefined && Number.isFinite(value)) setHoverTime(value); },
        globalout: () => setHoverTime(null),
        click: (event) => { const cluster = (event as { data?: { cluster?: { firstTime: string; lastTime: string } } }).data?.cluster; if (cluster) requestRange(Date.parse(cluster.firstTime), Math.max(Date.parse(cluster.firstTime) + 1, Date.parse(cluster.lastTime))); },
      }} />
      <MarketMarkerOverlay actions={overlayActions} view={view} chart={chart} selectedKey={selectedKey} onSelect={select} onStack={(actions, position) => { writeSelection(null); setStack({ actions, anchor: position }); }} onPositions={onPositions} />
      {hovered && hoverPixel && Number.isFinite(hoverPixel[0]) && <><span className={classes.hoverDot} style={{ left: hoverPixel[0], top: hoverPixel[1], borderColor: change?.up === false ? terminal.loss : terminal.gain }} /><span className={classes.timePill} style={{ left: Math.max(55, Math.min(width - 110, hoverPixel[0] ?? 0)) }}>{marketTime(hovered.time)}</span></>}
      {selected && <MarketActionCard action={selected} index={Math.max(1, selectedIndex + 1)} total={decuActions.length} firstBuy={selectedCycle !== undefined || selected.kind === 'OBSERVED' && selected.slot === cycle?.sourceSlot} positionClosed={selected.kind === 'OBSERVED' && selected.side === 'SELL' && cycle?.position?.state === 'CLOSED'} view={view} anchor={anchor} width={width} onClose={() => close()} onOpenDetails={onOpenAction ? () => { onOpenAction(selected); close(false); } : undefined} />}
      {stack && <Box data-market-action-card role="dialog" aria-label="Stacked Decu actions" className={classes.stack} style={{ width: Math.min(300, width - 16), left: Math.max(8, Math.min(width - 308, stack.anchor.x - 150)), top: stack.anchor.y < 230 ? stack.anchor.y + 26 : stack.anchor.y - 26, transform: stack.anchor.y < 230 ? undefined : 'translateY(-100%)' }}><Group justify="space-between"><Text size="sm" fw={600}>{stack.actions.length} Decu actions</Text><ActionIcon aria-label="Close stacked actions" variant="subtle" onClick={() => close()}>×</ActionIcon></Group><Stack gap={4} mt="xs">{stack.actions.map((action) => <Button key={actionKey(action)} variant="subtle" size="xs" onClick={() => select(action, stack.anchor)}>{markerLabel(action)}</Button>)}<Button variant="subtle" size="xs" onClick={() => { const times = stack.actions.map((action) => Date.parse(action.time)); requestRange(Math.min(...times) - 1000, Math.max(...times) + 1000); }}>Zoom to these →</Button></Stack></Box>}
    </Box>}
    {validPoints.length < 3 && <Text className={classes.status} size="xs">Not enough market data to draw a line in this range.</Text>}
    {!data.markers.available && <Text className={classes.status} size="xs">Decu's actions can't be placed: this dataset was recorded before trader data (v3).</Text>}
    <Group justify="space-between" gap="sm" mt="sm" wrap="wrap" data-market-control>
      <Group className={classes.rangeTrack} gap={2} wrap="nowrap">{RANGE_OPTIONS.map((item) => <button key={item.value} className={classes.rangePill} aria-pressed={activePreset === item.value} onClick={() => chooseRange(item.value)}>{item.label}</button>)}{zoomRange && <button className={classes.rangePill} onClick={() => chooseRange('cycle')}>Reset zoom</button>}</Group>
      <Group gap="xs"><ActionIcon aria-label={style === 'line' ? 'Show candles' : 'Show line'} title={style === 'line' ? 'Show candles' : 'Show line'} variant="default" radius="xl" onClick={() => setStyle(style === 'line' ? 'candles' : 'line')}>{style === 'line' ? '▥' : '⌁'}</ActionIcon>
        <Popover opened={layersOpen} onChange={setLayersOpen} position="top-end" width={320} shadow="md"><Popover.Target><ActionIcon aria-label="Layers" title="Layers" variant="default" radius="xl" onClick={() => { setLayersOpen(!layersOpen); setInfoOpen(false); }}>⋯</ActionIcon></Popover.Target><Popover.Dropdown><Stack gap="sm"><Checkbox label="Decu actions" checked disabled description="always on" />{kind === 'run' && <Checkbox label="Bot paper fills" checked={paperLayer} onChange={(event) => { setPaperLayer(event.currentTarget.checked); if (!event.currentTarget.checked && selected?.kind === 'PAPER') close(false); }} />}<Checkbox label="All captured traders" checked={allTraders} onChange={(event) => setAllTraders(event.currentTarget.checked)} /><Divider /><Text size="xs" c="dimmed">Watchlist wallets · {watchlist.length} / 20</Text><Group align="flex-start" gap="xs"><TextInput label="Watchlist wallet" value={wallet} onChange={(event) => setWallet(event.currentTarget.value)} onKeyDown={(event) => { if (event.key === 'Enter') addWallet(); }} error={inputError} style={{ flex: 1 }} /><Button size="xs" mt={24} onClick={addWallet}>Add wallet</Button></Group>{storageError && <Text size="xs" role="status">Browser storage unavailable; changes last for this visit.</Text>}<ScrollArea.Autosize mah={160}>{watchlist.map((address) => <Group key={address} justify="space-between" gap={4} wrap="nowrap"><Text size="xs" title={address}>{shortId(address)}</Text><Button size="compact-xs" variant="subtle" aria-label={`Remove ${address}`} onClick={() => updateWatchlist(watchlist.filter((item) => item !== address))}>Remove</Button></Group>)}</ScrollArea.Autosize></Stack></Popover.Dropdown></Popover>
      </Group>
    </Group>
    <Group justify="space-between" gap="xs" mt="sm" className={classes.legend}><Group gap="md"><Text size="xs"><span className={classes.buyLegend}>B</span> Decu buy</Text><Text size="xs"><span className={classes.sellLegend}>S</span> Decu sell</Text>{paperLayer && kind === 'run' && <Text size="xs"><span style={{ color: terminal.info }}>◇</span> Bot paper fill</Text>}</Group><Text size="xs">{decuActions.length} Decu actions in view{decuTotal > decuActions.length ? ` · ${decuTotal - decuActions.length} outside this range` : ''}</Text></Group>
    {visible.markers.clusters.length > 0 && <Text size="xs" c="dimmed" mt={4}>Other-trader clusters show trade times; their prices are unavailable until expanded.</Text>}
  </Card>;
}

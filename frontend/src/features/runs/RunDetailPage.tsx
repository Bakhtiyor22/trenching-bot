import {
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Group,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useHotkeys } from '@mantine/hooks';
import { useState, type ReactNode } from 'react';
import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import { api, pairKey } from '../../api/endpoints';
import {
  useRun,
  useRunEvents,
  useRunObservations,
  useRunOrders,
  useRunPositions,
  useRunUsdt,
  useSolUsdtPrice,
} from '../../api/queries';
import type { RunDetail, RunEvent, RunUsdtView, Scenario } from '../../api/types';
import { Chart } from '../../components/Chart';
import { CopyValue, Decimal, Mint, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { IconCompare, IconDownload, IconPlay } from '../../components/Icons';
import { MarketLinks } from '../../components/MarketLinks';
import { ObservationTable } from '../../components/ObservationTable';
import { KeyValueTable, Kpi, KPI_VALUE_SIZE, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { formatCount, formatSlot, humanizeCode, mintLabel, shortId, WRAPPED_SOL_MINT } from '../../lib/format';
import { recordedEventSignature, tokenLinks, transactionLinks } from '../../lib/marketLinks';
import { paths } from '../../paths';
import type { RunStartNavigationState } from './NewRunPage';
import { EventsTable, OrdersTable, PositionsTable } from './RunTables';
import { bpsLabel, horizonMinutes, latencyLabel, transactionCostLabel } from './configLabels';
import { CycleList, CycleStory, OutcomeBar, RunLanes } from './RunInspector';
import { ScenarioCard } from './ScenarioCard';
import { quoteValues, timelineOption, usdtValues } from './timelineChart';

type RecordTab = 'events' | 'observations' | 'orders' | 'positions';

function RunHeader({ detail }: { detail: RunDetail }) {
  const { run, config } = detail;
  return (
    <SimpleGrid cols={{ base: 1, lg: 2 }}>
      <Section title="Run">
        <KeyValueTable
          rows={[
            ['Run id', <ShortId key="id" value={run.id} />],
            [
              'Dataset',
              <Group key="ds" gap={4}>
                <Anchor component={Link} to={paths.dataset(run.datasetId)} ff="monospace" size="sm">
                  {shortId(run.datasetId)}
                </Anchor>
                <CopyValue value={run.datasetId} label="Copy dataset id" />
              </Group>,
            ],
            ['Engine version', <Badge key="ev" variant="light" color="gray" tt="none">{run.engineVersion}</Badge>],
            ['Configuration hash', <ShortId key="ch" value={run.configurationHash} />],
            ['Result checksum', <ShortId key="rc" value={run.resultChecksum} />],
            ['Quote mint', <Mint key="qm" mint={run.quoteMint} />],
            ['Created', <Timestamp key="ca" iso={run.createdAt} />],
          ]}
        />
      </Section>
      <Section title="Configuration used">
        <KeyValueTable
          rows={[
            ['Strategy', `${config.strategyName} v${config.strategyVersion}`],
            ['Notional', <Decimal key="n" value={config.notional} />],
            ['Dip below first buy', bpsLabel(config.dipBps)],
            ['Minimum market cap', `$${formatDecimal(config.minMarketCapUsd)}`],
            ['Take profit', bpsLabel(config.takeProfitBps)],
            ['Stop loss', bpsLabel(config.stopLossBps)],
            ['Horizon', `${formatSlot(config.horizonSlots)} slots (about ${horizonMinutes(config.horizonSlots)} min)`],
            ['Latency', latencyLabel(config.latencySlots)],
            ['Transaction cost', transactionCostLabel(config.transactionCostSol)],
            ['Max observation age', `${formatSlot(config.maxAgeSlots)} slots`],
            ['Quote mint', <Mint key="q" mint={config.quoteMint} />],
          ]}
        />
      </Section>
    </SimpleGrid>
  );
}

/** Open positions counted by why they never exited, e.g. "1 migrated · 1 horizon reached"; migrated tokens stay apart. */
function openReasons(scenarios: Scenario[]): string | undefined {
  const counts = new Map<string, number>();
  for (const s of scenarios) {
    if (s.position?.state === 'OPEN' && s.exitUnavailableReason) {
      counts.set(s.exitUnavailableReason, (counts.get(s.exitUnavailableReason) ?? 0) + 1);
    }
  }
  if (counts.size === 0) return undefined;
  return [...counts.entries()].map(([reason, count]) => `${count} ${humanizeCode(reason).toLowerCase()}`).join(' · ');
}

function RunTotals({ detail, usdt }: { detail: RunDetail; usdt: RunUsdtView | null }) {
  const { run } = detail;
  const reasons = openReasons(detail.scenarios);
  return (
    <KpiStrip label="Outcome">
      {usdt ? (
        <Kpi label="Realized P&L (USDT)" hint="Net proceeds at each exit minute's rate minus cost at each entry minute's rate">
          {usdt.realizedPnlUsdt == null ? <Text c="dimmed">— {usdt.realizedPnlUnavailableReason}</Text> : <Pnl value={usdt.realizedPnlUsdt} fz={KPI_VALUE_SIZE} fw={500} />}
        </Kpi>
      ) : (
        <Kpi label={`Realized P&L (${mintLabel(run.quoteMint)})`} hint="Exact sum over closed positions">
          <Pnl value={run.realizedPnl} fz={KPI_VALUE_SIZE} fw={500} />
        </Kpi>
      )}
      <Kpi label="Outcomes">
        <OutcomeBar scenarios={detail.scenarios} />
      </Kpi>
      <Kpi label="Tokens · entered / no entry">
        <Text ff="monospace" fz={KPI_VALUE_SIZE} fw={500}>
          {formatCount(run.scenarioCount)}{' '}
          <Text span c="dimmed" inherit>
            ·
          </Text>{' '}
          <Text span c="teal" inherit>
            {formatCount(run.filledCount)}
          </Text>
          {' / '}
          <Text span c={run.rejectedCount > 0 ? 'orange' : undefined} inherit>
            {formatCount(run.rejectedCount)}
          </Text>
        </Text>
      </Kpi>
      <Kpi label="Open / closed" hint={reasons && `Open: ${reasons}`}>
        <Text ff="monospace" fz={KPI_VALUE_SIZE} fw={500}>
          {formatCount(run.openCount)} / {formatCount(run.closedCount)}
        </Text>
      </Kpi>
    </KpiStrip>
  );
}

/** Price by slot for the selected cycle's pair, with its levels, entry and exit. */
function Timeline({ runId, scenarios, pair, unit, usdt }: { runId: string; scenarios: Scenario[]; pair: string; unit: string; usdt: RunUsdtView | null }) {
  const observations = useRunObservations(runId);
  const pairScenarios = scenarios.filter((s) => pairKey(s.pair) === pair);
  const pairObservations = (observations.data ?? []).filter((o) => pairKey(o.pair) === pair);
  const [base = '', quote = ''] = pair.split('/');

  return (
    <Section
      title={
        <>
          Price path ·{' '}
          <Text span ff="monospace" inherit>
            {mintLabel(base)} / {mintLabel(quote)}
          </Text>
        </>
      }
      hint="Observed price by slot. Dotted lines: the first buy's price P0, the dip level, take profit and stop loss. Triangles: the entry fill and the exit. Ctrl + scroll or drag the slider to zoom; drag the chart to pan."
    >
      {observations.isPending ? (
        <LoadingState label="Loading observations…" />
      ) : observations.isError ? (
        <ErrorState error={observations.error} onRetry={() => void observations.refetch()} />
      ) : (
        <Chart
          option={timelineOption(pairObservations, pairScenarios, usdt ? usdtValues(usdt) : quoteValues(unit))}
          height={380}
          ariaLabel="Price by slot with the first buy, dip, take-profit and stop-loss levels, entry and exit markers"
        />
      )}
    </Section>
  );
}

/** The recorded first buy behind a cycle, with its transaction and token links; nothing for other sources. */
function SourceBuyLinks({ event }: { event: RunEvent | undefined }) {
  const signature = event ? recordedEventSignature(event) : null;
  if (!event || !signature) return null;
  return (
    <Group gap="md">
      <Group gap={6}>
        <Text size="xs" c="dimmed">First buy</Text>
        <MarketLinks links={transactionLinks(signature)} />
      </Group>
      <Group gap={6}>
        <Text size="xs" c="dimmed">Token {shortId(event.pair.base)}</Text>
        <MarketLinks links={tokenLinks(event.pair.base)} />
      </Group>
    </Group>
  );
}

/** Every cycle's full card, in first-buy order; the inspected one is outlined. */
function Scenarios({ runId, scenarios, selectedId, unit, usdt }: { runId: string; scenarios: Scenario[]; selectedId: string | null; unit: string; usdt: RunUsdtView | null }) {
  const events = useRunEvents(runId, scenarios.length > 0);
  if (scenarios.length === 0) return null;
  const eventsById = new Map((events.data ?? []).map((e) => [e.eventId, e]));
  const usdtById = new Map((usdt?.scenarios ?? []).map((s) => [s.intentId, s]));
  return (
    <Stack gap="md">
      <Stack gap={2}>
        <Title order={3}>Cycle details</Title>
        <Text size="sm" c="dimmed">
          One per token, from the wallet's first buy of it. Later buys of the same token start no cycle.
        </Text>
      </Stack>
      <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }}>
        {scenarios.map((s) => (
          <Stack key={s.intentId} gap="xs">
            <SourceBuyLinks event={eventsById.get(s.sourceEventId)} />
            <ScenarioCard scenario={s} unit={unit} usdt={usdt ? usdtById.get(s.intentId) : undefined} selected={s.intentId === selectedId} />
          </Stack>
        ))}
      </SimpleGrid>
    </Stack>
  );
}

/** Cycles in the order their first buys happened. */
function byFirstBuy(scenarios: Scenario[]): Scenario[] {
  return [...scenarios].sort((a, b) => a.sourceSlot - b.sourceSlot);
}

function RecordTabs({ runId, usdt }: { runId: string; usdt: RunUsdtView | null }) {
  const [tab, setTab] = useState<RecordTab>('events');
  const events = useRunEvents(runId, tab === 'events');
  const observations = useRunObservations(runId, tab === 'observations');
  const orders = useRunOrders(runId, tab === 'orders');
  const positions = useRunPositions(runId, tab === 'positions');

  const panel = (query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }, content: () => ReactNode) =>
    query.isPending ? (
      <LoadingState />
    ) : query.isError ? (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    ) : (
      content()
    );

  return (
    <Section title="Stored records">
      <Tabs value={tab} onChange={(value) => value && setTab(value as RecordTab)} keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="events">Events</Tabs.Tab>
          <Tabs.Tab value="observations">Observations</Tabs.Tab>
          <Tabs.Tab value="orders">Orders &amp; fills</Tabs.Tab>
          <Tabs.Tab value="positions">Positions</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="events">{panel(events, () => <EventsTable events={events.data ?? []} />)}</Tabs.Panel>
        <Tabs.Panel value="observations">
          {panel(observations, () => <ObservationTable observations={observations.data ?? []} usdt={usdt?.observations} />)}
        </Tabs.Panel>
        <Tabs.Panel value="orders">{panel(orders, () => <OrdersTable orders={orders.data ?? []} />)}</Tabs.Panel>
        <Tabs.Panel value="positions">
          {panel(positions, () => <PositionsTable positions={positions.data ?? []} />)}
        </Tabs.Panel>
      </Tabs>
    </Section>
  );
}

/** SOL/USDT switch for a wrapped-SOL run, kept in the URL (`?quote=usdt`) so a link reopens the same view. */
function useQuoteCurrency() {
  const [params, setParams] = useSearchParams();
  const usdt = params.get(QUOTE_PARAM) === USDT;
  const setUsdt = (next: boolean) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next) updated.set(QUOTE_PARAM, USDT);
        else updated.delete(QUOTE_PARAM);
        return updated;
      },
      { replace: true, state: null },
    );
  return [usdt, setUsdt] as const;
}

const QUOTE_PARAM = 'quote';
const USDT = 'usdt';

function SolPrice() {
  const price = useSolUsdtPrice(true);
  if (!price.data) return null;
  const { rate, unavailableReason } = price.data;
  return (
    <Badge variant="light" color="gray" tt="none" size="lg" title="Binance SOLUSDT, refreshed every 30 seconds">
      {rate ? `1 SOL = ${rate} USDT` : `SOL price unavailable: ${unavailableReason}`}
    </Badge>
  );
}

export function RunDetailPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const navigationState = location.state as RunStartNavigationState | null;
  const run = useRun(id);
  const [usdtSelected, setUsdtSelected] = useQuoteCurrency();
  const solQuoted = run.data?.run.quoteMint === WRAPPED_SOL_MINT;
  const usdtQuery = useRunUsdt(id, solQuoted && usdtSelected);
  const [selected, setSelected] = useState(0);
  const cycles = run.data ? byFirstBuy(run.data.scenarios) : [];
  const step = (by: number) => {
    if (cycles.length > 0) setSelected((current) => (current + by + cycles.length) % cycles.length);
  };

  useHotkeys([
    ['j', () => step(1)],
    ['k', () => step(-1)],
    ['u', () => solQuoted && setUsdtSelected(!usdtSelected)],
  ]);

  if (run.isPending) return <LoadingState label="Loading run…" />;
  if (run.isError) return <ErrorState error={run.error} title="Could not load the run" onRetry={() => void run.refetch()} />;

  const detail = run.data;
  const unit = mintLabel(detail.run.quoteMint);
  const usdtView = solQuoted && usdtSelected ? usdtQuery.data ?? null : null;
  const usdt = usdtView && usdtView.unavailableReason == null ? usdtView : null;
  const current = cycles[Math.min(selected, cycles.length - 1)] ?? null;
  return (
    <Stack gap="lg">
      <PageHeader
        title={
          <Group gap="xs">
            Run <Text span ff="monospace" inherit>{shortId(detail.run.id)}</Text>
            <CopyValue value={detail.run.id} label="Copy run id" />
          </Group>
        }
        description="A persisted, immutable replay run. Pick a cycle to follow it on the chart; J and K step through them."
        actions={
          <>
            {solQuoted && (
              <>
                <SolPrice />
                <SegmentedControl
                  aria-label="Show values in"
                  value={usdtSelected ? 'USDT' : 'SOL'}
                  onChange={(value) => setUsdtSelected(value === 'USDT')}
                  data={['SOL', 'USDT']}
                />
              </>
            )}
            <Button component={Link} to={paths.compareRuns([detail.run.id])} variant="default" leftSection={<IconCompare size={16} />}>
              Compare with…
            </Button>
            <Button component={Link} to={paths.newRunFor(detail.run.datasetId)} variant="default" leftSection={<IconPlay size={16} />}>
              New run on this dataset
            </Button>
            <Button
              component="a"
              href={api.runReportUrl(detail.run.id)}
              download={`run-${detail.run.id}.json`}
              leftSection={<IconDownload size={16} />}
            >
              Download report
            </Button>
          </>
        }
      />
      {navigationState?.created === false && (
        <Alert color="blue" variant="light" title="Run already existed">
          This exact run already existed — identical dataset, config and engine produce the same run id.
        </Alert>
      )}
      {usdtView?.unavailableReason && (
        <Alert color="yellow" variant="light" title="USDT view unavailable">
          Values stay in {unit}: {usdtView.unavailableReason}
        </Alert>
      )}
      {usdtQuery.isError && usdtSelected && (
        <Alert color="yellow" variant="light" title="USDT view unavailable">
          Values stay in {unit}.
        </Alert>
      )}
      {usdt && (
        <Text size="xs" c="dimmed">
          USDT values are a display conversion at each trade's own minute (Binance SOLUSDT 1-minute open). Moves within a
          minute are not resolved. The simulation, its checksums and its P&L stay in {unit}.
        </Text>
      )}
      <RunTotals detail={detail} usdt={usdt} />
      {current ? (
        <>
          <Box style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--mantine-spacing-lg)', alignItems: 'flex-start' }}>
            <Box style={{ flex: '1 1 300px', minWidth: 0 }}>
              <CycleList scenarios={cycles} selected={selected} onSelect={setSelected} unit={unit} />
            </Box>
            <Stack gap="lg" style={{ flex: '3 1 560px', minWidth: 0 }}>
              <Timeline runId={detail.run.id} scenarios={detail.scenarios} pair={pairKey(current.pair)} unit={unit} usdt={usdt} />
              <CycleStory scenario={current} unit={unit} />
            </Stack>
          </Box>
          <RunLanes scenarios={cycles} selected={selected} onSelect={setSelected} unit={unit} />
        </>
      ) : (
        <Section title="Price path">
          <EmptyState title="No cycles" description="The dataset had no confirmed buys against the quote mint." />
        </Section>
      )}
      <Scenarios runId={detail.run.id} scenarios={cycles} selectedId={current?.intentId ?? null} unit={unit} usdt={usdt} />
      <RunHeader detail={detail} />
      <RecordTabs runId={detail.run.id} usdt={usdt} />
    </Stack>
  );
}

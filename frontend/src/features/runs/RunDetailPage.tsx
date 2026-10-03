import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useState, type ReactNode } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { api, pairKey } from '../../api/endpoints';
import {
  useRun,
  useRunEvents,
  useRunObservations,
  useRunOrders,
  useRunPositions,
} from '../../api/queries';
import type { RunDetail, Scenario } from '../../api/types';
import { Chart } from '../../components/Chart';
import { CopyValue, Decimal, Mint, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { IconDownload, IconPlay } from '../../components/Icons';
import { ObservationTable } from '../../components/ObservationTable';
import { KeyValueTable, PageHeader, Section, Stat } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, formatSlot, mintLabel, shortId } from '../../lib/format';
import { paths } from '../../paths';
import type { RunStartNavigationState } from './NewRunPage';
import { EventsTable, OrdersTable, PositionsTable } from './RunTables';
import { ScenarioCard } from './ScenarioCard';
import { scenarioPairs, timelineOption } from './timelineChart';

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
            ['Fee', `${config.feeBps} bps`],
            ['Slippage', `${config.slippageBps} bps`],
            ['Max wait', `${formatSlot(config.maxWaitSlots)} slots`],
            ['Max observation age', `${formatSlot(config.maxAgeSlots)} slots`],
            ['Holding period', `${formatSlot(config.holdingSlots)} slots`],
            ['Quote mint', <Mint key="q" mint={config.quoteMint} />],
          ]}
        />
      </Section>
    </SimpleGrid>
  );
}

function RunTotals({ detail }: { detail: RunDetail }) {
  const { run } = detail;
  return (
    <Section title="Outcome">
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        <Stat label="Scenarios" value={<Text fw={700} size="xl">{formatCount(run.scenarioCount)}</Text>} />
        <Stat
          label="Filled / rejected"
          value={
            <Text fw={700} size="xl">
              <Text span c="teal" inherit>{formatCount(run.filledCount)}</Text>
              {' / '}
              <Text span c={run.rejectedCount > 0 ? 'orange' : undefined} inherit>{formatCount(run.rejectedCount)}</Text>
            </Text>
          }
        />
        <Stat
          label="Open / closed"
          value={<Text fw={700} size="xl">{formatCount(run.openCount)} / {formatCount(run.closedCount)}</Text>}
        />
        <Stat
          label={`Realized P&L (${mintLabel(run.quoteMint)})`}
          value={<Pnl value={run.realizedPnl} size="xl" />}
          hint="Exact sum over closed positions"
        />
      </SimpleGrid>
    </Section>
  );
}

function Timeline({ runId, scenarios }: { runId: string; scenarios: Scenario[] }) {
  const observations = useRunObservations(runId);
  const pairs = scenarioPairs(scenarios);
  const [selected, setSelected] = useState<string | null>(null);
  const pair = selected ?? pairs[0] ?? null;

  if (scenarios.length === 0) {
    return (
      <Section title="Price timeline">
        <EmptyState title="No scenarios" description="The dataset had no confirmed buys against the quote mint." />
      </Section>
    );
  }
  const pairScenarios = scenarios.filter((s) => pairKey(s.pair) === pair);
  const pairObservations = (observations.data ?? []).filter((o) => pairKey(o.pair) === pair);

  return (
    <Section
      title="Price timeline"
      hint="Observed price by slot. Gray line: source buy. Green triangles: entry fills (+1/+2/+3) at their execution price. Orange triangles: exits. Dashed red lines: rejected entry targets. Scroll or drag the slider to zoom."
      actions={
        pairs.length > 1 && (
          <Select
            aria-label="Pair"
            data={pairs.map((p) => {
              const [base = '', quote = ''] = p.split('/');
              return { value: p, label: `${mintLabel(base)} / ${mintLabel(quote)}` };
            })}
            value={pair}
            onChange={setSelected}
            allowDeselect={false}
            w={240}
          />
        )
      }
    >
      {observations.isPending ? (
        <LoadingState label="Loading observations…" />
      ) : observations.isError ? (
        <ErrorState error={observations.error} onRetry={() => void observations.refetch()} />
      ) : (
        <Chart
          option={timelineOption(pairObservations, pairScenarios)}
          height={380}
          ariaLabel="Price by slot with source buy, entry fill and exit markers"
        />
      )}
    </Section>
  );
}

function Scenarios({ scenarios }: { scenarios: Scenario[] }) {
  if (scenarios.length === 0) return null;
  const bySource = new Map<string, Scenario[]>();
  for (const s of scenarios) {
    bySource.set(s.sourceEventId, [...(bySource.get(s.sourceEventId) ?? []), s]);
  }
  return (
    <Stack gap="md">
      <Title order={3}>Scenarios</Title>
      {[...bySource.entries()].map(([sourceEventId, group]) => (
        <Stack key={sourceEventId} gap="xs">
          {bySource.size > 1 && (
            <Text size="sm" c="dimmed">
              Source buy at slot {formatSlot(group[0]?.sourceSlot)} · event{' '}
              <Text span ff="monospace" inherit>{sourceEventId}</Text>
            </Text>
          )}
          <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }}>
            {[...group]
              .sort((a, b) => a.entryOffset - b.entryOffset)
              .map((s) => (
                <ScenarioCard key={s.intentId} scenario={s} />
              ))}
          </SimpleGrid>
        </Stack>
      ))}
    </Stack>
  );
}

function RecordTabs({ runId }: { runId: string }) {
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
          {panel(observations, () => <ObservationTable observations={observations.data ?? []} />)}
        </Tabs.Panel>
        <Tabs.Panel value="orders">{panel(orders, () => <OrdersTable orders={orders.data ?? []} />)}</Tabs.Panel>
        <Tabs.Panel value="positions">
          {panel(positions, () => <PositionsTable positions={positions.data ?? []} />)}
        </Tabs.Panel>
      </Tabs>
    </Section>
  );
}

export function RunDetailPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const navigationState = location.state as RunStartNavigationState | null;
  const run = useRun(id);

  if (run.isPending) return <LoadingState label="Loading run…" />;
  if (run.isError) return <ErrorState error={run.error} title="Could not load the run" onRetry={() => void run.refetch()} />;

  const detail = run.data;
  return (
    <Stack gap="lg">
      <PageHeader
        title={
          <Group gap="xs">
            Run <Text span ff="monospace" inherit>{shortId(detail.run.id)}</Text>
            <CopyValue value={detail.run.id} label="Copy run id" />
          </Group>
        }
        description="A persisted, immutable replay run: header, configuration, timeline, scenario results and every stored record."
        actions={
          <>
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
      <RunTotals detail={detail} />
      <RunHeader detail={detail} />
      <Timeline runId={detail.run.id} scenarios={detail.scenarios} />
      <Scenarios scenarios={detail.scenarios} />
      <RecordTabs runId={detail.run.id} />
    </Stack>
  );
}

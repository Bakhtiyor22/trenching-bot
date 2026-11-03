import {
  Alert,
  Button,
  Group,
  Modal,
  ScrollArea,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import type { EChartsOption } from 'echarts';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import {
  ACTIVE_BACKFILL_STATES,
  ITEM_STATES,
  useBackfill,
  useCancelBackfill,
  type AutomationBackfill,
  type AutomationStatus,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { useChartTokens } from '../../charts/chartTokens';
import { Chart } from '../../components/Chart';
import { Mint } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState, ErrorState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { formatCount } from '../../lib/format';
import { paths } from '../../paths';
import {
  BACKFILL_LIMITS,
  backfillDays,
  backfillKind,
  captureBudgetFor,
  dayInfo,
  dayRange,
  lamportsToSol,
  zoneDateTime,
} from './automationModel';
import { AutomationFrame, BuyStateBar, BuyStateLegend } from './AutomationShared';
import { BackfillStateChip, BudgetMeter, ScanProgress } from './BackfillShared';

const NOT_FOUND = 'AUTOMATION_BACKFILL_NOT_FOUND';
/** Bar labels above each day only while they fit. */
const MAX_LABELLED_DAYS = 14;

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.code === NOT_FOUND;
}

function stoppable(backfill: AutomationBackfill): boolean {
  return ACTIVE_BACKFILL_STATES.includes(backfill.state) || backfill.state === 'STOPPED_BUDGET';
}

/** Cancel after confirmation. The dialog stays open while the request is sending, and opens without an old error. */
function CancelBackfill({ backfill }: { backfill: AutomationBackfill }) {
  const [opened, { open, close }] = useDisclosure(false);
  const cancel = useCancelBackfill(backfill.id);
  const sending = cancel.isPending;
  if (!stoppable(backfill)) return null;
  const waiting = ITEM_STATES.filter((s) =>
    ['DISCOVERED', 'WAITING', 'BLOCKED_BUDGET', 'CAPTURING', 'CAPTURED'].includes(s),
  ).reduce((sum, s) => sum + (backfill.itemCounts[s] ?? 0), 0);
  const dismiss = () => {
    if (!sending) close();
  };
  return (
    <>
      <Button
        variant="outline"
        color="red"
        onClick={() => {
          cancel.reset();
          open();
        }}
      >
        Cancel backfill
      </Button>
      <Modal
        opened={opened}
        onClose={dismiss}
        closeOnClickOutside={!sending}
        closeOnEscape={!sending}
        withCloseButton={!sending}
        title="Cancel this backfill?"
        centered
      >
        <Stack gap="md">
          <Text size="sm">
            Its scan stops.{' '}
            {waiting > 0
              ? `${waiting} unfinished ${waiting === 1 ? 'buy becomes' : 'buys become'} Abandoned and ${waiting === 1 ? 'is' : 'are'} not scored.`
              : 'No buy is waiting, so nothing is abandoned.'}{' '}
            Buys already replayed are kept. A later backfill can cover these days again.
          </Text>
          {cancel.isError && <ErrorState error={cancel.error} title="Cancel failed" />}
          <Group justify="flex-end">
            <Button variant="default" onClick={dismiss} disabled={sending}>
              Keep running
            </Button>
            <Button
              color="red"
              loading={sending}
              onClick={() => cancel.mutate(undefined, { onSuccess: close })}
            >
              Cancel backfill
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function DryRunResult({ backfill }: { backfill: AutomationBackfill }) {
  const tokens = useChartTokens();
  const [view, setView] = useState<'chart' | 'table'>('chart');
  const summary = backfill.summary;
  if (!summary)
    return (
      <Text size="sm" c="dimmed">
        The estimate appears when the scan reaches the first day.
      </Text>
    );
  const days = dayRange(backfill.fromDay, backfill.toDay);
  const counts = days.map((d) => summary.buysPerDay[d] ?? 0);
  const option: EChartsOption = {
    animation: false,
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${formatCount(Number(v))} buys` },
    xAxis: { type: 'category', data: days.map((d) => dayInfo(d).short) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'bar',
        name: 'Buys passing the filter',
        data: counts,
        barMaxWidth: 36,
        itemStyle: { color: tokens.info, borderRadius: [4, 4, 0, 0] },
        label: { show: days.length <= MAX_LABELLED_DAYS, position: 'top', color: tokens.dimmed },
      },
    ],
  };
  const budget = captureBudgetFor(backfill.estimatedRequests);
  const captureLink = paths.automationBackfillsPrefilled({
    from: backfill.fromDay,
    to: backfill.toDay,
    dryRun: 'false',
    budget: String(budget),
    ...(backfill.minSignalLamports ? { min: lamportsToSol(backfill.minSignalLamports) } : {}),
  });
  return (
    <Stack gap="md">
      <KpiStrip label="Dry-run result" min={150}>
        <Kpi label="Buys passing" hint={`of ${formatCount(backfill.buysFound)} found`}>
          {formatCount(backfill.buysPassing)}
        </Kpi>
        <Kpi label="Distinct tokens" hint="each needs one capture">
          {formatCount(summary.distinctMints)}
        </Kpi>
        <Kpi label="Estimate" hint="requests to capture them">
          {formatCount(backfill.estimatedRequests)}
        </Kpi>
        <Kpi label="Spent" hint="discovery requests">
          {formatCount(backfill.requestsSpent)}
        </Kpi>
      </KpiStrip>
      <Group justify="space-between" wrap="wrap" gap="xs">
        <Text size="sm" fw={600}>
          Buys passing the filter, per trading day
        </Text>
        <SegmentedControl
          size="xs"
          aria-label="Dry-run view"
          value={view}
          onChange={(v) => setView(v as 'chart' | 'table')}
          data={[
            { value: 'chart', label: 'Chart' },
            { value: 'table', label: 'Table' },
          ]}
        />
      </Group>
      {view === 'table' ? (
        <ScrollArea type="auto" mah={320}>
          <Table verticalSpacing="xs" aria-label="Buys passing the filter per day">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Day</Table.Th>
                <Table.Th ta="right">Buys</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {days.map((d, i) => (
                <Table.Tr key={d}>
                  <Table.Td>{dayInfo(d).label}</Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatCount(counts[i])}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      ) : (
        <Chart
          ariaLabel={`Buys passing the filter per trading day, ${dayInfo(backfill.fromDay).short} to ${dayInfo(backfill.toDay).short}: ${counts.join(', ')}.`}
          height={200}
          option={option}
        />
      )}
      <Alert color="gray" variant="light" title="Capture these days?">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">
            {backfill.estimatedRequests == null
              ? `No buy has been captured yet, so there is no estimate. A capture needs a budget of at least ${formatCount(BACKFILL_LIMITS.minCaptureBudget)} requests.`
              : `Capturing these ${formatCount(summary.distinctMints)} tokens would take about ${formatCount(backfill.estimatedRequests)} requests (the median of the captures so far).`}{' '}
            The form opens with dry run off and a budget of {formatCount(budget)}.
          </Text>
          <Button component={Link} to={captureLink} size="xs">
            Capture this range…
          </Button>
        </Stack>
      </Alert>
    </Stack>
  );
}

function Detail({ backfill, status }: { backfill: AutomationBackfill; status: AutomationStatus }) {
  return (
    <Stack gap="lg">
      {backfill.filtered && (
        <Alert color="orange" variant="light" title="Filtered backfill">
          It captures only{' '}
          {backfill.mints.length > 0
            ? `the buys of ${backfill.mints.length} ${backfill.mints.length === 1 ? 'token' : 'tokens'}`
            : `buys above ${formatDecimal(lamportsToSol(backfill.minSignalLamports ?? '0'))} SOL`}
          , so its days do not count as covered and it does not count toward day verdicts.
        </Alert>
      )}
      {backfill.state === 'STOPPED_BUDGET' && (
        <Alert color="red" variant="light" title="Stopped: the request budget ran out">
          Buys it could not capture end Failed, and their days stay Pending until a later backfill
          fills them.
        </Alert>
      )}
      <Section title="Progress">
        <SimpleGrid cols={{ base: 1, md: 3 }} spacing="lg">
          <Stack gap={6}>
            <span className="console-label">Scan · newest first</span>
            <ScanProgress backfill={backfill} zone={status.zone} />
          </Stack>
          <Stack gap={6}>
            <span className="console-label">Budget</span>
            <BudgetMeter backfill={backfill} />
          </Stack>
          {!backfill.dryRun && (
            <Stack gap={6}>
              <span className="console-label">Buys</span>
              <BuyStateBar counts={backfill.itemCounts} captureEnabled={status.captureEnabled} />
              <BuyStateLegend captureEnabled={status.captureEnabled} />
            </Stack>
          )}
        </SimpleGrid>
        <Group gap="lg" wrap="wrap" mt="md">
          {(
            [
              ['actions scanned', backfill.actionsScanned],
              ['buys found', backfill.buysFound],
              ['pass the filter', backfill.buysPassing],
              ...(backfill.dryRun ? [] : [['items created', backfill.itemsCreated] as const]),
              ['skipped by the token list', backfill.skippedByFilter],
              ['without a block time', backfill.skippedNoBlockTime],
            ] as Array<readonly [string, number]>
          ).map(([label, value]) => (
            <Text size="sm" key={label}>
              <Text span ff="monospace" fw={600}>
                {formatCount(value)}
              </Text>{' '}
              <Text span c="dimmed">
                {label}
              </Text>
            </Text>
          ))}
        </Group>
      </Section>
      {backfill.dryRun && (
        <Section title="Dry-run result">
          <DryRunResult backfill={backfill} />
        </Section>
      )}
      {(backfill.mints.length > 0 || backfill.minSignalLamports) && (
        <Section title="Filters">
          <Stack gap="xs">
            {backfill.minSignalLamports && (
              <Text size="sm">
                Minimum signal size: more than{' '}
                <strong>{formatDecimal(lamportsToSol(backfill.minSignalLamports))} SOL</strong>,
                fees included
              </Text>
            )}
            {backfill.mints.length > 0 && (
              <Group gap="sm" wrap="wrap">
                {backfill.mints.map((m) => (
                  <Mint key={m} mint={m} />
                ))}
              </Group>
            )}
          </Stack>
        </Section>
      )}
    </Stack>
  );
}

function headerDescription(backfill: AutomationBackfill, status: AutomationStatus) {
  const zone = status.zone;
  return [
    `${zoneDateTime(backfill.rangeStart, zone)} → ${zoneDateTime(backfill.rangeEnd, zone)}`,
    `started ${zoneDateTime(backfill.createdAt, zone)}`,
    backfill.finishedAt ? `ended ${zoneDateTime(backfill.finishedAt, zone)}` : null,
    ACTIVE_BACKFILL_STATES.includes(backfill.state) ? 'refreshes every 15 s' : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

export function BackfillDetailPage() {
  const { id = '' } = useParams();
  const backfill = useBackfill(id);
  const data = backfill.data;
  const days = data ? backfillDays(data) : 'Backfill';
  return (
    <AutomationFrame
      title={data ? `${backfillKind(data)} · ${days}` : 'Backfill'}
      breadcrumbs={[
        { label: 'Automation', to: paths.automation },
        { label: 'Backfills', to: paths.automationBackfills },
        { label: days },
      ]}
      status={data ? <BackfillStateChip state={data.state} /> : null}
      description={(status) => (data ? headerDescription(data, status) : null)}
      actions={
        data ? (
          <>
            <Button
              component={Link}
              to={paths.automationDaysBetween(data.fromDay, data.toDay)}
              variant="default"
            >
              Open these days
            </Button>
            <CancelBackfill backfill={data} />
          </>
        ) : null
      }
    >
      {(status) =>
        data === undefined && isNotFound(backfill.error) ? (
          <EmptyState title="Backfill not found" />
        ) : (
          <QueryView
            query={backfill}
            loadingLabel="Loading the backfill…"
            errorTitle="Backfill unavailable"
          >
            {(b) => <Detail backfill={b} status={status} />}
          </QueryView>
        )
      }
    </AutomationFrame>
  );
}

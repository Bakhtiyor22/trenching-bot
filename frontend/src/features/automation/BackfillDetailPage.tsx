import {
  Alert,
  Anchor,
  Breadcrumbs,
  Button,
  Card,
  Group,
  Modal,
  ScrollArea,
  SimpleGrid,
  Stack,
  Table,
  Text,
  useComputedColorScheme,
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
import { Chart } from '../../components/Chart';
import { Mint } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import {
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

const BAR_COLOR = { dark: '#3F8FE8', light: '#1C7ED6' } as const;
const AXIS_COLOR = { dark: '#93A1AD', light: '#5C6670' } as const;

function CancelBackfill({ backfill }: { backfill: AutomationBackfill }) {
  const [opened, { open, close }] = useDisclosure(false);
  const cancel = useCancelBackfill(backfill.id);
  const stoppable =
    ACTIVE_BACKFILL_STATES.includes(backfill.state) || backfill.state === 'STOPPED_BUDGET';
  if (!stoppable) return null;
  const waiting = ITEM_STATES.filter((s) =>
    ['DISCOVERED', 'WAITING', 'BLOCKED_BUDGET', 'CAPTURING', 'CAPTURED'].includes(s),
  ).reduce((sum, s) => sum + (backfill.itemCounts[s] ?? 0), 0);
  return (
    <>
      <Button variant="default" color="red" onClick={open}>
        Cancel backfill
      </Button>
      <Modal opened={opened} onClose={close} title="Cancel this backfill?" centered>
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
            <Button variant="default" onClick={close} disabled={cancel.isPending}>
              Keep running
            </Button>
            <Button
              color="red"
              loading={cancel.isPending}
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
  const scheme = useComputedColorScheme('dark');
  const [tableView, setTableView] = useState(false);
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
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${String(v)} buys` },
    xAxis: {
      type: 'category',
      data: days.map((d) => dayInfo(d).short),
      axisLabel: { color: AXIS_COLOR[scheme] },
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: AXIS_COLOR[scheme] } },
    series: [
      {
        type: 'bar',
        name: 'Buys passing the filter',
        data: counts,
        barMaxWidth: 36,
        itemStyle: { color: BAR_COLOR[scheme], borderRadius: [4, 4, 0, 0] },
        label: { show: days.length <= 14, position: 'top', color: AXIS_COLOR[scheme] },
      },
    ],
  };
  const budget = captureBudgetFor(backfill.estimatedRequests);
  const captureLink = `${paths.automationBackfills}?${new URLSearchParams({
    from: backfill.fromDay,
    to: backfill.toDay,
    dryRun: 'false',
    budget: String(budget),
    ...(backfill.minSignalLamports ? { min: lamportsToSol(backfill.minSignalLamports) } : {}),
  }).toString()}`;
  return (
    <Stack gap="md">
      <KpiStrip label="Dry-run result" min={150}>
        <Kpi label="Buys passing" hint={`of ${backfill.buysFound} found`}>
          <Text fz="xl" fw={600}>
            {backfill.buysPassing}
          </Text>
        </Kpi>
        <Kpi label="Distinct tokens" hint="each needs one capture">
          <Text fz="xl" fw={600}>
            {summary.distinctMints}
          </Text>
        </Kpi>
        <Kpi label="Estimate" hint="requests to capture them">
          <Text fz="xl" fw={600} ff="monospace">
            {backfill.estimatedRequests == null
              ? '—'
              : backfill.estimatedRequests.toLocaleString('en-US')}
          </Text>
        </Kpi>
        <Kpi label="Spent" hint="discovery requests">
          <Text fz="xl" fw={600} ff="monospace">
            {backfill.requestsSpent.toLocaleString('en-US')}
          </Text>
        </Kpi>
      </KpiStrip>
      <Group justify="space-between" wrap="wrap" gap="xs">
        <Text size="sm" fw={600}>
          Buys passing the filter, per trading day
        </Text>
        <Button
          size="xs"
          variant="default"
          onClick={() => setTableView((v) => !v)}
          aria-pressed={tableView}
        >
          {tableView ? 'Chart view' : 'Table view'}
        </Button>
      </Group>
      {tableView ? (
        <ScrollArea type="auto" mah={320}>
          <Table aria-label="Buys passing the filter per day">
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
                    {counts[i]}
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
      <Alert color="amber" variant="light" title="Capture these days?">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">
            {backfill.estimatedRequests == null
              ? 'No buy has been captured yet, so there is no estimate. A capture needs a budget of at least 1,222 requests.'
              : `Capturing these ${summary.distinctMints} tokens would take about ${backfill.estimatedRequests.toLocaleString('en-US')} requests (the median of the captures so far).`}{' '}
            The form opens with dry run off and a budget of {budget.toLocaleString('en-US')}.
          </Text>
          <Button component={Link} to={captureLink} variant="light" size="xs">
            Capture this range…
          </Button>
        </Stack>
      </Alert>
    </Stack>
  );
}

function Detail({ backfill, status }: { backfill: AutomationBackfill; status: AutomationStatus }) {
  const active = ACTIVE_BACKFILL_STATES.includes(backfill.state);
  return (
    <Stack gap="lg">
      <Card padding="md">
        <Group justify="space-between" wrap="wrap" gap="md">
          <Stack gap={4}>
            <Group gap="sm">
              <Text fz="xl" fw={700} component="h2" m={0}>
                {backfillKind(backfill)} · {backfillDays(backfill)}
              </Text>
              <BackfillStateChip state={backfill.state} />
            </Group>
            <Text size="sm" c="dimmed">
              {zoneDateTime(backfill.rangeStart, status.zone)} →{' '}
              {zoneDateTime(backfill.rangeEnd, status.zone)}
              {' · started '}
              {zoneDateTime(backfill.createdAt, status.zone)}
              {backfill.finishedAt && ` · ended ${zoneDateTime(backfill.finishedAt, status.zone)}`}
              {active && ' · refreshes every 15 s'}
            </Text>
          </Stack>
          <Group gap="xs">
            <Button
              component={Link}
              to={`${paths.automationDays}?range=custom&from=${backfill.fromDay}&to=${backfill.toDay}`}
              variant="default"
            >
              Open these days
            </Button>
            <CancelBackfill backfill={backfill} />
          </Group>
        </Group>
      </Card>
      {backfill.filtered && (
        <Alert color="amber" variant="light" title="Filtered backfill">
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
            <Text size="xs" c="dimmed" tt="uppercase" fw={500} style={{ letterSpacing: '0.08em' }}>
              Scan · newest first
            </Text>
            <ScanProgress backfill={backfill} zone={status.zone} />
          </Stack>
          <Stack gap={6}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={500} style={{ letterSpacing: '0.08em' }}>
              Budget
            </Text>
            <BudgetMeter backfill={backfill} />
          </Stack>
          {!backfill.dryRun && (
            <Stack gap={6}>
              <Text
                size="xs"
                c="dimmed"
                tt="uppercase"
                fw={500}
                style={{ letterSpacing: '0.08em' }}
              >
                Buys
              </Text>
              <BuyStateBar counts={backfill.itemCounts} captureEnabled={status.captureEnabled} />
              <BuyStateLegend captureEnabled={status.captureEnabled} />
            </Stack>
          )}
        </SimpleGrid>
        <Group gap="lg" wrap="wrap" mt="md">
          {[
            ['actions scanned', backfill.actionsScanned],
            ['buys found', backfill.buysFound],
            ['pass the filter', backfill.buysPassing],
            ...(backfill.dryRun ? [] : [['items created', backfill.itemsCreated] as const]),
            ['skipped by the token list', backfill.skippedByFilter],
            ['without a block time', backfill.skippedNoBlockTime],
          ].map(([label, value]) => (
            <Text size="sm" key={String(label)}>
              <Text span ff="monospace" fw={600}>
                {Number(value).toLocaleString('en-US')}
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

export function BackfillDetailPage() {
  const { id = '' } = useParams();
  const backfill = useBackfill(id);
  return (
    <AutomationFrame description="">
      {(status) => (
        <Stack gap="lg">
          <Breadcrumbs separator="›">
            <Anchor component={Link} to={paths.automation} size="sm">
              Automation
            </Anchor>
            <Anchor component={Link} to={paths.automationBackfills} size="sm">
              Backfills
            </Anchor>
            <Text size="sm">{backfill.data ? backfillDays(backfill.data) : 'Backfill'}</Text>
          </Breadcrumbs>
          {backfill.isPending ? (
            <LoadingState label="Loading the backfill…" />
          ) : backfill.isError ? (
            backfill.error instanceof ApiError &&
            backfill.error.code === 'AUTOMATION_BACKFILL_NOT_FOUND' ? (
              <EmptyState title="Backfill not found" />
            ) : (
              <ErrorState error={backfill.error} onRetry={() => void backfill.refetch()} />
            )
          ) : (
            <Detail backfill={backfill.data} status={status} />
          )}
        </Stack>
      )}
    </AutomationFrame>
  );
}

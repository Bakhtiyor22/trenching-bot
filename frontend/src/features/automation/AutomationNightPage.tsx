import {
  Anchor,
  Badge,
  Box,
  Breadcrumbs,
  Button,
  Card,
  Group,
  Modal,
  ScrollArea,
  Stack,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { Link, useParams, useSearchParams } from 'react-router';
import {
  STEPS,
  useAutomationItem,
  useAutomationSession,
  useCloseSession,
  type AutomationItem,
  type AutomationSessionDetail,
  type AutomationStatus,
  type AutomationStep,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { Mint, ShortId } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import { reasonText } from '../captures/captureReasons';
import {
  bucketOf,
  stepMarks,
  type StepMark,
  buyBuckets,
  compareWorstDesc,
  itemStateLabels,
  lamportsToSol,
  nightTitle,
  outcomeExplanation,
  outcomeLabel,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
} from './automationModel';
import { AutomationFrame, SolValue, StatusAlerts, StatusChip, ZoneTime } from './AutomationShared';
import { Scoreboard } from './Scoreboard';

const STEP_NAMES: Record<AutomationStep, string> = {
  CREATE_SESSION: 'session',
  WALLET: 'wallet',
  MARKET: 'market',
  RATES: 'rates',
  EXPORT: 'export',
  QUALIFY: 'qualify',
  REGISTER: 'register',
  SWEEP: 'sweep',
};
const STEP_COLOR: Record<StepMark, string | undefined> = {
  done: 'var(--automation-replayed)',
  current: 'var(--automation-progress)',
  failed: 'var(--automation-issues)',
  skipped: 'var(--automation-skipped)',
  pending: undefined,
};
const MAX_COMPARED = 3;

function StepStrip({ item }: { item: AutomationItem }) {
  const marks = stepMarks(item);
  const where = item.step ? STEP_NAMES[item.step] : 'not started';
  return (
    <Tooltip label={STEPS.map((s, i) => `${STEP_NAMES[s]}: ${marks[i]}`).join(' · ')}>
      <Group
        gap={3}
        wrap="nowrap"
        component="ol"
        aria-label={`Pipeline: ${itemStateLabels[item.state].toLowerCase()} at ${where}`}
        style={{ listStyle: 'none', margin: 0, padding: 0 }}
      >
        {marks.map((mark, i) => (
          <Box
            component="li"
            key={STEPS[i]}
            w={12}
            h={12}
            style={{
              borderRadius: '50%',
              boxSizing: 'border-box',
              background: STEP_COLOR[mark],
              border:
                mark === 'pending' ? '1.5px solid var(--mantine-color-default-border)' : undefined,
            }}
          />
        ))}
      </Group>
    </Tooltip>
  );
}

const STATE_COLOR: Record<ReturnType<typeof bucketOf>, string> = {
  replayed: 'blue',
  inProgress: 'amber',
  issues: 'red',
  skipped: 'gray',
};

function ItemNote({ item, zone }: { item: AutomationItem; zone: string }) {
  if (item.state === 'SIMULATED') return <>Every variation replayed</>;
  if (item.state === 'SKIPPED_NOT_FIRST_BUY') return <>Decu held this token before; never scored</>;
  if (item.state === 'BLOCKED_BUDGET') return <>Waiting for the 24 h request cap to free up</>;
  if (item.state === 'FAILED' || item.state === 'ABANDONED') {
    const reason = item.failureCode ? (reasonText(item.failureCode) ?? item.failureCode) : null;
    return (
      <>
        {item.step ? `${STEP_NAMES[item.step]} step` : 'Pipeline'}
        {item.stepAttempt > 1 ? ` (attempt ${item.stepAttempt})` : ''}:{' '}
        {item.failureMessage ?? reason ?? 'stopped'}
      </>
    );
  }
  if (item.state === 'DISCOVERED') return <>Found; not captured</>;
  return (
    <>
      {item.step ? `At the ${STEP_NAMES[item.step]} step` : 'Waiting'}
      {item.nextActionAt ? `, next try ${zoneTime(item.nextActionAt, zone)}` : ''}
    </>
  );
}

function CloseNight({ detail }: { detail: AutomationSessionDetail }) {
  const [opened, { open, close }] = useDisclosure(false);
  const mutation = useCloseSession(detail.session.id);
  const unfinished = detail.items.filter(
    (i) => !['SIMULATED', 'FAILED', 'ABANDONED', 'SKIPPED_NOT_FIRST_BUY'].includes(i.state),
  ).length;
  if (detail.session.state === 'CLOSED') {
    return (
      <Tooltip label="Only an open night can be closed">
        <Button variant="default" disabled>
          Close night now
        </Button>
      </Tooltip>
    );
  }
  return (
    <>
      <Button variant="default" color="red" onClick={open}>
        Close night now
      </Button>
      <Modal opened={opened} onClose={close} title="Close this night now?" centered>
        <Stack gap="md">
          <Text size="sm">
            The night ends now instead of 60 minutes after the last buy.{' '}
            {unfinished > 0
              ? `${unfinished} unfinished ${unfinished === 1 ? 'buy becomes' : 'buys become'} Abandoned and ${unfinished === 1 ? 'is' : 'are'} not scored.`
              : 'Every buy has finished, so nothing is abandoned.'}{' '}
            Buys found later start a new night.
          </Text>
          {mutation.isError && <ErrorState error={mutation.error} title="Close failed" />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close} disabled={mutation.isPending}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={mutation.isPending}
              onClick={() => mutation.mutate(undefined, { onSuccess: close })}
            >
              Close night
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function SelectedBuy({ itemId, status }: { itemId: string; status: AutomationStatus }) {
  const detail = useAutomationItem(itemId);
  if (detail.isPending) return <LoadingState label="Loading the buy…" />;
  if (detail.isError) return <ErrorState error={detail.error} title="Buy unavailable" />;
  const { item, sweepRuns } = detail.data;
  const runs = [...sweepRuns].sort((a, b) => a.label.localeCompare(b.label));
  const comparable = [...sweepRuns.filter((r) => r.runId)].sort(compareWorstDesc);
  const baseline = comparable.find((r) => r.label === 'V00');
  const compared = [baseline, ...comparable.filter((r) => r !== baseline)]
    .filter((r): r is NonNullable<typeof r> => r != null)
    .slice(0, MAX_COMPARED)
    .map((r) => r.runId!);
  return (
    <Section
      title={
        <>
          Selected buy · {zoneTime(item.blockTime, status.zone, true)} · <Mint mint={item.mint} /> ·{' '}
          {formatDecimal(lamportsToSol(item.curvePaymentLamports))} SOL
        </>
      }
      hint={[
        itemStateLabels[item.state],
        item.classification
          ? `verdict ${item.classification.toLowerCase().replaceAll('_', ' ')}`
          : null,
        item.requestsUsed != null ? `${item.requestsUsed} requests` : null,
        `slot ${item.slot.toLocaleString('en-US')}`,
      ]
        .filter(Boolean)
        .join(' · ')}
      actions={
        <Group gap="xs" wrap="wrap">
          {item.captureSessionId && (
            <Button
              component={Link}
              to={paths.capture(item.captureSessionId)}
              variant="default"
              size="xs"
            >
              Open capture
            </Button>
          )}
          {item.datasetId && (
            <Button component={Link} to={paths.dataset(item.datasetId)} variant="default" size="xs">
              Open dataset
            </Button>
          )}
          {compared.length >= 2 && (
            <Button component={Link} to={paths.compareRuns(compared)} variant="default" size="xs">
              Compare {compared.length} runs
            </Button>
          )}
        </Group>
      }
    >
      <Stack gap="sm">
        <Group gap="lg" wrap="wrap">
          <Text size="sm">
            Signature <ShortId value={item.signature} />
          </Text>
          <Text size="sm">
            Block time <ZoneTime iso={item.blockTime} zone={status.zone} withDate />{' '}
            {zoneLabel(status.zone)}
          </Text>
          <Text size="sm" c="dimmed">
            Paid to the curve, fees excluded
          </Text>
        </Group>
        {runs.length === 0 ? (
          <Text size="sm" c="dimmed">
            Not replayed yet
            {item.state === 'SKIPPED_NOT_FIRST_BUY'
              ? ': this was not Decu’s first buy of the token, so it is never replayed.'
              : '.'}
          </Text>
        ) : (
          <ScrollArea type="auto">
            <Table miw={820} aria-label="Sweep runs of this buy">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Variation</Table.Th>
                  <Table.Th>What happened</Table.Th>
                  <Table.Th>Counts as a signal</Table.Th>
                  <Table.Th ta="right">Realized</Table.Th>
                  <Table.Th ta="right">Worst case</Table.Th>
                  <Table.Th>Run</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {runs.map((r) => (
                  <Table.Tr key={r.label}>
                    <Table.Th scope="row" fw={400}>
                      <Text span ff="monospace" fw={600} size="sm">
                        {r.label}
                      </Text>{' '}
                      <Text span size="xs" c="dimmed">
                        {variationChange(r.planId, r.label)}
                      </Text>
                    </Table.Th>
                    <Table.Td>
                      <Tooltip label={outcomeExplanation(r.outcome) ?? r.outcome} multiline w={320}>
                        <Text span size="sm">
                          {outcomeLabel(r.outcome)}
                        </Text>
                      </Tooltip>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c={r.signal ? undefined : 'dimmed'}>
                        {r.signal
                          ? Number(r.realizedPnl) === 0 && Number(r.worstCasePnl) === 0
                            ? 'Yes, 0 P&L'
                            : 'Yes'
                          : 'No'}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">
                      <SolValue value={r.realizedPnl} />
                    </Table.Td>
                    <Table.Td ta="right">
                      <SolValue value={r.worstCasePnl} strong />
                    </Table.Td>
                    <Table.Td>
                      {r.runId ? (
                        <Anchor component={Link} to={paths.run(r.runId)} size="sm" ff="monospace">
                          {r.runId.slice(0, 12)}…
                        </Anchor>
                      ) : (
                        <Text size="sm" c="dimmed">
                          no run
                        </Text>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )}
      </Stack>
    </Section>
  );
}

function Night({ detail, status }: { detail: AutomationSessionDetail; status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const { session, items, evaluations } = detail;
  const buckets = buyBuckets(session.itemCounts);
  const requested = params.get('item');
  const selectedId =
    items.find((i) => i.id === requested)?.id ??
    items.find((i) => i.state === 'SIMULATED')?.id ??
    items[0]?.id ??
    null;
  const select = (id: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('item', id);
        return next;
      },
      { replace: true },
    );
  const best = evaluations[0];
  const unscored = buckets.issues;
  return (
    <Stack gap="lg">
      <KpiStrip label="Night totals" min={170}>
        <Kpi
          label="Buys"
          hint={[
            buckets.replayed && `${buckets.replayed} replayed`,
            buckets.inProgress && `${buckets.inProgress} in progress`,
            buckets.skipped && `${buckets.skipped} not first`,
            buckets.issues && `${buckets.issues} failed or abandoned`,
          ]
            .filter(Boolean)
            .join(' · ')}
        >
          <Text fz="xl" fw={600}>
            {items.length}
          </Text>
        </Kpi>
        <Kpi label="Below the filter" hint="buys too small to capture, fees included">
          <Text fz="xl" fw={600}>
            {session.belowFilterCount}
          </Text>
        </Kpi>
        <Kpi label="Best variation" hint="over the 7 nights ending here">
          {best ? (
            <Group gap={6}>
              <StatusChip status={best.status} />
              <Text ff="monospace" fw={600}>
                {best.label}
              </Text>
            </Group>
          ) : (
            <Text c="dimmed">Not scored yet</Text>
          )}
        </Kpi>
        <Kpi label="Unscored" hint="failed or abandoned buys, kept out of P&L">
          <Text fz="xl" fw={600}>
            {unscored}
          </Text>
        </Kpi>
        <Kpi label="Requests" hint="provider calls for these captures">
          <Text fz="xl" fw={600} ff="monospace">
            {session.requestsUsed.toLocaleString('en-US')}
          </Text>
        </Kpi>
      </KpiStrip>

      <Section
        title="Buys this night · oldest first"
        hint="Steps: session · wallet · market · rates · export · qualify · register · sweep. Choose a buy to see its 10 sweep runs."
      >
        {items.length === 0 ? (
          <EmptyState title="No buys in this night" />
        ) : (
          <ScrollArea type="auto">
            <Table miw={900} aria-label="Buys this night">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Buy ({zoneLabel(status.zone)})</Table.Th>
                  <Table.Th>Token</Table.Th>
                  <Table.Th ta="right">Paid to curve</Table.Th>
                  <Table.Th>Progress</Table.Th>
                  <Table.Th>State</Table.Th>
                  <Table.Th>Note</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {items.map((item) => {
                  const selected = item.id === selectedId;
                  return (
                    <Table.Tr
                      key={item.id}
                      bg={selected ? 'var(--mantine-primary-color-light)' : undefined}
                      aria-selected={selected}
                    >
                      <Table.Th scope="row" fw={400}>
                        <Anchor
                          component="button"
                          size="sm"
                          ff="monospace"
                          onClick={() => select(item.id)}
                          aria-label={`Show buy at ${zoneTime(item.blockTime, status.zone, true)}`}
                        >
                          {zoneTime(item.blockTime, status.zone, true)}
                        </Anchor>
                      </Table.Th>
                      <Table.Td>
                        <Mint mint={item.mint} />
                      </Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {formatDecimal(lamportsToSol(item.curvePaymentLamports))} SOL
                      </Table.Td>
                      <Table.Td>
                        <StepStrip item={item} />
                      </Table.Td>
                      <Table.Td>
                        <Badge variant="light" color={STATE_COLOR[bucketOf(item.state)]} tt="none">
                          {itemStateLabels[item.state]}
                        </Badge>
                      </Table.Td>
                      <Table.Td maw={360}>
                        <Text size="sm" c="dimmed">
                          <ItemNote item={item} zone={status.zone} />
                        </Text>
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )}
      </Section>

      {selectedId && <SelectedBuy itemId={selectedId} status={status} />}

      <Section
        title="Scoreboard for the 7 nights ending here"
        hint="The same rule as the overview: worst-case net P&L above 0 SOL over at least 10 signals."
      >
        {evaluations.length === 0 ? (
          <Text size="sm" c="dimmed">
            Not scored yet: scoring starts when this night and every earlier night are closed.
          </Text>
        ) : (
          <Scoreboard evaluations={evaluations} />
        )}
      </Section>
    </Stack>
  );
}

function NightBreadcrumb({ title }: { title: string }) {
  return (
    <Breadcrumbs separator="›">
      <Anchor component={Link} to={paths.automation} size="sm">
        Automation
      </Anchor>
      <Anchor component={Link} to={paths.automationNights} size="sm">
        Nights
      </Anchor>
      <Text size="sm">{title}</Text>
    </Breadcrumbs>
  );
}

export function AutomationNightPage() {
  const { id = '' } = useParams();
  const detail = useAutomationSession(id);
  return (
    <AutomationFrame description="">
      {(status) => {
        if (detail.isPending) return <LoadingState label="Loading the night…" />;
        if (detail.isError)
          return detail.error instanceof ApiError &&
            detail.error.code === 'AUTOMATION_SESSION_NOT_FOUND' ? (
            <EmptyState
              title="Night not found"
              description="It may have been merged into another night by a catch-up scan."
            />
          ) : (
            <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
          );
        const { session } = detail.data;
        const title = nightTitle(session, status.zone);
        return (
          <Stack gap="lg">
            <NightBreadcrumb title={title} />
            <Card padding="md">
              <Group justify="space-between" wrap="wrap" gap="md">
                <Stack gap={4}>
                  <Text fz="xl" fw={700} component="h2" m={0}>
                    {title}
                  </Text>
                  <Text size="sm" c="dimmed">
                    {zoneDateTime(session.firstBuyAt, status.zone)} →{' '}
                    {zoneTime(session.lastBuyAt, status.zone)} {zoneLabel(status.zone)}
                    {session.state === 'CLOSED'
                      ? session.closeReason === 'MANUAL'
                        ? ' · closed by hand'
                        : ' · closed after 60 min without a new buy'
                      : session.state === 'OPEN'
                        ? ' · open, waiting for more buys'
                        : ' · closing, waiting for its last buys to finish'}{' '}
                    · wallet <ShortId value={session.wallet} />
                  </Text>
                </Stack>
                <Group gap="xs" wrap="wrap">
                  <Badge
                    variant="light"
                    color={
                      session.state === 'CLOSED'
                        ? 'gray'
                        : session.state === 'OPEN'
                          ? 'teal'
                          : 'amber'
                    }
                    tt="none"
                  >
                    {session.state === 'CLOSED'
                      ? 'Closed'
                      : session.state === 'OPEN'
                        ? 'Open'
                        : 'Closing'}
                  </Badge>
                  {buyBuckets(session.itemCounts).issues > 0 && (
                    <Badge
                      variant="light"
                      color="amber"
                      tt="none"
                      leftSection={<span aria-hidden="true">⚠</span>}
                    >
                      Capture issues
                    </Badge>
                  )}
                  <CloseNight detail={detail.data} />
                </Group>
              </Group>
            </Card>
            <StatusAlerts status={status} />
            <Night detail={detail.data} status={status} />
          </Stack>
        );
      }}
    </AutomationFrame>
  );
}

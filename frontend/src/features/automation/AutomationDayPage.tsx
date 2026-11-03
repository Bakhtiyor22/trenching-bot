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
  useAutomationDay,
  useAutomationItem,
  useCloseSession,
  type AutomationDayDetail,
  type AutomationItem,
  type AutomationSession,
  type AutomationStatus,
  type AutomationStep,
} from '../../api/automation';
import { Mint, ShortId } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import { reasonText } from '../captures/captureReasons';
import {
  addDays,
  bucketOf,
  stepMarks,
  type StepMark,
  compareWorstDesc,
  dayInfo,
  itemStateLabels,
  lamportsToSol,
  tradingDayOf,
  outcomeExplanation,
  outcomeLabel,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
} from './automationModel';
import {
  AutomationFrame,
  BestOfDay,
  BuyStateBar,
  SolValue,
  StatusAlerts,
  ZoneTime,
} from './AutomationShared';
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

const TERMINAL = ['SIMULATED', 'FAILED', 'ABANDONED', 'SKIPPED_NOT_FIRST_BUY'];

function CloseSession({ session, items }: { session: AutomationSession; items: AutomationItem[] }) {
  const [opened, { open, close }] = useDisclosure(false);
  const mutation = useCloseSession(session.id);
  const unfinished = items.filter(
    (i) => i.sessionId === session.id && !TERMINAL.includes(i.state),
  ).length;
  if (session.state === 'CLOSED') return null;
  return (
    <>
      <Button variant="default" color="red" size="xs" onClick={open}>
        Close session now
      </Button>
      <Modal opened={opened} onClose={close} title="Close this session now?" centered>
        <Stack gap="md">
          <Text size="sm">
            The session ends now instead of 60 minutes after its last buy.{' '}
            {unfinished > 0
              ? `${unfinished} unfinished ${unfinished === 1 ? 'buy becomes' : 'buys become'} Abandoned and ${unfinished === 1 ? 'is' : 'are'} not scored.`
              : 'Every buy has finished, so nothing is abandoned.'}{' '}
            Buys found later start a new session.
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
              Close session
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

const SESSION_STATE = {
  OPEN: { text: 'Open', color: 'teal' },
  CLOSING: { text: 'Closing', color: 'amber' },
  CLOSED: { text: 'Closed', color: 'gray' },
} as const;

function Sessions({ detail, status }: { detail: AutomationDayDetail; status: AutomationStatus }) {
  return (
    <ScrollArea type="auto">
      <Table miw={760} aria-label="Sessions this day">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Session ({zoneLabel(status.zone)})</Table.Th>
            <Table.Th>Source</Table.Th>
            <Table.Th>State</Table.Th>
            <Table.Th>Buys</Table.Th>
            <Table.Th ta="right">Requests</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {detail.sessions.map((s) => (
            <Table.Tr key={s.id}>
              <Table.Th scope="row" fw={400} ff="monospace">
                {zoneTime(s.firstBuyAt, status.zone)} → {zoneTime(s.lastBuyAt, status.zone)}
              </Table.Th>
              <Table.Td>
                {s.backfillId ? (
                  <Anchor component={Link} to={paths.automationBackfill(s.backfillId)} size="sm">
                    Backfill
                  </Anchor>
                ) : (
                  <Text size="sm">Live</Text>
                )}
              </Table.Td>
              <Table.Td>
                <Badge variant="light" color={SESSION_STATE[s.state].color} tt="none">
                  {SESSION_STATE[s.state].text}
                  {s.closeReason === 'MANUAL' ? ' by hand' : ''}
                </Badge>
              </Table.Td>
              <Table.Td>
                <BuyStateBar counts={s.itemCounts} captureEnabled={status.captureEnabled} />
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {s.requestsUsed.toLocaleString('en-US')}
              </Table.Td>
              <Table.Td ta="right">
                <CloseSession session={s} items={detail.items} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
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

function coverageText(detail: AutomationDayDetail, zone: string): string {
  const { summary, coverage, items } = detail;
  const unfinished = items.filter((i) => !TERMINAL.includes(i.state)).length;
  const open = detail.sessions.filter((s) => s.state !== 'CLOSED').length;
  const running = Date.parse(summary.to) > Date.now();
  const ranges = coverage.ranges
    .map((r) => `${zoneDateTime(r.from, zone)} → ${zoneDateTime(r.to, zone)}`)
    .join(', ');
  const covered = coverage.covered
    ? 'Fully covered by discovery'
    : running
      ? `Today, until ${zoneDateTime(summary.to, zone)}${ranges ? `; covered so far ${ranges}` : ''}`
      : ranges
        ? `Partly covered: ${ranges}`
        : 'Not covered by discovery yet';
  if (summary.complete) return `${covered} · complete`;
  const reasons = [
    !coverage.covered && !running && 'not fully covered',
    open > 0 && `${open} ${open === 1 ? 'session' : 'sessions'} still open`,
    unfinished > 0 && `${unfinished} ${unfinished === 1 ? 'buy' : 'buys'} still in progress`,
  ].filter(Boolean);
  return `${covered} · not complete${reasons.length ? `: ${reasons.join(', ')}` : ''}`;
}

function Day({ detail, status }: { detail: AutomationDayDetail; status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const { summary, items, evaluations } = detail;
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
  const skipped = items.filter((i) => i.state === 'SKIPPED_NOT_FIRST_BUY').length;
  const inProgress = items.filter((i) => !TERMINAL.includes(i.state)).length;
  return (
    <Stack gap="lg">
      <KpiStrip label="Day totals" min={170}>
        <Kpi
          label="Buys"
          hint={[
            summary.simulated && `${summary.simulated} replayed`,
            inProgress && `${inProgress} in progress`,
            skipped && `${skipped} not first`,
            summary.failed + summary.abandoned &&
              `${summary.failed + summary.abandoned} failed or abandoned`,
          ]
            .filter(Boolean)
            .join(' · ')}
        >
          <Text fz="xl" fw={600}>
            {summary.buys}
          </Text>
        </Kpi>
        <Kpi label="Below the filter" hint="buys too small to capture, fees included">
          <Text fz="xl" fw={600}>
            {summary.belowFilter}
          </Text>
        </Kpi>
        <Kpi label="Best variation" hint="over the 7 trading days ending here">
          <BestOfDay day={summary} planId={status.sweepPlanId} />
        </Kpi>
        <Kpi label="Unscored" hint="failed or abandoned buys, kept out of P&L">
          <Text fz="xl" fw={600}>
            {summary.failed + summary.abandoned}
          </Text>
        </Kpi>
        <Kpi label="Requests" hint="provider calls for these captures">
          <Text fz="xl" fw={600} ff="monospace">
            {summary.requestsUsed.toLocaleString('en-US')}
          </Text>
        </Kpi>
      </KpiStrip>

      <Section
        title="Sessions"
        hint="Buys grouped by idle gaps of 60 minutes, never across a day boundary."
      >
        {detail.sessions.length === 0 ? (
          <Text size="sm" c="dimmed">
            No buys this day
            {summary.covered ? '. It counts toward the 7-day window with zero signals.' : '.'}
          </Text>
        ) : (
          <Sessions detail={detail} status={status} />
        )}
      </Section>

      {items.length > 0 && (
        <Section
          title="Buys this day · oldest first"
          hint="Steps: session · wallet · market · rates · export · qualify · register · sweep. Choose a buy to see its 10 sweep runs."
        >
          <ScrollArea type="auto">
            <Table miw={900} aria-label="Buys this day">
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
        </Section>
      )}

      {selectedId && <SelectedBuy itemId={selectedId} status={status} />}

      <Section
        title="Scoreboard for the 7 trading days ending here"
        hint="The same rule as the overview: worst-case net P&L above 0 SOL over at least 10 signals. Pending rows show the counts so far."
      >
        {evaluations.length === 0 ? (
          <Text size="sm" c="dimmed">
            No rows for this day.
          </Text>
        ) : (
          <Scoreboard evaluations={evaluations} />
        )}
      </Section>
    </Stack>
  );
}

function DayBreadcrumb({ title }: { title: string }) {
  return (
    <Breadcrumbs separator="›">
      <Anchor component={Link} to={paths.automation} size="sm">
        Automation
      </Anchor>
      <Anchor component={Link} to={paths.automationDays} size="sm">
        Days
      </Anchor>
      <Text size="sm">{title}</Text>
    </Breadcrumbs>
  );
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

export function AutomationDayPage() {
  const { date = '' } = useParams();
  const valid = DATE.test(date);
  const detail = useAutomationDay(valid ? date : null);
  return (
    <AutomationFrame description="">
      {(status) => {
        if (!valid)
          return <EmptyState title="Not a trading day" description="Days are named YYYY-MM-DD." />;
        if (detail.isPending) return <LoadingState label="Loading the day…" />;
        if (detail.isError)
          return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
        const { summary } = detail.data;
        const title = dayInfo(summary.day).label;
        const today = tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
        return (
          <Stack gap="lg">
            <DayBreadcrumb title={title} />
            <Card padding="md">
              <Group justify="space-between" wrap="wrap" gap="md">
                <Stack gap={4}>
                  <Text fz="xl" fw={700} component="h2" m={0}>
                    {title}
                    {summary.day === today && (
                      <Text span c="dimmed" fz="md" fw={400}>
                        {' '}
                        · today
                      </Text>
                    )}
                  </Text>
                  <Text size="sm" c="dimmed">
                    {zoneDateTime(summary.from, status.zone)} →{' '}
                    {zoneDateTime(summary.to, status.zone)} {zoneLabel(status.zone)}
                  </Text>
                  <Text size="sm">{coverageText(detail.data, status.zone)}</Text>
                </Stack>
                <Group gap="xs" wrap="wrap">
                  <Button
                    component={Link}
                    to={paths.automationDay(addDays(summary.day, -1))}
                    variant="default"
                    size="xs"
                  >
                    ← {dayInfo(addDays(summary.day, -1)).short}
                  </Button>
                  {summary.day < today && (
                    <Button
                      component={Link}
                      to={paths.automationDay(addDays(summary.day, 1))}
                      variant="default"
                      size="xs"
                    >
                      {dayInfo(addDays(summary.day, 1)).short} →
                    </Button>
                  )}
                </Group>
              </Group>
            </Card>
            <StatusAlerts status={status} />
            <Day detail={detail.data} status={status} />
          </Stack>
        );
      }}
    </AutomationFrame>
  );
}

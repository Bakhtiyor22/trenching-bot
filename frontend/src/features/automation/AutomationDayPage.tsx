import {
  Anchor,
  Box,
  Button,
  Group,
  Modal,
  ScrollArea,
  Stack,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useEffect } from 'react';
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
import { CodeText } from '../../components/PlainLanguage';
import { QueryView } from '../../components/QueryView';
import { EmptyState, ErrorState } from '../../components/StateViews';
import { StatusChip, type StatusTone } from '../../components/StatusChip';
import { codeLabel } from '../../lib/codes';
import { decimalSign, formatDecimal } from '../../lib/decimal';
import { formatCount, formatSlot } from '../../lib/format';
import { paths } from '../../paths';
import { reasonText } from '../captures/captureReasons';
import {
  addDays,
  bucketOf,
  compareWorstDesc,
  dateProblem,
  dayInfo,
  itemStateLabels,
  lamportsToSol,
  stepMarks,
  tradingDayOf,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
  type BuyBucket,
  type StepMark,
} from './automationModel';
import {
  AutomationFrame,
  BestOfDay,
  BuyStateBar,
  OutcomeText,
  RunLink,
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
/** The selected row: the raised tone with the accent edge. */
const SELECTED_ROW = {
  background: 'var(--console-raised)',
  boxShadow: 'inset 3px 0 0 var(--mantine-primary-color-filled)',
} as const;

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
              border: mark === 'pending' ? '1.5px solid var(--console-line-strong)' : undefined,
            }}
          />
        ))}
      </Group>
    </Tooltip>
  );
}

const BUCKET_CHIP: Record<BuyBucket, { tone: StatusTone; glyph?: string }> = {
  replayed: { tone: 'success', glyph: '✓' },
  inProgress: { tone: 'running' },
  issues: { tone: 'failure', glyph: '✕' },
  skipped: { tone: 'neutral', glyph: '–' },
};

function ItemStateChip({ item }: { item: AutomationItem }) {
  const chip = BUCKET_CHIP[bucketOf(item.state)];
  return (
    <StatusChip tone={chip.tone} glyph={chip.glyph}>
      {itemStateLabels[item.state]}
    </StatusChip>
  );
}

function ItemNote({ item, zone }: { item: AutomationItem; zone: string }) {
  if (item.state === 'SIMULATED') return <>Every variation replayed</>;
  if (item.state === 'SKIPPED_NOT_FIRST_BUY') return <>Decu held this token before; never scored</>;
  if (item.state === 'BLOCKED_BUDGET') return <>Waiting for the 24 h request cap to free up</>;
  if (item.state === 'FAILED' || item.state === 'ABANDONED') {
    const reason = item.failureCode
      ? (reasonText(item.failureCode) ?? codeLabel(item.failureCode))
      : null;
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

/** Close a session now, after confirmation. The dialog stays open while the request is sending. */
function CloseSession({ session, items }: { session: AutomationSession; items: AutomationItem[] }) {
  const [opened, { open, close }] = useDisclosure(false);
  const mutation = useCloseSession(session.id);
  const sending = mutation.isPending;
  const unfinished = items.filter(
    (i) => i.sessionId === session.id && !TERMINAL.includes(i.state),
  ).length;
  if (session.state === 'CLOSED') return null;
  const dismiss = () => {
    if (!sending) close();
  };
  return (
    <>
      <Button
        variant="outline"
        color="red"
        size="xs"
        onClick={() => {
          mutation.reset();
          open();
        }}
      >
        Close session now
      </Button>
      <Modal
        opened={opened}
        onClose={dismiss}
        closeOnClickOutside={!sending}
        closeOnEscape={!sending}
        withCloseButton={!sending}
        title="Close this session now?"
        centered
      >
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
            <Button variant="default" onClick={dismiss} disabled={sending}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={sending}
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

const SESSION_STATE: Record<AutomationSession['state'], { text: string; tone: StatusTone }> = {
  OPEN: { text: 'Open', tone: 'running' },
  CLOSING: { text: 'Closing', tone: 'running' },
  CLOSED: { text: 'Closed', tone: 'neutral' },
};

function Sessions({ detail, status }: { detail: AutomationDayDetail; status: AutomationStatus }) {
  return (
    <ScrollArea type="auto">
      <Table miw={760} verticalSpacing="xs" aria-label="Sessions this day">
        <Table.Thead>
          <Table.Tr>
            <Table.Th className="console-sticky">Session ({zoneLabel(status.zone)})</Table.Th>
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
              <Table.Th scope="row" fw={400} ff="monospace" className="console-sticky">
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
                <StatusChip tone={SESSION_STATE[s.state].tone}>
                  {SESSION_STATE[s.state].text}
                  {s.closeReason === 'MANUAL' ? ' by hand' : ''}
                </StatusChip>
              </Table.Td>
              <Table.Td>
                <BuyStateBar counts={s.itemCounts} captureEnabled={status.captureEnabled} />
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {formatCount(s.requestsUsed)}
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

function SelectedBuy({
  itemId,
  status,
  live,
}: {
  itemId: string;
  status: AutomationStatus;
  /** True while the day can still change; the buy then refreshes with the day's list. */
  live: boolean;
}) {
  const detail = useAutomationItem(itemId, { poll: live });
  return (
    <QueryView query={detail} loadingLabel="Loading the buy…" errorTitle="Buy unavailable">
      {({ item, sweepRuns }) => {
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
                Selected buy · {zoneTime(item.blockTime, status.zone, true)} ·{' '}
                <Mint mint={item.mint} /> ·{' '}
                {formatDecimal(lamportsToSol(item.curvePaymentLamports))} SOL
              </>
            }
            hint={[
              itemStateLabels[item.state],
              item.classification
                ? `verdict ${codeLabel(item.classification).toLowerCase()}`
                : null,
              item.requestsUsed != null ? `${formatCount(item.requestsUsed)} requests` : null,
              `slot ${formatSlot(item.slot)}`,
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
                  <Button
                    component={Link}
                    to={paths.dataset(item.datasetId)}
                    variant="default"
                    size="xs"
                  >
                    Open dataset
                  </Button>
                )}
                {compared.length >= 2 && (
                  <Button
                    component={Link}
                    to={paths.compareRuns(compared)}
                    variant="default"
                    size="xs"
                  >
                    Compare {compared.length} runs
                  </Button>
                )}
              </Group>
            }
          >
            <Stack gap="sm">
              <Group gap="lg" wrap="wrap">
                <Text size="sm" component="div">
                  Signature <ShortId value={item.signature} />
                </Text>
                <Text size="sm" component="div">
                  Block time <ZoneTime iso={item.blockTime} zone={status.zone} withDate />{' '}
                  {zoneLabel(status.zone)}
                </Text>
                <Text size="sm" c="dimmed">
                  Paid to the curve, fees excluded
                </Text>
              </Group>
              {(item.classification || item.failureCode) && (
                <Group gap="lg" wrap="wrap">
                  <CodeText code={item.classification} />
                  <CodeText code={item.failureCode} />
                </Group>
              )}
              {runs.length === 0 ? (
                <Text size="sm" c="dimmed">
                  Not replayed yet
                  {item.state === 'SKIPPED_NOT_FIRST_BUY'
                    ? ': this was not Decu’s first buy of the token, so it is never replayed.'
                    : '.'}
                </Text>
              ) : (
                <ScrollArea type="auto">
                  <Table miw={820} verticalSpacing="xs" aria-label="Sweep runs of this buy">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th className="console-sticky">Variation</Table.Th>
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
                          <Table.Th scope="row" fw={400} className="console-sticky">
                            <Text span ff="monospace" fw={600} size="sm">
                              {r.label}
                            </Text>{' '}
                            <Text span size="xs" c="dimmed">
                              {variationChange(r.planId, r.label)}
                            </Text>
                          </Table.Th>
                          <Table.Td>
                            <OutcomeText outcome={r.outcome} />
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm" c={r.signal ? undefined : 'dimmed'}>
                              {r.signal
                                ? decimalSign(r.realizedPnl) === 0 &&
                                  decimalSign(r.worstCasePnl) === 0
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
                            <RunLink runId={r.runId} />
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
      }}
    </QueryView>
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
  // The default buy is picked from live data; pin it in the URL so a refresh does not move the selection.
  useEffect(() => {
    if (selectedId && requested !== selectedId)
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('item', selectedId);
          return next;
        },
        { replace: true },
      );
  }, [selectedId, requested, setParams]);
  const skipped = items.filter((i) => i.state === 'SKIPPED_NOT_FIRST_BUY').length;
  const inProgress = items.filter((i) => !TERMINAL.includes(i.state)).length;
  const unscored = summary.failed + summary.abandoned;
  return (
    <Stack gap="lg">
      <KpiStrip label="Day totals" min={170}>
        <Kpi
          label="Buys"
          hint={[
            summary.simulated && `${formatCount(summary.simulated)} replayed`,
            inProgress && `${formatCount(inProgress)} in progress`,
            skipped && `${formatCount(skipped)} not first`,
            unscored && `${formatCount(unscored)} failed or abandoned`,
          ]
            .filter(Boolean)
            .join(' · ')}
        >
          {formatCount(summary.buys)}
        </Kpi>
        <Kpi label="Below the filter" hint="buys too small to capture, fees included">
          {formatCount(summary.belowFilter)}
        </Kpi>
        <Kpi label="Best variation" hint="over the 7 trading days ending here">
          <BestOfDay day={summary} planId={status.sweepPlanId} />
        </Kpi>
        <Kpi label="Unscored" hint="failed or abandoned buys, kept out of P&L">
          {formatCount(unscored)}
        </Kpi>
        <Kpi label="Requests" hint="provider calls for these captures">
          {formatCount(summary.requestsUsed)}
        </Kpi>
      </KpiStrip>

      <Section
        title="Sessions"
        hint="Buys grouped by idle gaps of 60 minutes, never across a day boundary."
      >
        {detail.sessions.length === 0 ? (
          <Text size="sm" c="dimmed">
            {summary.covered
              ? 'No buys this day. It counts toward the 7-day window with zero signals.'
              : 'Not scanned yet: discovery has not covered this day, so its buys are unknown.'}
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
            <Table miw={900} verticalSpacing="xs" aria-label="Buys this day">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th className="console-sticky">Buy ({zoneLabel(status.zone)})</Table.Th>
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
                      style={selected ? SELECTED_ROW : undefined}
                      aria-selected={selected}
                    >
                      <Table.Th
                        scope="row"
                        fw={400}
                        className="console-sticky"
                        style={selected ? SELECTED_ROW : undefined}
                      >
                        <Anchor
                          component="button"
                          size="sm"
                          ff="monospace"
                          onClick={() => select(item.id)}
                          aria-label={`Show buy at ${zoneTime(item.blockTime, status.zone, true)}`}
                          aria-current={selected || undefined}
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
                        <ItemStateChip item={item} />
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

      {selectedId && <SelectedBuy itemId={selectedId} status={status} live={!summary.complete} />}

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

/** Where the day stands, as the header chip. */
function DayChip({ detail, today }: { detail: AutomationDayDetail; today: string }) {
  const { summary } = detail;
  if (summary.complete)
    return (
      <StatusChip tone="neutral" glyph="✓">
        Complete
      </StatusChip>
    );
  if (summary.day > today) return <StatusChip tone="neutral">Not started</StatusChip>;
  if (summary.day === today) return <StatusChip tone="running">Today · in progress</StatusChip>;
  return (
    <StatusChip tone="pending" glyph="◷">
      Not complete
    </StatusChip>
  );
}

function todayOf(status: AutomationStatus): string {
  return tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
}

export function AutomationDayPage() {
  const { date = '' } = useParams();
  const problem = dateProblem(date);
  const valid = problem == null;
  const detail = useAutomationDay(valid ? date : null);
  const title = valid ? dayInfo(date).label : 'Day';
  return (
    <AutomationFrame
      title={title}
      breadcrumbs={[
        { label: 'Automation', to: paths.automation },
        { label: 'Days', to: paths.automationDays },
        { label: title },
      ]}
      status={(status) =>
        detail.data ? <DayChip detail={detail.data} today={todayOf(status)} /> : null
      }
      description={(status) =>
        detail.data ? (
          <>
            <span style={{ display: 'block' }}>
              {zoneDateTime(detail.data.summary.from, status.zone)} →{' '}
              {zoneDateTime(detail.data.summary.to, status.zone)} {zoneLabel(status.zone)}
            </span>
            <span style={{ display: 'block' }}>{coverageText(detail.data, status.zone)}</span>
          </>
        ) : null
      }
      actions={(status) =>
        valid ? (
          <>
            <Button component={Link} to={paths.automationDay(addDays(date, -1))} variant="default">
              ← {dayInfo(addDays(date, -1)).short}
            </Button>
            {date < todayOf(status) && (
              <Button component={Link} to={paths.automationDay(addDays(date, 1))} variant="default">
                {dayInfo(addDays(date, 1)).short} →
              </Button>
            )}
          </>
        ) : null
      }
    >
      {(status) =>
        !valid ? (
          <EmptyState
            title="Not a trading day"
            description={`${problem} Days are named YYYY-MM-DD.`}
          />
        ) : (
          <QueryView query={detail} loadingLabel="Loading the day…" errorTitle="Day unavailable">
            {(data) => (
              <Stack gap="lg">
                <StatusAlerts status={status} />
                <Day detail={data} status={status} />
              </Stack>
            )}
          </QueryView>
        )
      }
    </AutomationFrame>
  );
}

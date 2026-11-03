import {
  Alert,
  Anchor,
  Box,
  Button,
  Group,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import {
  useAllAutomationEvaluations,
  useAutomationDays,
  useBackfill,
  useRunDiscovery,
  type AutomationDaySummary,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { RelativeTime } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import {
  addDays,
  automationMode,
  backfillDays,
  isBackfillRefusal,
  BASELINE_LABEL,
  capPercent,
  dayInfo,
  dayItemCounts,
  daySpanText,
  defaultScoredDay,
  GRID_DAY_CHOICES,
  pendingText,
  statusText,
  tradingDayOf,
  zoneDateTime,
  zoneLabel,
  zoneTime,
  type GridDays,
} from './automationModel';
import {
  AutomationFrame,
  BuyStateBar,
  BuyStateLegend,
  DayLink,
  BestOfDay,
  StatusAlerts,
  ZoneNote,
} from './AutomationShared';
import { BackfillRefusal, BackfillStateChip } from './BackfillShared';
import { Scoreboard } from './Scoreboard';
import { StatusGrid, StatusGridLegend } from './StatusGrid';
import { VariationTrend } from './VariationTrend';

const MODE_TEXT = {
  OFF: 'Off',
  DISCOVERY_ONLY: 'Discovery only',
  CAPTURING: 'Capturing',
} as const;
const STATUS_FILTERS: Array<{ value: 'ALL' | EvaluationStatus; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'SUCCESS', label: '✓ Success' },
  { value: 'NO_SUCCESS', label: '✕ No success' },
  { value: 'NO_RESULT', label: '– No result' },
  { value: 'PENDING', label: '◷ Pending' },
];
const RECENT_DAYS = 5;
const DEFAULT_SPAN: GridDays = '14';

/** "Run now": queue one live discovery pass, whatever the mode or window. */
function RunNow({ status }: { status: AutomationStatus }) {
  const run = useRunDiscovery();
  const queued = status.manualRunQueued || (run.isPending && !run.isError);
  return (
    <Stack gap={4} align="flex-start">
      <Tooltip label="Scan Decu's wallet for new buys now; capture follows on the next minute's tick">
        <Button
          size="xs"
          variant="light"
          onClick={() => run.mutate()}
          loading={run.isPending}
          disabled={!status.enabled || status.manualRunQueued}
        >
          {queued ? 'Queued…' : 'Run now'}
        </Button>
      </Tooltip>
      {run.isError && (
        <Text size="xs" c="red" role="alert">
          {isBackfillRefusal(run.error) ? (
            <BackfillRefusal error={run.error} />
          ) : run.error instanceof ApiError ? (
            run.error.message
          ) : (
            'Run now failed'
          )}
        </Text>
      )}
      {run.data?.alreadyQueued && !status.manualRunQueued && (
        <Text size="xs" c="dimmed">
          A run was already queued.
        </Text>
      )}
    </Stack>
  );
}

function ActiveBackfill({ id, zone }: { id: string; zone: string }) {
  const backfill = useBackfill(id);
  if (!backfill.data)
    return (
      <Anchor component={Link} to={paths.automationBackfill(id)} size="sm">
        Open the running backfill
      </Anchor>
    );
  const b = backfill.data;
  return (
    <Stack gap={4} align="flex-start">
      <Anchor component={Link} to={paths.automationBackfill(id)} fw={600}>
        {backfillDays(b)}
      </Anchor>
      <BackfillStateChip state={b.state} />
      <Text size="xs" c="dimmed" ff="monospace">
        {b.requestsSpent.toLocaleString('en-US')} / {b.requestBudget.toLocaleString('en-US')}{' '}
        requests
        {b.scannedTo && b.state === 'SCANNING'
          ? ` · read to ${zoneDateTime(b.scannedTo, zone)}`
          : ''}
      </Text>
    </Stack>
  );
}

function StatusStrip({
  status,
  today,
}: {
  status: AutomationStatus;
  today: AutomationDaySummary | null;
}) {
  const zone = zoneLabel(status.zone);
  const mode = automationMode(status);
  const percent = capPercent(status.requestsLast24h, status.maxRequestsPer24h);
  return (
    <KpiStrip label="Automation status" min={180}>
      <Kpi
        label="Mode"
        hint={
          status.mode === 'ALWAYS'
            ? `always on · plan ${status.sweepPlanId}`
            : `${status.pollFrom}–${status.pollTo} ${zone} · ${status.inPollWindow ? 'in window now' : 'outside the window'}`
        }
      >
        <Group gap={8} wrap="nowrap">
          <Box
            w={8}
            h={8}
            style={{ borderRadius: '50%' }}
            bg={
              mode === 'CAPTURING' ? 'var(--mantine-color-teal-6)' : 'var(--mantine-color-blue-6)'
            }
            className={mode === 'CAPTURING' ? 'console-live-dot' : undefined}
          />
          <Text fz="lg" fw={600}>
            {MODE_TEXT[mode]}
          </Text>
        </Group>
      </Kpi>
      <Kpi
        label="Last check"
        hint={
          status.nextTickAt ? `next ${zoneTime(status.nextTickAt, status.zone)} ${zone}` : undefined
        }
      >
        <Stack gap={6}>
          <Text fz="lg" component="div">
            <RelativeTime iso={status.lastTickAt} />
          </Text>
          <RunNow status={status} />
        </Stack>
      </Kpi>
      <Kpi
        label="Requests, last 24 h"
        hint={`${status.maxRequestsPer24h.toLocaleString('en-US')} cap, shared with manual captures and backfills`}
      >
        <Stack gap={6}>
          <Text fz="lg" ff="monospace">
            {status.requestsLast24h.toLocaleString('en-US')}
            <Text span size="sm" c="dimmed">
              {' '}
              / {status.maxRequestsPer24h.toLocaleString('en-US')}
            </Text>
          </Text>
          <Box
            role="meter"
            aria-label="Requests used of the 24 hour cap"
            aria-valuemin={0}
            aria-valuemax={status.maxRequestsPer24h}
            aria-valuenow={status.requestsLast24h}
            h={6}
            style={{
              borderRadius: 3,
              overflow: 'hidden',
              background: 'var(--mantine-color-default-border)',
            }}
          >
            <Box
              h="100%"
              style={{
                width: `${percent}%`,
                background:
                  percent >= 90 ? 'var(--automation-issues)' : 'var(--automation-replayed)',
              }}
            />
          </Box>
        </Stack>
      </Kpi>
      <Kpi label="Covered since" hint="live discovery; backfills reach further back">
        <Text fz="lg">
          {status.coveredFrom ? zoneDateTime(status.coveredFrom, status.zone) : '—'}
        </Text>
      </Kpi>
      <Kpi label="Today" hint={daySpanText(status.zone, status.tradingDayStart)}>
        {today ? (
          <Stack gap={4}>
            <DayLink day={today.day} />
            <BuyStateBar counts={dayItemCounts(today)} captureEnabled={status.captureEnabled} />
          </Stack>
        ) : (
          <Text fz="lg" c="dimmed">
            No buys yet
          </Text>
        )}
      </Kpi>
      <Kpi label="Backfill" hint="fills older days with past buys">
        {status.activeBackfillId ? (
          <ActiveBackfill id={status.activeBackfillId} zone={status.zone} />
        ) : (
          <Anchor component={Link} to={paths.automationBackfills} size="sm">
            None running · start one
          </Anchor>
        )}
      </Kpi>
    </KpiStrip>
  );
}

function RecentDays({ days, status }: { days: AutomationDaySummary[]; status: AutomationStatus }) {
  return (
    <ScrollArea type="auto">
      <Table miw={820} aria-label="Recent days">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Day ({daySpanText(status.zone, status.tradingDayStart)})</Table.Th>
            <Table.Th>Buys</Table.Th>
            <Table.Th>Best variation</Table.Th>
            <Table.Th ta="right">Requests</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {days.map((d) => (
            <Table.Tr key={d.day}>
              <Table.Th scope="row" fw={400}>
                <Stack gap={0}>
                  <DayLink day={d.day} />
                  <Text size="xs" c="dimmed">
                    {d.complete ? 'complete' : d.covered ? 'not complete yet' : 'not fully covered'}
                  </Text>
                </Stack>
              </Table.Th>
              <Table.Td>
                <BuyStateBar counts={dayItemCounts(d)} captureEnabled={status.captureEnabled} />
              </Table.Td>
              <Table.Td>
                <BestOfDay day={d} planId={status.sweepPlanId} />
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {d.requestsUsed.toLocaleString('en-US')}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function Overview({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const span = (GRID_DAY_CHOICES as readonly string[]).includes(params.get('days') ?? '')
    ? (params.get('days') as GridDays)
    : DEFAULT_SPAN;
  const statusFilter = (params.get('status') as EvaluationStatus | null) ?? null;
  const set = (key: string, value: string | null, fallback?: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value == null || value === fallback) next.delete(key);
        else next.set(key, value);
        return next;
      },
      { replace: true },
    );
  const today = tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
  const from = addDays(today, -(Number(span) - 1));
  const days = useAutomationDays({ from, to: today });
  const evaluations = useAllAutomationEvaluations({ from, to: today, sort: 'day,desc' });

  if (days.isPending || evaluations.isPending)
    return <LoadingState label="Loading days and scores…" />;
  if (days.isError) return <ErrorState error={days.error} onRetry={() => void days.refetch()} />;
  if (evaluations.isError)
    return <ErrorState error={evaluations.error} onRetry={() => void evaluations.refetch()} />;

  const list = days.data.items;
  const todaySummary = list.find((d) => d.day === today && d.buys > 0) ?? null;
  const all = evaluations.data;
  const labels = [...new Set(all.map((e) => e.label))].sort();
  const scoredId = params.get('day');
  const scored = list.find((d) => d.day === scoredId) ?? defaultScoredDay(list);
  const variation = params.get('variation') ?? BASELINE_LABEL;
  const selected = labels.includes(variation) ? variation : (labels[0] ?? BASELINE_LABEL);
  const dayRows = scored ? all.filter((e) => e.day === scored.day) : [];
  const board = dayRows.filter((e) => !statusFilter || e.status === statusFilter);
  const allPending = list.length > 0 && list.every((d) => d.bestStatus === 'PENDING');
  const anyBuys = list.some((d) => d.buys > 0);
  const daysShown = list.map((d) => d.day);

  return (
    <Stack gap="lg">
      <StatusAlerts status={status} />
      <StatusStrip status={status} today={todaySummary} />
      {!anyBuys && labels.length === 0 ? (
        <EmptyState
          title="No buys in these days yet"
          description={`A trading day runs ${daySpanText(status.zone, status.tradingDayStart)}. Buys appear here once discovery finds them; use Run now to scan immediately, or a backfill for older days.`}
        />
      ) : (
        <>
          <Section
            title="Scoreboard · which variation works"
            hint="Success = worst-case net P&L above 0 SOL over at least 10 signals in the 7 trading days ending the chosen day. Sorted Success › No success › No result › Pending, then by worst case. Worst case counts a position still open at the end as a loss of its entry cost."
            actions={<ZoneNote zone={status.zone} />}
          >
            <Stack gap="md">
              {allPending && (
                <Alert color="gray" variant="light" title="Every day here is pending">
                  {status.captureEnabled
                    ? 'A day gets a verdict once it and the 6 days before it are complete: covered by discovery, every session closed and every buy finished. Rows below show the counts so far.'
                    : 'Discovery only: buys are counted, but none is captured or replayed, so no day completes. Turn capture on to score days.'}
                </Alert>
              )}
              <Group gap="sm" wrap="wrap" align="flex-end">
                <Select
                  label="Scored day"
                  value={scored?.day ?? null}
                  onChange={(v) => set('day', v, defaultScoredDay(list)?.day)}
                  data={list.map((d) => ({
                    value: d.day,
                    label: `${dayInfo(d.day).label}${d.bestStatus === 'PENDING' ? ' (pending)' : ''}`,
                  }))}
                  allowDeselect={false}
                  w={240}
                />
                <Group gap={4} wrap="wrap" role="group" aria-label="Show status">
                  {STATUS_FILTERS.map((f) => {
                    const active = (statusFilter ?? 'ALL') === f.value;
                    return (
                      <Button
                        key={f.value}
                        size="xs"
                        variant={active ? 'filled' : 'default'}
                        aria-pressed={active}
                        onClick={() => set('status', f.value === 'ALL' ? null : f.value)}
                      >
                        {f.label}
                      </Button>
                    );
                  })}
                </Group>
                {scored && dayRows[0] && (
                  <Text size="xs" c="dimmed" pb={8}>
                    Window: {dayInfo(dayRows[0].windowDays[0] ?? scored.day).short} →{' '}
                    {dayInfo(scored.day).short}
                    {dayRows[0].status === 'PENDING' && ` · ${pendingText(dayRows[0].pendingDay)}`}
                  </Text>
                )}
              </Group>
              {board.length === 0 ? (
                <Text size="sm" c="dimmed">
                  {statusFilter
                    ? `No variation is ${statusText[statusFilter].toLowerCase()} for this day. `
                    : 'No rows for this day. '}
                  {statusFilter && (
                    <Anchor component="button" size="sm" onClick={() => set('status', null)}>
                      Show all
                    </Anchor>
                  )}
                </Text>
              ) : (
                <Scoreboard
                  evaluations={board}
                  selectedLabel={selected}
                  onSelect={(l) => set('variation', l, BASELINE_LABEL)}
                />
              )}
            </Stack>
          </Section>
          {labels.length > 0 && (
            <Section
              title="Status by day"
              hint="Each cell scores the 7 trading days ending that day. A variation that stays ✓ across a row is consistent, not lucky. Choose a variation to follow it below; click a cell to open its day."
              actions={<StatusGridLegend />}
            >
              <StatusGrid
                days={daysShown}
                evaluations={all}
                planId={status.sweepPlanId}
                labels={labels}
                selectedLabel={selected}
                onSelectLabel={(l) => set('variation', l, BASELINE_LABEL)}
                span={span}
                onSpanChange={(n) => set('days', n, DEFAULT_SPAN)}
              />
            </Section>
          )}
          {labels.length > 0 && (
            <Section
              title={
                selected === BASELINE_LABEL
                  ? `The default (${BASELINE_LABEL}) by day, with the other variations as context`
                  : `${selected} against the default (${BASELINE_LABEL}), by day`
              }
              hint="Worst-case net P&L of the 7 trading days ending each day. Success needs it above the 0 SOL line with at least 10 signals."
            >
              <VariationTrend
                days={daysShown}
                evaluations={all}
                planId={status.sweepPlanId}
                labels={labels}
                selected={selected}
                onSelect={(l) => set('variation', l, BASELINE_LABEL)}
              />
            </Section>
          )}
          <Section
            title="Recent days"
            actions={
              <Group gap="md" wrap="wrap">
                <BuyStateLegend captureEnabled={status.captureEnabled} />
                <Anchor component={Link} to={paths.automationDays} size="sm">
                  All days →
                </Anchor>
              </Group>
            }
          >
            <RecentDays days={list.slice(0, RECENT_DAYS)} status={status} />
          </Section>
        </>
      )}
    </Stack>
  );
}

export function AutomationOverviewPage() {
  return <AutomationFrame>{(status) => <Overview status={status} />}</AutomationFrame>;
}

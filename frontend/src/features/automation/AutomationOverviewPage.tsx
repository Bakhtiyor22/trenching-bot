import {
  Alert,
  Anchor,
  Box,
  Group,
  ScrollArea,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import {
  useAllAutomationEvaluations,
  useAutomationDays,
  useBackfill,
  type AutomationDaySummary,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { Meter } from '../../components/Meter';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState } from '../../components/StateViews';
import { formatCount, formatRelative } from '../../lib/format';
import { paths } from '../../paths';
import {
  addDays,
  backfillDays,
  BASELINE_LABEL,
  capPercent,
  dayInfo,
  dayItemCounts,
  daySpanText,
  defaultScoredDay,
  GRID_DAY_CHOICES,
  parseEvaluationStatus,
  pendingText,
  statusGlyph,
  statusText,
  tradingDayOf,
  zoneDateTime,
  zoneLabel,
  zoneTime,
  type GridDays,
} from './automationModel';
import {
  AutomationFrame,
  BestOfDay,
  BuyStateBar,
  BuyStateLegend,
  DayLink,
  QueryGate,
  StatusAlerts,
  ZoneNote,
} from './AutomationShared';
import { BackfillStateChip } from './BackfillShared';
import { Scoreboard } from './Scoreboard';
import { StatusGrid, StatusGridLegend } from './StatusGrid';
import { VariationTrend } from './VariationTrend';

const ALL = 'ALL';
const STATUS_FILTERS: Array<{ value: typeof ALL | EvaluationStatus; label: string }> = [
  { value: ALL, label: 'All' },
  ...(['SUCCESS', 'NO_SUCCESS', 'NO_RESULT', 'PENDING'] as const).map((s) => ({
    value: s,
    label: `${statusGlyph[s]} ${statusText[s]}`,
  })),
];
const RECENT_DAYS = 5;
const DEFAULT_SPAN: GridDays = '14';
/** At or above this share of the 24 h cap, the meter turns to the attention tone. */
const CAP_ATTENTION_PERCENT = 90;
const MINUTE_MS = 60_000;

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
      <Group gap="xs" wrap="wrap">
        <Anchor component={Link} to={paths.automationBackfill(id)} fw={600} size="sm">
          {backfillDays(b)}
        </Anchor>
        <BackfillStateChip state={b.state} />
      </Group>
      <Text size="xs" c="dimmed" ff="monospace">
        {formatCount(b.requestsSpent)} of {formatCount(b.requestBudget)} requests
        {b.scannedTo && b.state === 'SCANNING'
          ? ` · read to ${zoneDateTime(b.scannedTo, zone)}`
          : ''}
      </Text>
    </Stack>
  );
}

/**
 * "19 h ago" in the KPI's own figure style (the shared RelativeTime sets a small text size); the UTC instant is in
 * the title. It re-reads the clock every minute.
 */
function Ago({ iso }: { iso: string }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), MINUTE_MS);
    return () => window.clearInterval(timer);
  }, []);
  return (
    <time dateTime={iso} title={`${iso} (UTC)`}>
      {formatRelative(iso, now)}
    </time>
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
  const percent = capPercent(status.requestsLast24h, status.maxRequestsPer24h);
  return (
    <KpiStrip label="Automation status" min={180}>
      <Kpi
        label="Mode"
        text
        hint={
          status.mode === 'ALWAYS'
            ? `plan ${status.sweepPlanId}`
            : `${zone} · ${status.inPollWindow ? 'in window now' : 'outside the window'} · plan ${status.sweepPlanId}`
        }
      >
        {status.mode === 'ALWAYS' ? 'Always on' : `${status.pollFrom}–${status.pollTo}`}
      </Kpi>
      <Kpi
        label="Last check"
        text
        hint={
          status.nextTickAt ? `next ${zoneTime(status.nextTickAt, status.zone)} ${zone}` : undefined
        }
      >
        {status.lastTickAt ? <Ago iso={status.lastTickAt} /> : '—'}
      </Kpi>
      <Kpi
        label="Requests, last 24 h"
        hint={`${formatCount(status.maxRequestsPer24h)} cap, shared with manual captures and backfills`}
      >
        <Stack gap={6}>
          <span>
            {formatCount(status.requestsLast24h)}
            <span className="console-unit">/ {formatCount(status.maxRequestsPer24h)}</span>
          </span>
          <Meter
            label="Requests used of the 24 hour cap"
            value={status.requestsLast24h}
            max={status.maxRequestsPer24h}
            color={
              percent >= CAP_ATTENTION_PERCENT ? 'var(--console-rejected)' : 'var(--console-info)'
            }
          />
        </Stack>
      </Kpi>
      <Kpi label="Covered since" text hint="live discovery; backfills reach further back">
        {status.coveredFrom ? zoneDateTime(status.coveredFrom, status.zone) : '—'}
      </Kpi>
      <Kpi label="Today" text hint={daySpanText(status.zone, status.tradingDayStart)}>
        {today ? (
          <Stack gap={4}>
            <DayLink day={today.day} />
            <BuyStateBar counts={dayItemCounts(today)} captureEnabled={status.captureEnabled} />
          </Stack>
        ) : (
          <Text span c="dimmed" inherit>
            No buys yet
          </Text>
        )}
      </Kpi>
      <Kpi label="Backfill" text hint="fills older days with past buys">
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
      <Table miw={820} verticalSpacing="xs" aria-label="Recent days">
        <Table.Thead>
          <Table.Tr>
            <Table.Th className="console-sticky">
              Day ({daySpanText(status.zone, status.tradingDayStart)})
            </Table.Th>
            <Table.Th>Buys</Table.Th>
            <Table.Th>Best variation</Table.Th>
            <Table.Th ta="right">Requests</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {days.map((d) => (
            <Table.Tr key={d.day}>
              <Table.Th scope="row" fw={400} className="console-sticky">
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
                {formatCount(d.requestsUsed)}
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
  const statusFilter = parseEvaluationStatus(params.get('status'));
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

  // The default scored day is picked from live data; pin it in the URL once chosen, so it does not jump on the
  // next refresh and a shared link shows the same day.
  const list = days.data?.items;
  const requestedDay = params.get('day');
  const scored = list ? (list.find((d) => d.day === requestedDay) ?? defaultScoredDay(list)) : null;
  const scoredDay = scored?.day ?? null;
  useEffect(() => {
    if (scoredDay && requestedDay !== scoredDay)
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('day', scoredDay);
          return next;
        },
        { replace: true },
      );
  }, [scoredDay, requestedDay, setParams]);

  return (
    <Stack gap="lg">
      <StatusAlerts status={status} />
      <QueryGate queries={[days, evaluations]} loadingLabel="Loading days and scores…">
        {() => {
          const items = days.data!.items;
          const all = evaluations.data!;
          const todaySummary = items.find((d) => d.day === today && d.buys > 0) ?? null;
          const labels = [...new Set(all.map((e) => e.label))].sort();
          const variation = params.get('variation') ?? BASELINE_LABEL;
          const selected = labels.includes(variation) ? variation : (labels[0] ?? BASELINE_LABEL);
          const dayRows = scored ? all.filter((e) => e.day === scored.day) : [];
          const board = dayRows.filter((e) => !statusFilter || e.status === statusFilter);
          const allPending = items.length > 0 && items.every((d) => d.bestStatus === 'PENDING');
          const anyBuys = items.some((d) => d.buys > 0);
          const daysShown = items.map((d) => d.day);
          return (
            <Stack gap="lg">
              <StatusStrip status={status} today={todaySummary} />
              {!anyBuys && labels.length === 0 ? (
                <EmptyState
                  title="No buys in these days yet"
                  description={`A trading day runs ${daySpanText(status.zone, status.tradingDayStart)}. Buys appear here once discovery finds them; use Run discovery now to scan immediately, or a backfill for older days.`}
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
                          onChange={(v) => set('day', v)}
                          data={items.map((d) => ({
                            value: d.day,
                            label: `${dayInfo(d.day).label}${d.bestStatus === 'PENDING' ? ' (pending)' : ''}`,
                          }))}
                          allowDeselect={false}
                          w={{ base: '100%', sm: 240 }}
                        />
                        {/* Five choices are wider than a phone; they scroll inside their own row. */}
                        <Box maw="100%" style={{ overflowX: 'auto' }}>
                          <SegmentedControl
                            size="xs"
                            aria-label="Show status"
                            value={statusFilter ?? ALL}
                            onChange={(v) => set('status', v === ALL ? null : v)}
                            data={STATUS_FILTERS}
                          />
                        </Box>
                        {scored && dayRows[0] && (
                          <Text size="xs" c="dimmed" pb={8}>
                            Window: {dayInfo(dayRows[0].windowDays[0] ?? scored.day).short} →{' '}
                            {dayInfo(scored.day).short}
                            {dayRows[0].status === 'PENDING' &&
                              ` · ${pendingText(dayRows[0].pendingDay)}`}
                          </Text>
                        )}
                      </Group>
                      {board.length === 0 ? (
                        <Text size="sm" c="dimmed">
                          {statusFilter
                            ? `No variation is ${statusText[statusFilter].toLowerCase()} for this day. `
                            : 'No rows for this day. '}
                          {statusFilter && (
                            <Anchor
                              component="button"
                              size="sm"
                              onClick={() => set('status', null)}
                            >
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
                          ? `Trend · the default (${BASELINE_LABEL}) with the other variations as context`
                          : `Trend · ${selected} against the default (${BASELINE_LABEL})`
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
                    <RecentDays days={items.slice(0, RECENT_DAYS)} status={status} />
                  </Section>
                </>
              )}
            </Stack>
          );
        }}
      </QueryGate>
    </Stack>
  );
}

export function AutomationOverviewPage() {
  return <AutomationFrame>{(status) => <Overview status={status} />}</AutomationFrame>;
}

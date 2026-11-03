import { Alert, Group, ScrollArea, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useSearchParams } from 'react-router';
import {
  MAX_DAYS_PER_LIST,
  useAutomationDays,
  type AutomationDaySummary,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { StatusChip } from '../../components/StatusChip';
import { formatCount } from '../../lib/format';
import {
  addDays,
  dateProblem,
  dayInfo,
  dayItemCounts,
  daySpanText,
  daysBetween,
  parseEvaluationStatus,
  statusGlyph,
  statusText,
  tradingDayOf,
} from './automationModel';
import {
  AutomationFrame,
  BestOfDay,
  BuyStateBar,
  BuyStateLegend,
  DayLink,
} from './AutomationShared';

const PRESETS = [
  { value: '7', label: 'Last 7 days' },
  { value: '14', label: 'Last 14 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '62', label: 'Last 62 days' },
  { value: 'custom', label: 'From – to dates' },
];
const DEFAULT_PRESET = '14';
const ANY = 'ANY';
const HEALTH_FILTERS = ['ISSUES', 'INCOMPLETE'] as const;
type HealthFilter = (typeof HEALTH_FILTERS)[number];
const FILTER_WIDTH = { base: '100%', sm: 170 } as const;

function parseHealth(value: string | null): HealthFilter | null {
  return (HEALTH_FILTERS as readonly string[]).includes(value ?? '')
    ? (value as HealthFilter)
    : null;
}

/** Status and health filter the at most 62 rows the API returns for the span, on the page. */
function keep(
  day: AutomationDaySummary,
  status: EvaluationStatus | null,
  health: HealthFilter | null,
) {
  if (status && day.bestStatus !== status) return false;
  if (health === 'ISSUES' && day.failed + day.abandoned === 0) return false;
  if (health === 'INCOMPLETE' && day.complete) return false;
  return true;
}

/** Where a day stands: complete, still running (today), not complete yet, or not scanned in full. */
function Coverage({ day, today }: { day: AutomationDaySummary; today: string }) {
  if (day.complete)
    return (
      <StatusChip tone="neutral" glyph="✓">
        Complete
      </StatusChip>
    );
  if (day.day > today)
    return (
      <Text size="sm" c="dimmed">
        Not started yet
      </Text>
    );
  if (day.day === today) return <StatusChip tone="running">In progress</StatusChip>;
  return (
    <Text size="sm" c="dimmed">
      {day.covered ? 'Covered · not complete' : 'Not fully covered'}
    </Text>
  );
}

/** A day's buys; a day with none says whether it was scanned at all, so unknown never reads as none. */
function Buys({
  day,
  today,
  captureEnabled,
}: {
  day: AutomationDaySummary;
  today: string;
  captureEnabled: boolean;
}) {
  if (day.buys > 0)
    return <BuyStateBar counts={dayItemCounts(day)} captureEnabled={captureEnabled} />;
  const text =
    day.day > today
      ? 'Not started yet'
      : day.day === today
        ? 'None yet'
        : day.covered
          ? 'No buys'
          : 'Not scanned yet';
  return (
    <Text size="sm" c="dimmed">
      {text}
    </Text>
  );
}

function rangeProblem(from: string, to: string): string | null {
  const problem = dateProblem(from) ?? dateProblem(to);
  if (problem) return problem;
  const span = daysBetween(from, to) + 1;
  if (span < 1) return 'The start date is after the end date.';
  if (span > MAX_DAYS_PER_LIST)
    return `At most ${MAX_DAYS_PER_LIST} days at a time; this range has ${span}.`;
  return null;
}

function Days({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const today = tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
  const preset = PRESETS.some((p) => p.value === params.get('range'))
    ? params.get('range')!
    : DEFAULT_PRESET;
  const customFrom = params.get('from') ?? addDays(today, -6);
  const customTo = params.get('to') ?? today;
  const best = parseEvaluationStatus(params.get('status'));
  const health = parseHealth(params.get('health'));
  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value == null || value === '') next.delete(key);
        else next.set(key, value);
        return next;
      },
      { replace: true },
    );
  const custom = preset === 'custom';
  const rangeError = custom ? rangeProblem(customFrom, customTo) : null;
  const span = custom ? daysBetween(customFrom, customTo) + 1 : Number(preset);
  const from = custom ? customFrom : addDays(today, -(span - 1));
  const to = custom ? customTo : today;
  const days = useAutomationDays({ from, to }, rangeError == null);

  return (
    <Section
      title="Trading days"
      hint={`A trading day runs ${daySpanText(status.zone, status.tradingDayStart)} and is named by its start date, so a session of Decu's buys after midnight stays in the day it started. Newest first.`}
      actions={<BuyStateLegend captureEnabled={status.captureEnabled} />}
    >
      <Stack gap="md">
        <Group gap="sm" wrap="wrap" align="flex-end" role="search" aria-label="Day filters">
          <Select
            label="Days"
            value={preset}
            onChange={(v) => set('range', v === DEFAULT_PRESET ? null : v)}
            data={PRESETS}
            allowDeselect={false}
            w={FILTER_WIDTH}
          />
          {custom && (
            <>
              <TextInput
                type="date"
                label="From"
                value={customFrom}
                max={today}
                onChange={(e) => set('from', e.currentTarget.value)}
                w={FILTER_WIDTH}
              />
              <TextInput
                type="date"
                label="To"
                value={customTo}
                max={today}
                onChange={(e) => set('to', e.currentTarget.value)}
                w={FILTER_WIDTH}
              />
            </>
          )}
          <Select
            label="Best status"
            value={best ?? ANY}
            onChange={(v) => set('status', v === ANY ? null : v)}
            data={[
              { value: ANY, label: 'Any' },
              ...(['SUCCESS', 'NO_SUCCESS', 'NO_RESULT', 'PENDING'] as const).map((s) => ({
                value: s,
                label: `${statusGlyph[s]} ${statusText[s]}`,
              })),
            ]}
            allowDeselect={false}
            w={FILTER_WIDTH}
          />
          <Select
            label="Capture health"
            value={health ?? ANY}
            onChange={(v) => set('health', v === ANY ? null : v)}
            data={[
              { value: ANY, label: 'Any' },
              { value: 'ISSUES', label: 'With failed buys' },
              { value: 'INCOMPLETE', label: 'Not complete' },
            ]}
            allowDeselect={false}
            w={FILTER_WIDTH}
          />
        </Group>
        {rangeError ? (
          <Alert color="red" variant="light" role="alert">
            {rangeError}
          </Alert>
        ) : (
          <QueryView query={days} loadingLabel="Loading days…" errorTitle="Days unavailable">
            {(data) => {
              const rows = data.items.filter((d) => keep(d, best, health));
              if (rows.length === 0)
                return (
                  <EmptyState
                    title="No days match"
                    description={
                      best || health ? 'Clear a filter, or widen the range.' : 'Widen the range.'
                    }
                  />
                );
              return (
                <Stack gap="sm">
                  <ScrollArea type="auto" style={{ opacity: days.isPlaceholderData ? 0.6 : 1 }}>
                    <Table miw={960} verticalSpacing="xs" aria-label="Trading days">
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th className="console-sticky">Day</Table.Th>
                          <Table.Th>Coverage</Table.Th>
                          <Table.Th ta="right">Sessions</Table.Th>
                          <Table.Th>Buys</Table.Th>
                          <Table.Th ta="right">Below filter</Table.Th>
                          <Table.Th>Best variation</Table.Th>
                          <Table.Th ta="right">Requests</Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {rows.map((d) => (
                          <Table.Tr key={d.day}>
                            <Table.Th scope="row" fw={400} className="console-sticky">
                              <DayLink day={d.day} />
                              {d.day === today && (
                                <Text span size="xs" c="dimmed">
                                  {' '}
                                  · today
                                </Text>
                              )}
                            </Table.Th>
                            <Table.Td>
                              <Coverage day={d} today={today} />
                            </Table.Td>
                            <Table.Td ta="right" ff="monospace">
                              {formatCount(d.sessions)}
                            </Table.Td>
                            <Table.Td>
                              <Buys day={d} today={today} captureEnabled={status.captureEnabled} />
                            </Table.Td>
                            <Table.Td ta="right" ff="monospace">
                              {formatCount(d.belowFilter)}
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
                  <Text size="sm" c="dimmed">
                    {formatCount(rows.length)} of {formatCount(data.items.length)} days ·{' '}
                    {dayInfo(data.from).label} → {dayInfo(data.to).label}
                  </Text>
                </Stack>
              );
            }}
          </QueryView>
        )}
      </Stack>
    </Section>
  );
}

export function AutomationDaysPage() {
  return <AutomationFrame>{(status) => <Days status={status} />}</AutomationFrame>;
}

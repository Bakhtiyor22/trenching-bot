import {
  Alert,
  Badge,
  Group,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { useSearchParams } from 'react-router';
import {
  MAX_DAYS_PER_LIST,
  useAutomationDays,
  type AutomationDaySummary,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import {
  addDays,
  dayInfo,
  dayItemCounts,
  daySpanText,
  daysBetween,
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
type HealthFilter = 'ISSUES' | 'INCOMPLETE';
const DATE = /^\d{4}-\d{2}-\d{2}$/;

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

function Days({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const today = tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
  const preset = PRESETS.some((p) => p.value === params.get('range'))
    ? params.get('range')!
    : DEFAULT_PRESET;
  const customFrom = params.get('from') ?? addDays(today, -6);
  const customTo = params.get('to') ?? today;
  const best = (params.get('status') as EvaluationStatus | null) ?? null;
  const health = (params.get('health') as HealthFilter | null) ?? null;
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
  const span = custom ? daysBetween(customFrom, customTo) + 1 : Number(preset);
  const rangeError = !custom
    ? null
    : !DATE.test(customFrom) || !DATE.test(customTo)
      ? 'Enter both dates.'
      : span < 1
        ? 'The start date is after the end date.'
        : span > MAX_DAYS_PER_LIST
          ? `At most ${MAX_DAYS_PER_LIST} days at a time; this range has ${span}.`
          : null;
  const from = custom ? customFrom : addDays(today, -(span - 1));
  const to = custom ? customTo : today;
  const days = useAutomationDays({ from, to }, rangeError == null);
  const rows = (days.data?.items ?? []).filter((d) => keep(d, best, health));

  return (
    <Section
      title="Trading days"
      hint={`A trading day runs ${daySpanText(status.zone, status.tradingDayStart)} and is named by its start date, so one night of Decu's buys is one day. Newest first.`}
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
            w={170}
          />
          {custom && (
            <>
              <TextInput
                type="date"
                label="From"
                value={customFrom}
                max={today}
                onChange={(e) => set('from', e.currentTarget.value)}
                w={160}
              />
              <TextInput
                type="date"
                label="To"
                value={customTo}
                max={today}
                onChange={(e) => set('to', e.currentTarget.value)}
                w={160}
              />
            </>
          )}
          <Select
            label="Best status"
            value={best ?? 'ANY'}
            onChange={(v) => set('status', v === 'ANY' ? null : v)}
            data={[
              { value: 'ANY', label: 'Any' },
              { value: 'SUCCESS', label: '✓ Success' },
              { value: 'NO_SUCCESS', label: '✕ No success' },
              { value: 'NO_RESULT', label: '– No result' },
              { value: 'PENDING', label: '◷ Pending' },
            ]}
            allowDeselect={false}
            w={160}
          />
          <Select
            label="Capture health"
            value={health ?? 'ANY'}
            onChange={(v) => set('health', v === 'ANY' ? null : v)}
            data={[
              { value: 'ANY', label: 'Any' },
              { value: 'ISSUES', label: 'With failed buys' },
              { value: 'INCOMPLETE', label: 'Not complete' },
            ]}
            allowDeselect={false}
            w={170}
          />
        </Group>
        {rangeError ? (
          <Alert color="red" variant="light" role="alert">
            {rangeError}
          </Alert>
        ) : days.isPending ? (
          <LoadingState label="Loading days…" />
        ) : days.isError ? (
          <ErrorState error={days.error} onRetry={() => void days.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="No days match"
            description={
              best || health ? 'Clear a filter, or widen the range.' : 'Widen the range.'
            }
          />
        ) : (
          <>
            <ScrollArea type="auto" style={{ opacity: days.isPlaceholderData ? 0.6 : 1 }}>
              <Table miw={960} aria-label="Trading days">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Day</Table.Th>
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
                      <Table.Th scope="row" fw={400}>
                        <DayLink day={d.day} />
                        {d.day === today && (
                          <Text span size="xs" c="dimmed">
                            {' '}
                            · today
                          </Text>
                        )}
                      </Table.Th>
                      <Table.Td>
                        {d.complete ? (
                          <Badge variant="light" color="gray" tt="none">
                            Complete
                          </Badge>
                        ) : d.day === today ? (
                          <Text size="sm" c="dimmed">
                            In progress
                          </Text>
                        ) : d.covered ? (
                          <Text size="sm" c="dimmed">
                            Covered · not complete
                          </Text>
                        ) : (
                          <Text size="sm" c="dimmed">
                            Not fully covered
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {d.sessions}
                      </Table.Td>
                      <Table.Td>
                        {d.buys === 0 ? (
                          <Text size="sm" c="dimmed">
                            No buys
                          </Text>
                        ) : (
                          <BuyStateBar
                            counts={dayItemCounts(d)}
                            captureEnabled={status.captureEnabled}
                          />
                        )}
                      </Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {d.belowFilter}
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
            <Text size="sm" c="dimmed">
              {rows.length} of {days.data.items.length} days · {dayInfo(days.data.from).label} →{' '}
              {dayInfo(days.data.to).label}
            </Text>
          </>
        )}
      </Stack>
    </Section>
  );
}

export function AutomationDaysPage() {
  return <AutomationFrame>{(status) => <Days status={status} />}</AutomationFrame>;
}

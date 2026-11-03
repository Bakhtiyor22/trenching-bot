import { Badge, Group, ScrollArea, Select, Stack, Table, Text } from '@mantine/core';
import { useSearchParams } from 'react-router';
import {
  useAutomationSessions,
  type AutomationStatus,
  type EvaluationStatus,
  type SessionHealth,
  type SessionSort,
} from '../../api/automation';
import { PagingFooter, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import {
  buyBuckets,
  nightKeyBefore,
  nightOf,
  nightStart,
  variationChange,
  zoneLabel,
  zoneTime,
} from './automationModel';
import {
  AutomationFrame,
  BuyStateBar,
  BuyStateLegend,
  NightLink,
  StatusChip,
} from './AutomationShared';

const PAGE_SIZE = 20;
const RANGES: Array<{ value: string; label: string; nights: number | null }> = [
  { value: '7', label: 'Last 7 nights', nights: 7 },
  { value: '30', label: 'Last 30 nights', nights: 30 },
  { value: 'all', label: 'All nights', nights: null },
];
const DEFAULT_RANGE = '30';

function Nights({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const range =
    RANGES.find((r) => r.value === params.get('range')) ??
    RANGES.find((r) => r.value === DEFAULT_RANGE)!;
  const best = (params.get('status') as EvaluationStatus | null) ?? null;
  const health = (params.get('health') as SessionHealth | null) ?? null;
  const sort: SessionSort = params.get('sort') === 'createdAt,desc' ? 'createdAt,desc' : 'status';
  const page = Math.max(1, Number(params.get('page') ?? 1));
  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value == null) next.delete(key);
        else next.set(key, value);
        if (key !== 'page') next.delete('page');
        return next;
      },
      { replace: true },
    );
  const from =
    range.nights == null
      ? null
      : nightStart(
          nightKeyBefore(new Date().toISOString(), status.zone, range.nights - 1),
          status.zone,
        );
  const sessions = useAutomationSessions({
    page: page - 1,
    size: PAGE_SIZE,
    from,
    status: best,
    health,
    sort,
  });
  const zone = zoneLabel(status.zone);

  return (
    <Section
      title="Nights"
      hint={`A night starts with Decu's first buy and ends 60 minutes after the last one. Times in ${zone}.`}
      actions={<BuyStateLegend captureEnabled={status.captureEnabled} />}
    >
      <Stack gap="md">
        <Group gap="sm" wrap="wrap" align="flex-end" role="search" aria-label="Night filters">
          <Select
            label="Range"
            value={range.value}
            onChange={(v) => set('range', v === DEFAULT_RANGE ? null : v)}
            data={RANGES.map((r) => ({ value: r.value, label: r.label }))}
            allowDeselect={false}
            w={170}
          />
          <Select
            label="Best status"
            value={best ?? 'ANY'}
            onChange={(v) => set('status', v === 'ANY' ? null : v)}
            data={[
              { value: 'ANY', label: 'Any' },
              { value: 'SUCCESS', label: '✓ Success' },
              { value: 'NO_SUCCESS', label: '✕ No success' },
              { value: 'NO_RESULT', label: '– No result' },
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
              { value: 'ISSUES', label: 'With issues' },
              { value: 'OK', label: 'No issues' },
            ]}
            allowDeselect={false}
            w={160}
          />
          <Select
            label="Sort"
            value={sort}
            onChange={(v) => set('sort', v === 'status' ? null : v)}
            data={[
              { value: 'status', label: 'Best result first' },
              { value: 'createdAt,desc', label: 'Newest first' },
            ]}
            allowDeselect={false}
            w={170}
          />
        </Group>
        {sessions.isPending ? (
          <LoadingState label="Loading nights…" />
        ) : sessions.isError ? (
          <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} />
        ) : sessions.data.items.length === 0 ? (
          <EmptyState title="No nights match" description="Widen the range or clear a filter." />
        ) : (
          <>
            <ScrollArea type="auto" style={{ opacity: sessions.isPlaceholderData ? 0.6 : 1 }}>
              <Table miw={960} aria-label="Nights">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Night ({zone})</Table.Th>
                    <Table.Th>State</Table.Th>
                    <Table.Th>Buys</Table.Th>
                    <Table.Th ta="right">Below filter</Table.Th>
                    <Table.Th>Best variation</Table.Th>
                    <Table.Th>Capture</Table.Th>
                    <Table.Th ta="right">Requests</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {sessions.data.items.map((s) => {
                    const issues = buyBuckets(s.itemCounts).issues;
                    return (
                      <Table.Tr key={s.id}>
                        <Table.Th scope="row" fw={400}>
                          <Stack gap={0}>
                            <NightLink id={s.id}>
                              {nightOf(s.firstBuyAt, status.zone).label}
                            </NightLink>
                            <Text size="xs" c="dimmed" ff="monospace">
                              {zoneTime(s.firstBuyAt, status.zone)} →{' '}
                              {zoneTime(s.lastBuyAt, status.zone)}
                            </Text>
                          </Stack>
                        </Table.Th>
                        <Table.Td>
                          {s.state === 'CLOSED' ? (
                            <Text size="sm">
                              Closed{s.closeReason === 'MANUAL' ? ' by hand' : ''}
                            </Text>
                          ) : (
                            <Badge
                              variant="light"
                              color={s.state === 'OPEN' ? 'teal' : 'amber'}
                              tt="none"
                            >
                              {s.state === 'OPEN' ? 'Open' : 'Closing'}
                            </Badge>
                          )}
                        </Table.Td>
                        <Table.Td>
                          <BuyStateBar
                            counts={s.itemCounts}
                            captureEnabled={status.captureEnabled}
                          />
                        </Table.Td>
                        <Table.Td ta="right" ff="monospace">
                          {s.belowFilterCount}
                        </Table.Td>
                        <Table.Td>
                          {s.bestStatus ? (
                            <Group gap={6} wrap="nowrap">
                              <StatusChip status={s.bestStatus} />
                              <Text span ff="monospace" size="sm" fw={600}>
                                {s.bestLabel}
                              </Text>
                              <Text span size="xs" c="dimmed" visibleFrom="lg">
                                {s.bestLabel
                                  ? variationChange(status.sweepPlanId, s.bestLabel)
                                  : null}
                              </Text>
                            </Group>
                          ) : (
                            <Text size="sm" c="dimmed">
                              Not scored yet
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>
                          {issues > 0 ? (
                            <Badge
                              variant="light"
                              color="amber"
                              tt="none"
                              leftSection={<span aria-hidden="true">⚠</span>}
                            >
                              {issues} with issues
                            </Badge>
                          ) : (
                            <Text size="sm" c="dimmed">
                              OK
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td ta="right" ff="monospace">
                          {s.requestsUsed.toLocaleString('en-US')}
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </ScrollArea>
            <PagingFooter
              page={page}
              pages={Math.ceil(sessions.data.total / PAGE_SIZE)}
              total={sessions.data.total}
              onChange={(p) => set('page', p === 1 ? null : String(p))}
              noun="nights"
            />
          </>
        )}
      </Stack>
    </Section>
  );
}

export function AutomationNightsPage() {
  return <AutomationFrame>{(status) => <Nights status={status} />}</AutomationFrame>;
}

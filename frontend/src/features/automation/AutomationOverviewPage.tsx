import { Anchor, Box, Button, Group, ScrollArea, Select, Stack, Table, Text } from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import {
  useAllAutomationEvaluations,
  useAutomationSessions,
  type AutomationSession,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { RelativeTime } from '../../components/DisplayValues';
import { Kpi, KpiStrip, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import {
  automationMode,
  BASELINE_LABEL,
  capPercent,
  GRID_NIGHT_CHOICES,
  type GridNights,
  nightKeyBefore,
  nightOf,
  nightStart,
  nightTitle,
  statusText,
  variationChange,
  zoneLabel,
  zoneTime,
} from './automationModel';
import {
  AutomationFrame,
  BuyStateBar,
  BuyStateLegend,
  NightLink,
  StatusAlerts,
  StatusChip,
  ZoneNote,
} from './AutomationShared';
import { Scoreboard } from './Scoreboard';
import { StatusGrid, StatusGridLegend } from './StatusGrid';
import { VariationTrend } from './VariationTrend';

const MODE_TEXT = { OFF: 'Off', DISCOVERY_ONLY: 'Discovery only', CAPTURING: 'Capturing' } as const;
const STATUS_FILTERS: Array<{ value: 'ALL' | EvaluationStatus; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'SUCCESS', label: '✓ Success' },
  { value: 'NO_SUCCESS', label: '✕ No success' },
  { value: 'NO_RESULT', label: '– No result' },
];
const RECENT_NIGHTS = 5;
const DEFAULT_NIGHTS: GridNights = '14';

function StatusStrip({
  status,
  tonight,
}: {
  status: AutomationStatus;
  tonight: AutomationSession | null;
}) {
  const zone = zoneLabel(status.zone);
  const mode = automationMode(status);
  const percent = capPercent(status.requestsLast24h, status.maxRequestsPer24h);
  return (
    <KpiStrip label="Automation status" min={190}>
      <Kpi label="Mode" hint={`plan ${status.sweepPlanId}`}>
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
        label="Polling window"
        hint={`${zone} · ${status.inPollWindow ? 'in window now' : 'outside the window'}`}
      >
        <Text fz="lg" ff="monospace">
          {status.pollFrom}–{status.pollTo}
        </Text>
      </Kpi>
      <Kpi
        label="Last check"
        hint={
          status.nextTickAt ? `next ${zoneTime(status.nextTickAt, status.zone)} ${zone}` : undefined
        }
      >
        <Text fz="lg" component="div">
          <RelativeTime iso={status.lastTickAt} />
        </Text>
      </Kpi>
      <Kpi
        label="Requests, last 24 h"
        hint={`${status.maxRequestsPer24h.toLocaleString('en-US')} cap, shared with manual captures`}
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
      <Kpi
        label="Tonight"
        hint={
          tonight
            ? `open · since ${zoneTime(tonight.firstBuyAt, status.zone)} ${zone}`
            : status.inPollWindow
              ? 'no buy yet tonight'
              : 'outside the polling window'
        }
      >
        {tonight ? (
          <Stack gap={4}>
            <NightLink id={tonight.id}>{nightTitle(tonight, status.zone)}</NightLink>
            <BuyStateBar counts={tonight.itemCounts} captureEnabled={status.captureEnabled} />
          </Stack>
        ) : (
          <Text fz="lg" c="dimmed">
            No open night
          </Text>
        )}
      </Kpi>
    </KpiStrip>
  );
}

function RecentNights({
  sessions,
  status,
}: {
  sessions: AutomationSession[];
  status: AutomationStatus;
}) {
  return (
    <ScrollArea type="auto">
      <Table miw={820} aria-label="Recent nights">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Night ({zoneLabel(status.zone)})</Table.Th>
            <Table.Th>State</Table.Th>
            <Table.Th>Buys</Table.Th>
            <Table.Th>Best variation</Table.Th>
            <Table.Th ta="right">Requests</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {sessions.map((s) => (
            <Table.Tr key={s.id}>
              <Table.Th scope="row" fw={400}>
                <Stack gap={0}>
                  <NightLink id={s.id}>{nightOf(s.firstBuyAt, status.zone).label}</NightLink>
                  <Text size="xs" c="dimmed" ff="monospace">
                    {zoneTime(s.firstBuyAt, status.zone)} → {zoneTime(s.lastBuyAt, status.zone)}
                  </Text>
                </Stack>
              </Table.Th>
              <Table.Td>
                {s.state === 'CLOSED' ? 'Closed' : s.state === 'OPEN' ? 'Open' : 'Closing'}
              </Table.Td>
              <Table.Td>
                <BuyStateBar counts={s.itemCounts} captureEnabled={status.captureEnabled} />
              </Table.Td>
              <Table.Td>
                {s.bestStatus ? (
                  <Group gap={6} wrap="nowrap">
                    <StatusChip status={s.bestStatus} />
                    <Text span ff="monospace" size="sm" fw={600}>
                      {s.bestLabel}
                    </Text>
                    <Text span size="xs" c="dimmed" visibleFrom="md">
                      {s.bestLabel ? variationChange(status.sweepPlanId, s.bestLabel) : null}
                    </Text>
                  </Group>
                ) : (
                  <Text size="sm" c="dimmed">
                    Not scored yet
                  </Text>
                )}
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {s.requestsUsed.toLocaleString('en-US')}
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
  const nights = (GRID_NIGHT_CHOICES as readonly string[]).includes(params.get('nights') ?? '')
    ? (params.get('nights') as GridNights)
    : DEFAULT_NIGHTS;
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
  const from = nightStart(
    nightKeyBefore(new Date().toISOString(), status.zone, Number(nights) - 1),
    status.zone,
  );
  const sessions = useAutomationSessions({
    page: 0,
    size: Number(nights) + 1,
    from,
    sort: 'createdAt,desc',
  });
  const evaluations = useAllAutomationEvaluations({ planId: status.sweepPlanId, from });

  if (sessions.isPending || evaluations.isPending)
    return <LoadingState label="Loading nights and scores…" />;
  if (sessions.isError)
    return <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} />;
  if (evaluations.isError)
    return <ErrorState error={evaluations.error} onRetry={() => void evaluations.refetch()} />;

  const rows = sessions.data.items;
  const tonight = rows.find((s) => s.id === status.openSessionId) ?? null;
  const all = evaluations.data;
  const scoredNights = rows.filter((s) => all.some((e) => e.sessionId === s.id));
  const scoredId = params.get('scored');
  const scored = scoredNights.find((s) => s.id === scoredId) ?? scoredNights[0] ?? null;
  const labels = [...new Set(all.map((e) => e.label))].sort();
  const variation = params.get('variation') ?? BASELINE_LABEL;
  const selected = labels.includes(variation) ? variation : (labels[0] ?? BASELINE_LABEL);
  const board = scored
    ? all.filter((e) => e.sessionId === scored.id && (!statusFilter || e.status === statusFilter))
    : [];
  const windowNights = scored
    ? (all.find((e) => e.sessionId === scored.id)?.windowSessionIds.length ?? 0)
    : 0;

  return (
    <Stack gap="lg">
      <StatusAlerts status={status} />
      <StatusStrip status={status} tonight={tonight} />
      {rows.length === 0 ? (
        <EmptyState
          title="No nights yet"
          description={`A night starts with Decu's first buy in the polling window (${status.pollFrom}–${status.pollTo} ${zoneLabel(status.zone)}) and ends after 60 minutes without a new buy.`}
        />
      ) : (
        <>
          <Section
            title="Scoreboard · which variation works"
            hint="Success = worst-case net P&L above 0 SOL over at least 10 signals in the 7-night window. Sorted Success › No success › No result, then by worst case. Worst case counts a position still open at the end as a loss of its entry cost."
            actions={<ZoneNote zone={status.zone} />}
          >
            <Stack gap="md">
              <Group gap="sm" wrap="wrap" align="flex-end">
                <Select
                  label="Scored up to"
                  placeholder="No scored night yet"
                  value={scored?.id ?? null}
                  onChange={(v) => set('scored', v, scoredNights[0]?.id)}
                  data={scoredNights.map((s, i) => ({
                    value: s.id,
                    label: `${nightOf(s.firstBuyAt, status.zone).label}${i === 0 ? ' (latest)' : ''}`,
                  }))}
                  allowDeselect={false}
                  disabled={scoredNights.length === 0}
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
                {scored && (
                  <Text size="xs" c="dimmed" pb={8}>
                    Window: the {windowNights} nights ending{' '}
                    {nightOf(scored.firstBuyAt, status.zone).label}
                    {windowNights < 7 && ' (fills up after 7 closed nights)'}
                  </Text>
                )}
              </Group>
              {!scored ? (
                <EmptyState
                  title="Not scored yet"
                  description={
                    status.captureEnabled
                      ? 'A night is scored once it and every earlier night are closed. Until 7 nights are closed every variation shows No result.'
                      : 'Discovery only: buys are counted, but nothing is replayed or scored.'
                  }
                />
              ) : board.length === 0 ? (
                <Text size="sm" c="dimmed">
                  No variation is {statusText[statusFilter!].toLowerCase()} for this night.{' '}
                  <Anchor component="button" size="sm" onClick={() => set('status', null)}>
                    Show all
                  </Anchor>
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
              title="Status by night"
              hint="Each cell scores the 7 nights ending that night. A variation that stays ✓ across a row is consistent, not lucky. Choose a variation to follow it below; click a cell to open its night."
              actions={<StatusGridLegend />}
            >
              <StatusGrid
                sessions={rows}
                evaluations={all}
                zone={status.zone}
                planId={status.sweepPlanId}
                labels={labels}
                selectedLabel={selected}
                onSelectLabel={(l) => set('variation', l, BASELINE_LABEL)}
                nights={nights}
                onNightsChange={(n) => set('nights', n, DEFAULT_NIGHTS)}
              />
            </Section>
          )}
          {labels.length > 0 && (
            <Section
              title={
                selected === BASELINE_LABEL
                  ? `The default (${BASELINE_LABEL}) by night, with the other variations as context`
                  : `${selected} against the default (${BASELINE_LABEL}), by night`
              }
              hint="Worst-case net P&L of the 7 nights ending each night. Success needs it above the 0 SOL line with at least 10 signals."
            >
              <VariationTrend
                sessions={rows}
                evaluations={all}
                zone={status.zone}
                planId={status.sweepPlanId}
                labels={labels}
                selected={selected}
                onSelect={(l) => set('variation', l, BASELINE_LABEL)}
              />
            </Section>
          )}
          <Section
            title="Recent nights"
            actions={
              <Group gap="md" wrap="wrap">
                <BuyStateLegend captureEnabled={status.captureEnabled} />
                <Anchor component={Link} to={paths.automationNights} size="sm">
                  All nights →
                </Anchor>
              </Group>
            }
          >
            <RecentNights sessions={rows.slice(0, RECENT_NIGHTS)} status={status} />
          </Section>
        </>
      )}
    </Stack>
  );
}

export function AutomationOverviewPage() {
  return <AutomationFrame>{(status) => <Overview status={status} />}</AutomationFrame>;
}

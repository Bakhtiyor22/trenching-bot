import {
  Anchor,
  Group,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import {
  useAutomationSessions,
  useAutomationSweepRuns,
  type AutomationStatus,
} from '../../api/automation';
import { Mint } from '../../components/DisplayValues';
import { PagingFooter, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import {
  addDays,
  dayInfo,
  dayStartInstant,
  tradingDayOf,
  OUTCOME_GROUPS,
  outcomeExplanation,
  outcomeLabel,
  planLabels,
  variationChange,
  zoneLabel,
  zoneTime,
} from './automationModel';
import { AutomationFrame, DayLink, SolValue } from './AutomationShared';

const PAGE_SIZE = 50;
/** Sessions offered by the session picker; the day range narrows them (the sweep-run API has no date filter). */
const NIGHT_CHOICES = 100;
const RANGES = [
  { value: '7', label: 'Last 7 days', nights: 7 },
  { value: '30', label: 'Last 30 days', nights: 30 },
  { value: 'all', label: 'All days', nights: null },
] as const;
const MINT_DEBOUNCE_MS = 300;

function SweepRuns({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const range = RANGES.find((r) => r.value === params.get('range')) ?? RANGES[1];
  const sessionId = params.get('session');
  const label = params.get('variation');
  const outcome = params.get('outcome');
  const page = Math.max(1, Number(params.get('page') ?? 1));
  const [mintInput, setMintInput] = useState(params.get('mint') ?? '');
  const [mint] = useDebouncedValue(mintInput.trim(), MINT_DEBOUNCE_MS);
  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value == null || value === '') next.delete(key);
        else next.set(key, value);
        if (key !== 'page') next.delete('page');
        return next;
      },
      { replace: true },
    );
  useEffect(() => {
    if ((params.get('mint') ?? '') !== mint) set('mint', mint || null);
    // Only the debounced input writes the URL.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mint]);

  const from =
    range.nights == null
      ? null
      : dayStartInstant(
          addDays(
            tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart),
            -(range.nights - 1),
          ),
          status.zone,
          status.tradingDayStart,
        );
  const nights = useAutomationSessions({
    page: 0,
    size: NIGHT_CHOICES,
    from,
  });
  const runs = useAutomationSweepRuns({
    sessionId,
    label,
    outcome,
    mint: mint || null,
    page: page - 1,
    size: PAGE_SIZE,
  });
  const nightById = new Map((nights.data?.items ?? []).map((s) => [s.id, s]));
  const labels = planLabels(status.sweepPlanId);
  const zone = zoneLabel(status.zone);

  return (
    <Section
      title="Sweep runs"
      hint={`Every variation replayed on every captured buy, newest buy first. Times in ${zone}. To see one day, pick one of its sessions.`}
    >
      <Stack gap="md">
        <Group gap="sm" wrap="wrap" align="flex-end" role="search" aria-label="Sweep run filters">
          <Select
            label="Sessions from"
            value={range.value}
            onChange={(v) => {
              set('range', v === '30' ? null : v);
            }}
            data={RANGES.map((r) => ({ value: r.value, label: r.label }))}
            allowDeselect={false}
            w={160}
          />
          <Select
            label="Session"
            placeholder="Any session"
            value={sessionId}
            onChange={(v) => set('session', v)}
            data={(nights.data?.items ?? []).map((s) => ({
              value: s.id,
              label: `${dayInfo(s.tradingDay).label} · ${zoneTime(s.firstBuyAt, status.zone)}${s.backfillId ? ' · backfill' : ''}${s.state === 'CLOSED' ? '' : ' (open)'}`,
            }))}
            clearable
            searchable
            nothingFoundMessage="No session in this range"
            w={200}
          />
          <Select
            label="Variation"
            placeholder="Any variation"
            value={label}
            onChange={(v) => set('variation', v)}
            data={labels.map((l) => {
              const change = variationChange(status.sweepPlanId, l);
              return { value: l, label: change ? `${l} · ${change}` : l };
            })}
            clearable
            w={230}
          />
          <Select
            label="Outcome"
            placeholder="Any outcome"
            value={outcome}
            onChange={(v) => set('outcome', v)}
            data={OUTCOME_GROUPS.map((g) => ({
              group: g.group,
              items: g.codes.map((code) => ({ value: code, label: outcomeLabel(code) })),
            }))}
            clearable
            searchable
            w={260}
          />
          <TextInput
            label="Token mint"
            placeholder="Exact mint address"
            value={mintInput}
            onChange={(e) => setMintInput(e.currentTarget.value)}
            w={260}
          />
        </Group>
        {runs.isPending ? (
          <LoadingState label="Loading sweep runs…" />
        ) : runs.isError ? (
          <ErrorState error={runs.error} onRetry={() => void runs.refetch()} />
        ) : runs.data.items.length === 0 ? (
          <EmptyState
            title="No sweep runs match"
            description="Clear a filter, or wait for tonight's buys to be replayed."
          />
        ) : (
          <>
            <ScrollArea type="auto" style={{ opacity: runs.isPlaceholderData ? 0.6 : 1 }}>
              <Table miw={1000} aria-label="Sweep runs">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Day</Table.Th>
                    <Table.Th>Buy ({zone})</Table.Th>
                    <Table.Th>Token</Table.Th>
                    <Table.Th>Variation</Table.Th>
                    <Table.Th>What happened</Table.Th>
                    <Table.Th>Signal</Table.Th>
                    <Table.Th ta="right">Realized</Table.Th>
                    <Table.Th ta="right">Worst case</Table.Th>
                    <Table.Th>Run</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {runs.data.items.map((r) => {
                    const night = nightById.get(r.sessionId);
                    return (
                      <Table.Tr key={`${r.itemId}-${r.planId}-${r.label}`}>
                        <Table.Td>
                          {night ? (
                            <DayLink day={night.tradingDay} itemId={r.itemId} />
                          ) : r.blockTime ? (
                            <DayLink
                              day={tradingDayOf(r.blockTime, status.zone, status.tradingDayStart)}
                              itemId={r.itemId}
                            />
                          ) : (
                            '—'
                          )}
                        </Table.Td>
                        <Table.Td ff="monospace">
                          {zoneTime(r.blockTime, status.zone, true)}
                        </Table.Td>
                        <Table.Td>
                          <Mint mint={r.mint} />
                        </Table.Td>
                        <Table.Td>
                          <Text span ff="monospace" fw={600} size="sm">
                            {r.label}
                          </Text>
                        </Table.Td>
                        <Table.Td>
                          <Tooltip
                            label={outcomeExplanation(r.outcome) ?? r.outcome}
                            multiline
                            w={320}
                          >
                            <Text span size="sm">
                              {outcomeLabel(r.outcome)}
                            </Text>
                          </Tooltip>
                        </Table.Td>
                        <Table.Td>
                          <Text size="sm" c={r.signal ? undefined : 'dimmed'}>
                            {r.signal ? 'Yes' : 'No'}
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
                            <Anchor
                              component={Link}
                              to={paths.run(r.runId)}
                              size="sm"
                              ff="monospace"
                            >
                              {r.runId.slice(0, 10)}…
                            </Anchor>
                          ) : (
                            <Text size="sm" c="dimmed">
                              no run
                            </Text>
                          )}
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </ScrollArea>
            <PagingFooter
              page={page}
              pages={Math.ceil(runs.data.total / PAGE_SIZE)}
              total={runs.data.total}
              onChange={(p) => set('page', p === 1 ? null : String(p))}
              noun="sweep runs"
            />
          </>
        )}
      </Stack>
    </Section>
  );
}

export function AutomationSweepRunsPage() {
  return <AutomationFrame>{(status) => <SweepRuns status={status} />}</AutomationFrame>;
}

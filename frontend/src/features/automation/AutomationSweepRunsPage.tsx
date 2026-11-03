import { Group, ScrollArea, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import {
  useAutomationSessions,
  useAutomationSweepRuns,
  type AutomationStatus,
} from '../../api/automation';
import { Mint } from '../../components/DisplayValues';
import { PagingFooter, Section } from '../../components/Panels';
import { QueryView, RefreshNotice } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import {
  addDays,
  dayInfo,
  dayStartInstant,
  tradingDayOf,
  OUTCOME_GROUPS,
  outcomeLabel,
  parsePage,
  planLabels,
  variationChange,
  zoneLabel,
  zoneTime,
} from './automationModel';
import { AutomationFrame, DayLink, OutcomeText, RunLink, SolValue } from './AutomationShared';

const PAGE_SIZE = 50;
/** Sessions offered by the session picker; the day range narrows them (the sweep-run API has no date filter). */
const SESSION_CHOICES = 100;
const RANGES = [
  { value: '7', label: 'Last 7 days', days: 7 },
  { value: '30', label: 'Last 30 days', days: 30 },
  { value: 'all', label: 'All days', days: null },
] as const;
const MINT_DEBOUNCE_MS = 300;
const FILTER_WIDTH = { base: '100%', sm: 200 } as const;
const WIDE_FILTER_WIDTH = { base: '100%', sm: 260 } as const;

function SweepRuns({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const range = RANGES.find((r) => r.value === params.get('range')) ?? RANGES[1];
  const sessionId = params.get('session');
  const label = params.get('variation');
  const outcome = params.get('outcome');
  const page = parsePage(params.get('page'));
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

  // A mint that is not a Solana address is never sent; the field says why.
  const mintError = mint !== '' && !isSolanaAddress(mint) ? 'Not a valid mint address' : null;
  const from =
    range.days == null
      ? null
      : dayStartInstant(
          addDays(
            tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart),
            -(range.days - 1),
          ),
          status.zone,
          status.tradingDayStart,
        );
  const sessions = useAutomationSessions({ page: 0, size: SESSION_CHOICES, from });
  const runs = useAutomationSweepRuns({
    sessionId,
    label,
    outcome,
    mint: mint && !mintError ? mint : null,
    page: page - 1,
    size: PAGE_SIZE,
  });
  const sessionById = new Map((sessions.data?.items ?? []).map((s) => [s.id, s]));
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
            onChange={(v) => set('range', v === '30' ? null : v)}
            data={RANGES.map((r) => ({ value: r.value, label: r.label }))}
            allowDeselect={false}
            w={FILTER_WIDTH}
          />
          <Select
            label="Session"
            placeholder="Any session"
            value={sessionId}
            onChange={(v) => set('session', v)}
            data={(sessions.data?.items ?? []).map((s) => ({
              value: s.id,
              label: `${dayInfo(s.tradingDay).label} · ${zoneTime(s.firstBuyAt, status.zone)}${s.backfillId ? ' · backfill' : ''}${s.state === 'CLOSED' ? '' : ' (open)'}`,
            }))}
            clearable
            searchable
            nothingFoundMessage="No session in this range"
            w={FILTER_WIDTH}
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
            w={WIDE_FILTER_WIDTH}
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
            w={WIDE_FILTER_WIDTH}
          />
          <TextInput
            label="Token mint"
            placeholder="Exact mint address"
            value={mintInput}
            onChange={(e) => setMintInput(e.currentTarget.value)}
            error={mintError}
            w={WIDE_FILTER_WIDTH}
            styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
          />
        </Group>
        {sessions.isError && (
          <RefreshNotice
            error={sessions.error}
            updatedAt={sessions.dataUpdatedAt}
            onRetry={() => void sessions.refetch()}
          />
        )}
        <QueryView
          query={runs}
          loadingLabel="Loading sweep runs…"
          errorTitle="Sweep runs unavailable"
        >
          {(data) =>
            data.items.length === 0 ? (
              <EmptyState
                title="No sweep runs match"
                description="Clear a filter, or wait for today's buys to be replayed."
              />
            ) : (
              <>
                <ScrollArea type="auto" style={{ opacity: runs.isPlaceholderData ? 0.6 : 1 }}>
                  <Table miw={1000} verticalSpacing="xs" aria-label="Sweep runs">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th className="console-sticky">Day</Table.Th>
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
                      {data.items.map((r) => {
                        const session = sessionById.get(r.sessionId);
                        const day = session
                          ? session.tradingDay
                          : r.blockTime
                            ? tradingDayOf(r.blockTime, status.zone, status.tradingDayStart)
                            : null;
                        return (
                          <Table.Tr key={`${r.itemId}-${r.planId}-${r.label}`}>
                            <Table.Th scope="row" fw={400} className="console-sticky">
                              {day ? <DayLink day={day} itemId={r.itemId} /> : '—'}
                            </Table.Th>
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
                              <OutcomeText outcome={r.outcome} />
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
                              <RunLink runId={r.runId} />
                            </Table.Td>
                          </Table.Tr>
                        );
                      })}
                    </Table.Tbody>
                  </Table>
                </ScrollArea>
                <PagingFooter
                  page={page}
                  pages={Math.ceil(data.total / PAGE_SIZE)}
                  total={data.total}
                  onChange={(p) => set('page', p === 1 ? null : String(p))}
                  noun="sweep runs"
                />
              </>
            )
          }
        </QueryView>
      </Stack>
    </Section>
  );
}

export function AutomationSweepRunsPage() {
  return <AutomationFrame>{(status) => <SweepRuns status={status} />}</AutomationFrame>;
}

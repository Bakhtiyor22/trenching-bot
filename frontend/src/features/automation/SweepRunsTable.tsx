import { Group, ScrollArea, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import {
  useAutomationSessions,
  useAutomationSweepRuns,
  type AutomationStatus,
  type AutomationSweepRunRow,
} from '../../api/automation';
import { TokenLink } from '../../components/DisplayValues';
import { PagingFooter } from '../../components/Panels';
import { QueryView, RefreshNotice } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import {
  dayFilter,
  dayInfo,
  OUTCOME_GROUPS,
  outcomeLabel,
  parsePage,
  tradingDayOf,
  variationName,
  zoneLabel,
  zoneTime,
} from './automationModel';
import { DayLink, OutcomeText, RunLink, SolValue, VariationLink } from './AutomationShared';
import { useVariationBook } from './useVariationBook';

/** Sweep runs per page. */
export const SWEEP_RUN_PAGE_SIZE = 50;
/** Sessions offered by the session picker, newest first within the day range. */
const SESSION_CHOICES = 100;
const MINT_DEBOUNCE_MS = 300;
const FILTER_WIDTH = { base: '100%', sm: 170 } as const;
const WIDE_FILTER_WIDTH = { base: '100%', sm: 260 } as const;

/** The URL keys of the run filters; `page` is one-based and dropped on page 1. */
const RUN_FILTER_KEYS = {
  from: 'from',
  to: 'to',
  outcome: 'outcome',
  mint: 'mint',
  page: 'page',
  variation: 'variation',
  session: 'session',
} as const;

function RunsTable({
  rows,
  status,
  showVariation,
  faded,
}: {
  rows: AutomationSweepRunRow[];
  status: AutomationStatus;
  showVariation: boolean;
  faded: boolean;
}) {
  const zone = zoneLabel(status.zone);
  return (
    <ScrollArea type="auto" style={{ opacity: faded ? 0.6 : 1 }}>
      <Table miw={showVariation ? 1080 : 980} verticalSpacing="xs" aria-label="Sweep runs">
        <Table.Thead>
          <Table.Tr>
            <Table.Th className="console-sticky">Day</Table.Th>
            <Table.Th>Buy ({zone})</Table.Th>
            <Table.Th>Token</Table.Th>
            {showVariation && <Table.Th>Variation</Table.Th>}
            <Table.Th>What happened</Table.Th>
            <Table.Th>Signal</Table.Th>
            <Table.Th ta="right">Realized</Table.Th>
            <Table.Th ta="right">Worst case</Table.Th>
            <Table.Th>Run</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((r) => {
            const day = r.blockTime
              ? tradingDayOf(r.blockTime, status.zone, status.tradingDayStart)
              : null;
            return (
              <Table.Tr key={`${r.itemId}-${r.planId}-${r.label}`}>
                <Table.Th scope="row" fw={400} className="console-sticky">
                  {day ? <DayLink day={day} itemId={r.itemId} /> : '—'}
                </Table.Th>
                <Table.Td ff="monospace">{zoneTime(r.blockTime, status.zone, true)}</Table.Td>
                <Table.Td>
                  <TokenLink mint={r.mint} />
                </Table.Td>
                {showVariation && (
                  <Table.Td>
                    <VariationLink label={r.label} />
                  </Table.Td>
                )}
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
  );
}

/**
 * Sweep runs with their filters, all in the URL: trading days `from`–`to` (both included), outcome, token, and on the
 * all-runs page also variation and session; one-based `page`, 50 rows each. A fixed `label` shows one variation's runs
 * only. Newest buy first.
 */
export function SweepRunsView({
  status,
  label,
  planId,
  emptyText,
}: {
  status: AutomationStatus;
  /** One variation's runs: no variation filter or column. */
  label?: string;
  planId?: string;
  /** What an empty, unfiltered list means. */
  emptyText: string;
}) {
  const [params, setParams] = useSearchParams();
  const fixedLabel = label != null;
  const book = useVariationBook();
  const fromDay = params.get(RUN_FILTER_KEYS.from);
  const toDay = params.get(RUN_FILTER_KEYS.to);
  const outcome = params.get(RUN_FILTER_KEYS.outcome);
  const variation = fixedLabel ? label : params.get(RUN_FILTER_KEYS.variation);
  const sessionId = fixedLabel ? null : params.get(RUN_FILTER_KEYS.session);
  const page = parsePage(params.get(RUN_FILTER_KEYS.page));
  const [mintInput, setMintInput] = useState(params.get(RUN_FILTER_KEYS.mint) ?? '');
  const [mint] = useDebouncedValue(mintInput.trim(), MINT_DEBOUNCE_MS);
  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value == null || value === '') next.delete(key);
        else next.set(key, value);
        if (key !== RUN_FILTER_KEYS.page) next.delete(RUN_FILTER_KEYS.page);
        return next;
      },
      { replace: true },
    );
  useEffect(() => {
    if ((params.get(RUN_FILTER_KEYS.mint) ?? '') !== mint) set(RUN_FILTER_KEYS.mint, mint || null);
    // Only the debounced input writes the URL.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mint]);

  // A mint that is not a Solana address, or a day that is not a real date, is never sent; the field says why.
  const mintError = mint !== '' && !isSolanaAddress(mint) ? 'Not a valid mint address' : null;
  const days = dayFilter(fromDay, toDay, status.zone, status.tradingDayStart);
  const datesValid = Object.keys(days.errors).length === 0;
  const sessions = useAutomationSessions(
    {
      page: 0,
      size: SESSION_CHOICES,
      from: days.fromInstant,
      to: days.toInstant,
    },
    !fixedLabel && datesValid,
  );
  const runs = useAutomationSweepRuns(
    {
      sessionId,
      planId: planId ?? null,
      label: variation,
      outcome,
      mint: mint && !mintError ? mint : null,
      from: days.fromInstant,
      to: days.toInstant,
      page: page - 1,
      size: SWEEP_RUN_PAGE_SIZE,
    },
    datesValid,
  );
  const filtered = Boolean(
    fromDay || toDay || outcome || mint || sessionId || (!fixedLabel && variation),
  );

  return (
    <Stack gap="md">
      <Group gap="sm" wrap="wrap" align="flex-start" role="search" aria-label="Sweep run filters">
        <TextInput
          type="date"
          label="From day"
          value={fromDay ?? ''}
          onChange={(e) => set(RUN_FILTER_KEYS.from, e.currentTarget.value)}
          error={days.errors.from}
          w={FILTER_WIDTH}
        />
        <TextInput
          type="date"
          label="To day"
          value={toDay ?? ''}
          onChange={(e) => set(RUN_FILTER_KEYS.to, e.currentTarget.value)}
          error={days.errors.to}
          w={FILTER_WIDTH}
        />
        {!fixedLabel && (
          <Select
            label="Variation"
            placeholder="Any variation"
            value={variation}
            onChange={(v) => set(RUN_FILTER_KEYS.variation, v)}
            data={book.variations.map((v) => ({
              value: v.label,
              label: `${variationName(book, v.label)}${v.state === 'ARCHIVED' ? ' (archived)' : ''}`,
            }))}
            clearable
            searchable
            w={WIDE_FILTER_WIDTH}
          />
        )}
        {!fixedLabel && (
          <Select
            label="Session"
            placeholder="Any session"
            value={sessionId}
            onChange={(v) => set(RUN_FILTER_KEYS.session, v)}
            data={(sessions.data?.items ?? []).map((s) => ({
              value: s.id,
              label: `${dayInfo(s.tradingDay).label} · ${zoneTime(s.firstBuyAt, status.zone)}${s.backfillId ? ' · backfill' : ''}${s.state === 'CLOSED' ? '' : ' (open)'}`,
            }))}
            clearable
            searchable
            nothingFoundMessage="No session in these days"
            w={FILTER_WIDTH}
          />
        )}
        <Select
          label="Outcome"
          placeholder="Any outcome"
          value={outcome}
          onChange={(v) => set(RUN_FILTER_KEYS.outcome, v)}
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
      {sessions.isError && sessions.data && (
        <RefreshNotice
          error={sessions.error}
          updatedAt={sessions.dataUpdatedAt}
          onRetry={() => void sessions.refetch()}
        />
      )}
      {!datesValid ? (
        <EmptyState
          title="Check the days"
          description="Runs are listed once both days are real dates and the start is not after the end."
        />
      ) : (
        <QueryView
          query={runs}
          loadingLabel="Loading sweep runs…"
          errorTitle="Sweep runs unavailable"
        >
          {(data) =>
            data.items.length === 0 ? (
              <EmptyState
                title={filtered ? 'No sweep runs match' : 'No sweep runs yet'}
                description={filtered ? 'Clear a filter, or widen the days.' : emptyText}
              />
            ) : (
              <>
                <RunsTable
                  rows={data.items}
                  status={status}
                  showVariation={!fixedLabel}
                  faded={runs.isPlaceholderData}
                />
                <PagingFooter
                  page={page}
                  pages={Math.ceil(data.total / SWEEP_RUN_PAGE_SIZE)}
                  total={data.total}
                  onChange={(p) => set(RUN_FILTER_KEYS.page, p === 1 ? null : String(p))}
                  noun="sweep runs"
                />
              </>
            )
          }
        </QueryView>
      )}
    </Stack>
  );
}

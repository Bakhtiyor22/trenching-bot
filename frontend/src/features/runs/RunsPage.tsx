import {
  Affix,
  Anchor,
  Box,
  Button,
  Checkbox,
  Group,
  Kbd,
  Pagination,
  Paper,
  ScrollArea,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Tooltip,
  UnstyledButton,
  VisuallyHidden,
} from '@mantine/core';
import { useHotkeys } from '@mantine/hooks';
import { useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useDatasets, useRuns } from '../../api/queries';
import type { Dataset, RunSummary } from '../../api/types';
import { Mint, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { IconCompare, IconPlay, IconSearch } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { RefreshNotice } from '../../components/QueryView';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, shortId } from '../../lib/format';
import { MAX_COMPARED_RUNS, paths } from '../../paths';
import {
  filterRuns,
  parseOutcome,
  parseSort,
  serializeSort,
  sortRuns,
  toggleSort,
  type Outcome,
  type Sort,
  type SortKey,
} from './runList';

const PAGE_SIZE = 20;
const QUERY_PARAM = 'q';
const OUTCOME_PARAM = 'outcome';
const SORT_PARAM = 'sort';

/** The compare checkbox and the run id stay in view while the table scrolls sideways. */
const SELECT_COLUMN_WIDTH = 40;
const STICKY_SELECT = { left: 0 } as const;
const STICKY_RUN = { left: SELECT_COLUMN_WIDTH } as const;

const OUTCOMES: Array<{ value: Outcome; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'profit', label: 'Profit' },
  { value: 'loss', label: 'Loss' },
  { value: 'flat', label: 'Flat' },
];

function SortHeader({ label, column, sort, onSort, align }: { label: string; column: SortKey; sort: Sort; onSort: (key: SortKey) => void; align?: 'right' }) {
  const active = sort.key === column;
  return (
    <Table.Th ta={align} aria-sort={active ? (sort.descending ? 'descending' : 'ascending') : undefined}>
      <UnstyledButton onClick={() => onSort(column)} fz="sm" fw={600} c={active ? undefined : 'dimmed'}>
        {label} {active ? (sort.descending ? '↓' : '↑') : ''}
      </UnstyledButton>
    </Table.Th>
  );
}

/** Filled vs rejected scenarios as a small bar, with the counts beside it. */
function ScenarioBar({ run }: { run: RunSummary }) {
  const total = run.filledCount + run.rejectedCount;
  return (
    <Group gap={8} wrap="nowrap" justify="flex-end">
      <Box
        w={64}
        h={6}
        style={{ display: 'flex', gap: 2, borderRadius: 3, overflow: 'hidden', background: 'var(--console-raised)' }}
        aria-hidden
      >
        {total > 0 && run.filledCount > 0 && <Box style={{ flex: run.filledCount, background: 'var(--console-info)' }} />}
        {run.rejectedCount > 0 && <Box style={{ flex: run.rejectedCount, background: 'var(--console-rejected)' }} />}
      </Box>
      <Text size="sm" ff="monospace">
        <Text span c="teal" inherit fw={600}>
          {formatCount(run.filledCount)}
        </Text>
        {' / '}
        <Text span c={run.rejectedCount > 0 ? 'orange' : 'dimmed'} inherit fw={600}>
          {formatCount(run.rejectedCount)}
        </Text>
      </Text>
    </Group>
  );
}

function RunsTable({
  runs,
  datasetNames,
  sort,
  onSort,
  selected,
  onToggle,
  stale,
}: {
  runs: RunSummary[];
  datasetNames: Map<string, string>;
  sort: Sort;
  onSort: (key: SortKey) => void;
  selected: string[];
  onToggle: (id: string) => void;
  /** True while a new page loads over the previous one. */
  stale: boolean;
}) {
  const navigate = useNavigate();
  const full = selected.length >= MAX_COMPARED_RUNS;
  return (
    <ScrollArea type="auto" style={{ opacity: stale ? 0.6 : 1, transition: 'opacity 120ms' }}>
      <Table verticalSpacing="xs" miw={1000}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={SELECT_COLUMN_WIDTH} className="console-sticky" style={STICKY_SELECT}>
              <VisuallyHidden>Compare</VisuallyHidden>
            </Table.Th>
            <Table.Th className="console-sticky" style={STICKY_RUN}>
              Run
            </Table.Th>
            <SortHeader label="Created" column="created" sort={sort} onSort={onSort} />
            <Table.Th>Dataset</Table.Th>
            <SortHeader label="Scenarios" column="scenarios" sort={sort} onSort={onSort} align="right" />
            <Table.Th ta="right">Filled / rejected</Table.Th>
            <Table.Th ta="right">Open / closed</Table.Th>
            <SortHeader label="Realized P&L" column="pnl" sort={sort} onSort={onSort} align="right" />
            <Table.Th>Quote mint</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {runs.map((run) => {
            const checked = selected.includes(run.id);
            const datasetName = datasetNames.get(run.datasetId);
            return (
              <Table.Tr
                key={run.id}
                style={{ cursor: 'pointer', background: checked ? 'var(--console-raised)' : undefined }}
                onClick={() => void navigate(paths.run(run.id))}
              >
                <Table.Td onClick={(e) => e.stopPropagation()} className="console-sticky" style={STICKY_SELECT}>
                  <Tooltip label={`Compare at most ${MAX_COMPARED_RUNS} runs`} disabled={checked || !full}>
                    <Checkbox
                      checked={checked}
                      disabled={!checked && full}
                      onChange={() => onToggle(run.id)}
                      aria-label={`Select run ${shortId(run.id)} to compare`}
                    />
                  </Tooltip>
                </Table.Td>
                <Table.Td className="console-sticky" style={{ whiteSpace: 'nowrap', ...STICKY_RUN }}>
                  <Anchor component={Link} to={paths.run(run.id)} ff="monospace" size="sm" fw={600} onClick={(e) => e.stopPropagation()}>
                    {shortId(run.id)}
                  </Anchor>
                  <Text size="xs" c="dimmed" ff="monospace">
                    cfg {shortId(run.configurationHash)}
                  </Text>
                </Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <Timestamp iso={run.createdAt} ff="monospace" c="dimmed" />
                </Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <Text size="sm" c={datasetName ? undefined : 'dimmed'}>
                    {datasetName || '—'}
                  </Text>
                  <ShortId value={run.datasetId} c="dimmed" size="xs" />
                </Table.Td>
                <Table.Td ta="right" ff="monospace">
                  {formatCount(run.scenarioCount)}
                </Table.Td>
                <Table.Td ta="right">
                  <ScenarioBar run={run} />
                </Table.Td>
                <Table.Td ta="right" ff="monospace">
                  {formatCount(run.openCount)} / {formatCount(run.closedCount)}
                </Table.Td>
                <Table.Td ta="right">
                  <Pnl value={run.realizedPnl} />
                </Table.Td>
                <Table.Td>
                  <Mint mint={run.quoteMint} />
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function CompareBar({ selected, onClear }: { selected: string[]; onClear: () => void }) {
  if (selected.length === 0) return null;
  return (
    <Affix position={{ bottom: 20, left: '50%' }} style={{ transform: 'translateX(-50%)', maxWidth: 'calc(100vw - 32px)' }} zIndex={150}>
      <Paper
        role="region"
        aria-label="Selected runs"
        px="md"
        py="sm"
        withBorder
        style={{
          borderColor: 'var(--console-line-strong)',
          background: 'var(--console-raised)',
          boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
        }}
      >
        <Group gap="md" wrap="wrap">
          <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
            <Text span ff="monospace" fw={600}>
              {selected.length}
            </Text>{' '}
            selected · up to {MAX_COMPARED_RUNS}
          </Text>
          <Button variant="default" size="xs" onClick={onClear}>
            Clear
          </Button>
          <Button
            component={Link}
            to={paths.compareRuns(selected)}
            size="xs"
            leftSection={<IconCompare size={14} />}
            disabled={selected.length < 2}
            rightSection={<Kbd>C</Kbd>}
          >
            Compare {selected.length}
          </Button>
        </Group>
      </Paper>
    </Affix>
  );
}

function Shortcut({ keys, children }: { keys: string; children: ReactNode }) {
  return (
    <Text span size="xs" c="dimmed">
      <Kbd>{keys}</Kbd> {children}
    </Text>
  );
}

interface ListUpdate {
  datasetId?: string | null;
  page?: number;
  query?: string;
  outcome?: Outcome;
  sort?: Sort;
}

export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const datasetId = params.get('datasetId');
  const requestedPage = Number(params.get('page') ?? '0');
  const page = Number.isSafeInteger(requestedPage) && requestedPage >= 0 ? requestedPage : 0;
  const runs = useRuns(page, PAGE_SIZE, datasetId);
  const datasets = useDatasets(0, 100);
  const datasetList: Dataset[] = datasets.data?.items ?? [];
  const datasetNames = new Map(datasetList.map((d) => [d.id, d.name]));
  const data = runs.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  // Filter text, outcome and sort live in the URL so a shared link shows the same list.
  const query = params.get(QUERY_PARAM) ?? '';
  const outcome = parseOutcome(params.get(OUTCOME_PARAM));
  const sort = parseSort(params.get(SORT_PARAM));
  const [selected, setSelected] = useState<string[]>([]);
  const filterInput = useRef<HTMLInputElement>(null);

  const toggle = (id: string) =>
    setSelected((current) => (current.includes(id) ? current.filter((x) => x !== id) : current.length >= MAX_COMPARED_RUNS ? current : [...current, id]));

  useHotkeys([
    ['f', () => filterInput.current?.focus()],
    ['c', () => selected.length >= 2 && void navigate(paths.compareRuns(selected))],
  ]);

  const update = (next: ListUpdate) => {
    const nextParams = new URLSearchParams(params);
    const setOrDelete = (key: string, value: string | null) => {
      if (value) nextParams.set(key, value);
      else nextParams.delete(key);
    };
    if (next.datasetId !== undefined) {
      setOrDelete('datasetId', next.datasetId);
      nextParams.delete('page');
    }
    if (next.page !== undefined) setOrDelete('page', next.page > 0 ? String(next.page) : null);
    if (next.query !== undefined) setOrDelete(QUERY_PARAM, next.query);
    if (next.outcome !== undefined) setOrDelete(OUTCOME_PARAM, next.outcome === 'all' ? null : next.outcome);
    if (next.sort !== undefined) setOrDelete(SORT_PARAM, serializeSort(next.sort));
    // Typing in the filter replaces the history entry instead of adding one per key stroke.
    setParams(nextParams, { replace: next.query !== undefined });
  };

  const shown = data ? sortRuns(filterRuns(data.items, query, outcome, datasetNames), sort) : [];
  const narrowed = data ? shown.length !== data.items.length : false;
  const newRunPath = datasetId ? paths.newRunFor(datasetId) : paths.newRun;

  let body: ReactNode;
  if (!data) {
    body = runs.isError ? (
      <ErrorState error={runs.error} title="Could not load runs" onRetry={() => void runs.refetch()} />
    ) : (
      <LoadingState label="Loading runs…" />
    );
  } else if (data.items.length === 0) {
    body = (
      <EmptyState
        title={datasetId ? 'No runs for this dataset' : 'No runs yet'}
        description="Start a run from a stored dataset to see it here."
        action={
          <Button component={Link} to={newRunPath} variant="default">
            Start a run
          </Button>
        }
      />
    );
  } else if (shown.length === 0) {
    body = <EmptyState title="No runs match" description="Clear the filter or pick another outcome." />;
  } else {
    body = (
      <RunsTable
        runs={shown}
        datasetNames={datasetNames}
        sort={sort}
        onSort={(key) => update({ sort: toggleSort(sort, key) })}
        selected={selected}
        onToggle={toggle}
        stale={runs.isPlaceholderData}
      />
    );
  }

  const datasetName = datasetId ? datasetNames.get(datasetId) : undefined;
  const countHint = data
    ? narrowed
      ? `${formatCount(shown.length)} of ${formatCount(data.items.length)} on this page`
      : `${formatCount(data.total)} runs`
    : undefined;

  return (
    <Stack gap="md">
      <PageHeader
        title="Runs"
        description="Every persisted replay run. Runs are immutable: the same dataset, configuration and engine always give the same run. Tick runs to compare them."
        actions={
          <Button component={Link} to={newRunPath} leftSection={<IconPlay size={16} />}>
            New run
          </Button>
        }
      />

      <Group gap="sm" wrap="wrap">
        <TextInput
          ref={filterInput}
          aria-label="Filter runs on this page"
          placeholder="Filter by run, dataset or config hash"
          leftSection={<IconSearch size={15} />}
          rightSection={<Kbd>F</Kbd>}
          value={query}
          onChange={(e) => update({ query: e.currentTarget.value })}
          onKeyDown={(e) => e.key === 'Escape' && e.currentTarget.blur()}
          w={{ base: '100%', sm: 320 }}
        />
        <SegmentedControl aria-label="Outcome" value={outcome} onChange={(value) => update({ outcome: parseOutcome(value) })} data={OUTCOMES} />
        <Select
          aria-label="Filter by dataset"
          placeholder="All datasets"
          data={datasetList.map((d) => ({ value: d.id, label: `${d.name || '—'} · ${shortId(d.id)}` }))}
          value={datasetId}
          onChange={(value) => update({ datasetId: value })}
          clearable
          searchable
          w={{ base: '100%', sm: 280 }}
        />
      </Group>

      <Section
        title={datasetId ? `Runs on ${datasetName || shortId(datasetId)}` : 'All runs'}
        hint={countHint}
        padding="md"
      >
        {data && runs.isError && (
          <RefreshNotice error={runs.error} updatedAt={runs.dataUpdatedAt} onRetry={() => void runs.refetch()} />
        )}
        {body}
      </Section>

      {data && (
        <Group justify="space-between">
          <Group gap="md" visibleFrom="sm">
            <Shortcut keys="F">filter</Shortcut>
            <Shortcut keys="C">compare selected</Shortcut>
            <Shortcut keys="N">new run</Shortcut>
          </Group>
          {pages > 1 && <Pagination value={page + 1} total={pages} onChange={(p) => update({ page: p - 1 })} size="sm" />}
        </Group>
      )}

      <CompareBar selected={selected} onClear={() => setSelected([])} />
    </Stack>
  );
}

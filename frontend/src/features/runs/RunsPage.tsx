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
import { useDebouncedCallback, useDebouncedValue, useHotkeys } from '@mantine/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Link,
  NavigationType,
  useLocation,
  useNavigate,
  useNavigationType,
  useSearchParams,
} from 'react-router';
import { useAutomationStatus } from '../../api/automation';
import {
  MAX_SEARCH_CHARS,
  MIN_SEARCH_CHARS,
  queryKeys,
  useDataset,
  useDatasetSearch,
  useLiveRunTotal,
  useRuns,
} from '../../api/queries';
import { RUN_STATUSES, type RunStatus, type RunSummary } from '../../api/types';
import { Mint, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { IconCompare, IconPlay, IconSearch } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { RefreshNotice } from '../../components/QueryView';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, shortId } from '../../lib/format';
import { MAX_COMPARED_RUNS, paths } from '../../paths';
import {
  dayInfo,
  daySpanText,
  tradingDayOf,
  zoneDateTime,
  zoneLabel,
} from '../automation/automationModel';
import {
  activeFilterLabels,
  createdRangeProblem,
  dayProblem,
  FILTER_PARAMS,
  hasFilters,
  LEGACY_OUTCOME_PARAM,
  mintProblem,
  parseSort,
  parseStatus,
  queryProblem,
  readFilters,
  RUN_PARAMS,
  serializeSort,
  sortRuns,
  STATUS_LABELS,
  toggleSort,
  toRunQuery,
  type DayClock,
  type Sort,
  type SortKey,
  type StatusFilter,
} from './runList';

const PAGE_SIZE = 20;
/** Typing in a search box waits this long before asking the server. */
const SEARCH_DEBOUNCE_MS = 300;
/** Datasets the dataset combobox lists per search. */
const DATASET_OPTIONS = 20;
/** The two created dates share a row on a phone. */
const HALF_ON_PHONE = { base: 'calc(50% - var(--mantine-spacing-sm) / 2)', xs: 170 } as const;

/** The compare checkbox and the run id stay in view while the table scrolls sideways. */
const SELECT_COLUMN_WIDTH = 40;
const STICKY_SELECT = { left: 0 } as const;
const STICKY_RUN = { left: SELECT_COLUMN_WIDTH } as const;

function SortHeader({
  label,
  column,
  sort,
  onSort,
  align,
}: {
  label: string;
  column: SortKey;
  sort: Sort;
  onSort: (key: SortKey) => void;
  align?: 'right';
}) {
  const active = sort.key === column;
  return (
    <Table.Th
      ta={align}
      aria-sort={active ? (sort.descending ? 'descending' : 'ascending') : undefined}
    >
      <UnstyledButton
        onClick={() => onSort(column)}
        fz="sm"
        fw={600}
        c={active ? undefined : 'dimmed'}
      >
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
        style={{
          display: 'flex',
          gap: 2,
          borderRadius: 3,
          overflow: 'hidden',
          background: 'var(--console-raised)',
        }}
        aria-hidden
      >
        {total > 0 && run.filledCount > 0 && (
          <Box style={{ flex: run.filledCount, background: 'var(--console-info)' }} />
        )}
        {run.rejectedCount > 0 && (
          <Box style={{ flex: run.rejectedCount, background: 'var(--console-rejected)' }} />
        )}
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

/** The tokens a run's dataset holds: the first, and how many more. */
function RunTokens({ mints }: { mints: string[] }) {
  if (mints.length === 0)
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  return (
    <Group gap={4} wrap="nowrap">
      <Mint mint={mints[0]!} />
      {mints.length > 1 && (
        <Tooltip label={mints.slice(1).join(', ')} withArrow multiline maw={360}>
          <Text span size="xs" c="dimmed">
            +{mints.length - 1}
          </Text>
        </Tooltip>
      )}
    </Group>
  );
}

/**
 * The buy a run's dataset was captured around, on the automation clock, and for a sweep run a link to the day page of
 * the trading day holding it (the exact buy needs the token page, which comes later).
 */
function RunBuy({ run, clock }: { run: RunSummary; clock: DayClock | null }) {
  if (!run.anchorBlockTime)
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  if (!clock) return <Timestamp iso={run.anchorBlockTime} ff="monospace" c="dimmed" />;
  const day = tradingDayOf(run.anchorBlockTime, clock.zone, clock.dayStart);
  return (
    <Stack gap={0}>
      <Tooltip label={`${run.anchorBlockTime} (UTC)`} withArrow>
        <Text size="sm" ff="monospace" c="dimmed">
          {zoneDateTime(run.anchorBlockTime, clock.zone)} {zoneLabel(clock.zone)}
        </Text>
      </Tooltip>
      {run.storage === 'DATASET_REFERENCE' && (
        <Anchor
          component={Link}
          to={paths.automationDay(day)}
          size="xs"
          onClick={(e) => e.stopPropagation()}
          aria-label={`From buy: trading day ${dayInfo(day).label}`}
        >
          From buy · {dayInfo(day).short}
        </Anchor>
      )}
    </Stack>
  );
}

function RunsTable({
  runs,
  clock,
  sort,
  onSort,
  selected,
  onToggle,
  stale,
}: {
  runs: RunSummary[];
  clock: DayClock | null;
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
      <Table verticalSpacing="xs" miw={1240}>
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
            <Table.Th>Token</Table.Th>
            <Table.Th>Buy</Table.Th>
            <SortHeader
              label="Scenarios"
              column="scenarios"
              sort={sort}
              onSort={onSort}
              align="right"
            />
            <Table.Th ta="right">Filled / rejected</Table.Th>
            <Table.Th ta="right">Open / closed</Table.Th>
            <SortHeader
              label="Realized P&L"
              column="pnl"
              sort={sort}
              onSort={onSort}
              align="right"
            />
            <Table.Th>Quote mint</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {runs.map((run) => {
            const checked = selected.includes(run.id);
            return (
              <Table.Tr
                key={run.id}
                style={{
                  cursor: 'pointer',
                  background: checked ? 'var(--console-raised)' : undefined,
                }}
                onClick={() => void navigate(paths.run(run.id))}
              >
                <Table.Td
                  onClick={(e) => e.stopPropagation()}
                  className="console-sticky"
                  style={STICKY_SELECT}
                >
                  <Tooltip
                    label={`Compare at most ${MAX_COMPARED_RUNS} runs`}
                    disabled={checked || !full}
                  >
                    <Checkbox
                      checked={checked}
                      disabled={!checked && full}
                      onChange={() => onToggle(run.id)}
                      aria-label={`Select run ${shortId(run.id)} to compare`}
                    />
                  </Tooltip>
                </Table.Td>
                <Table.Td
                  className="console-sticky"
                  style={{ whiteSpace: 'nowrap', ...STICKY_RUN }}
                >
                  <Anchor
                    component={Link}
                    to={paths.run(run.id)}
                    ff="monospace"
                    size="sm"
                    fw={600}
                    onClick={(e) => e.stopPropagation()}
                  >
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
                  <Text size="sm" c={run.datasetName ? undefined : 'dimmed'}>
                    {run.datasetName || '—'}
                  </Text>
                  <ShortId value={run.datasetId} c="dimmed" size="xs" />
                </Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <RunTokens mints={run.mints} />
                </Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <RunBuy run={run} clock={clock} />
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
    <Affix
      position={{ bottom: 20, left: '50%' }}
      style={{ transform: 'translateX(-50%)', maxWidth: 'calc(100vw - 32px)' }}
      zIndex={150}
    >
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

/**
 * A field's typed value, kept apart from the URL: the URL only takes it once it is usable (a valid token) or after a
 * pause (text). Only going back or forward replaces what was typed, so a late URL write never eats key strokes.
 */
function useUrlDraft(value: string) {
  const location = useLocation();
  const navigationType = useNavigationType();
  const [draft, setDraft] = useState(value);
  const [seenKey, setSeenKey] = useState(location.key);
  if (navigationType === NavigationType.Pop && seenKey !== location.key) {
    setSeenKey(location.key);
    setDraft(value);
  }
  return [draft, setDraft] as const;
}

/** The dataset filter: a combobox searched on the server, so every dataset can be picked, not only the newest 100. */
function DatasetFilter({
  datasetId,
  datasetName,
  onChange,
}: {
  datasetId: string | null;
  datasetName: string | null | undefined;
  onChange: (id: string | null) => void;
}) {
  const [search, setSearch] = useState('');
  const [debounced] = useDebouncedValue(search, SEARCH_DEBOUNCE_MS);
  const selectedLabel = datasetId ? datasetOptionLabel(datasetName, datasetId) : '';
  // While the box shows the chosen dataset's label, list the newest datasets rather than search for that label.
  const needle = debounced === selectedLabel ? '' : debounced;
  const datasets = useDatasetSearch(needle, DATASET_OPTIONS);
  const options = (datasets.data?.items ?? []).map((d) => ({
    value: d.id,
    label: datasetOptionLabel(d.name, d.id),
  }));
  if (datasetId && !options.some((o) => o.value === datasetId))
    options.unshift({ value: datasetId, label: selectedLabel });
  const tooShort = needle.trim().length > 0 && needle.trim().length < MIN_SEARCH_CHARS;
  return (
    <Select
      label="Dataset"
      aria-label="Filter by dataset"
      placeholder="All datasets"
      data={options}
      value={datasetId}
      onChange={(value) => onChange(value)}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      // The server already matched the text; Mantine must not filter its answer again.
      filter={({ options: all }) => all}
      nothingFoundMessage={
        tooShort
          ? `Type at least ${MIN_SEARCH_CHARS} characters`
          : datasets.isFetching
            ? 'Searching…'
            : 'No dataset matches'
      }
      clearable
      w={{ base: '100%', sm: 260 }}
    />
  );
}

function datasetOptionLabel(name: string | null | undefined, id: string): string {
  return `${name || '—'} · ${shortId(id)}`;
}

/** "Status" as a segmented control with each status's count from the server. */
function StatusFilterControl({
  value,
  counts,
  onChange,
}: {
  value: StatusFilter;
  counts: Record<RunStatus, number> | undefined;
  onChange: (value: StatusFilter) => void;
}) {
  const all = counts ? RUN_STATUSES.reduce((sum, s) => sum + counts[s], 0) : undefined;
  const label = (text: string, count: number | undefined) => (
    <Group gap={6} wrap="nowrap" justify="center">
      <span>{text}</span>
      {count !== undefined && ' '}
      {count !== undefined && (
        <Text span size="xs" ff="monospace" c="dimmed">
          {formatCount(count)}
        </Text>
      )}
    </Group>
  );
  return (
    <ScrollArea type="never" maw="100%">
      <SegmentedControl
        aria-label="Status"
        value={value}
        onChange={(next) => onChange(parseStatus(next))}
        data={[
          { value: 'all', label: label('All', all) },
          ...RUN_STATUSES.map((s) => ({ value: s, label: label(STATUS_LABELS[s], counts?.[s]) })),
        ]}
      />
    </ScrollArea>
  );
}

/** The backend answered without `statusCounts`: it predates server-side run search and ignored the new filters. */
function FiltersUnsupportedNotice({ byDataset }: { byDataset: boolean }) {
  return (
    <Group
      gap="sm"
      mb="sm"
      px="sm"
      py={6}
      role="status"
      style={{ border: '1px solid var(--console-line-strong)', borderRadius: 6 }}
    >
      <Text size="sm">
        This backend does not support server-side filters yet; filters apply after it is updated.
        The list below is every run{byDataset ? ' of the chosen dataset' : ''}, newest first.
      </Text>
    </Group>
  );
}

/** Shown when runs were created after the page's snapshot; "Show" moves the snapshot to now. */
function NewRunsNotice({ count, onShow }: { count: number; onShow: () => void }) {
  return (
    <Group
      gap="sm"
      justify="space-between"
      mb="sm"
      px="sm"
      py={6}
      role="status"
      style={{ border: '1px solid var(--console-line-strong)', borderRadius: 6 }}
    >
      <Text size="sm">
        <Text span ff="monospace" fw={600}>
          {formatCount(count)}
        </Text>{' '}
        new {count === 1 ? 'run' : 'runs'} since this list was loaded. Pages stay as they were until
        you show them.
      </Text>
      <Button size="xs" variant="light" onClick={onShow}>
        Show
      </Button>
    </Group>
  );
}

interface ListUpdate {
  set?: Partial<Record<(typeof FILTER_PARAMS)[number], string | null>>;
  page?: number;
  sort?: Sort;
  /** Typing replaces the history entry instead of adding one per key stroke. */
  replace?: boolean;
}

/**
 * The URL's query and a writer that edits it. Each write starts from the last write, not from the last render: a
 * debounced search, the snapshot pin and a click can land before React renders the previous write, and building on a
 * render's copy would silently drop the other's parameter.
 */
function useListParams() {
  const [params, setParams] = useSearchParams();
  const latest = useRef(params);
  /** Our newest write until the router renders it; older renders in between must not roll `latest` back. */
  const outstanding = useRef<string | null>(null);
  // Every render, not only when `params` changes: a write that lands back on the same query still clears.
  useEffect(() => {
    if (outstanding.current === null) latest.current = params;
    else if (outstanding.current === params.toString()) outstanding.current = null;
  });
  const write = useCallback(
    (change: (next: URLSearchParams) => void, replace = false) => {
      const next = new URLSearchParams(latest.current);
      change(next);
      if (next.toString() === latest.current.toString()) return;
      latest.current = next;
      outstanding.current = next.toString();
      setParams(next, { replace });
    },
    [setParams],
  );
  return [params, write] as const;
}

export function RunsPage() {
  const [params, writeParams] = useListParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const filters = readFilters(params);
  const requestedPage = Number(params.get(RUN_PARAMS.page) ?? '0');
  const page = Number.isSafeInteger(requestedPage) && requestedPage >= 0 ? requestedPage : 0;
  const sort = parseSort(params.get(RUN_PARAMS.sort));
  const rawAsOf = params.get(RUN_PARAMS.asOf);
  const asOf = rawAsOf && !Number.isNaN(Date.parse(rawAsOf)) ? rawAsOf : null;

  const status = useAutomationStatus();
  const clock: DayClock | null = status.data
    ? { zone: status.data.zone, dayStart: status.data.tradingDayStart }
    : null;
  // A trading day is cut on the automation's clock; wait for it rather than send a day-less query.
  const waitingForClock = Boolean(filters.day) && !clock && status.isPending;
  const query = toRunQuery(filters, clock, page, PAGE_SIZE, asOf);
  const runs = useRuns(query, { enabled: !waitingForClock });
  const data = runs.data;
  const current = data && !runs.isPlaceholderData ? data : undefined;
  const liveTotal = useLiveRunTotal(query, Boolean(asOf && data));
  const newRuns = current && liveTotal.data !== undefined ? liveTotal.data - current.total : 0;
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;
  const dataset = useDataset(filters.datasetId ?? '', filters.datasetId !== null);
  const datasetName = dataset.data?.name;

  // Pin the first page's snapshot in the URL: later pages and a reload are cut from the same list.
  useEffect(() => {
    if (asOf || !current) return;
    queryClient.setQueryData(queryKeys.runs({ ...query, asOf: current.asOf }), current);
    writeParams((next) => next.set(RUN_PARAMS.asOf, current.asOf), true);
  }, [asOf, current, query, queryClient, writeParams]);

  const [selected, setSelected] = useState<string[]>([]);
  const searchInput = useRef<HTMLInputElement>(null);
  const [text, setText] = useUrlDraft(filters.q);
  const [mint, setMint] = useUrlDraft(filters.mint);

  const update = ({ set, page: nextPage, sort: nextSort, replace }: ListUpdate) =>
    writeParams((next) => {
      const setOrDelete = (key: string, value: string | null | undefined) => {
        if (value) next.set(key, value);
        else next.delete(key);
      };
      if (set) {
        for (const [key, value] of Object.entries(set)) setOrDelete(key, value);
        next.delete(LEGACY_OUTCOME_PARAM);
        // A new filter is a new list: back to its first page, on a fresh snapshot.
        next.delete(RUN_PARAMS.page);
        next.delete(RUN_PARAMS.asOf);
      }
      if (nextPage !== undefined)
        setOrDelete(RUN_PARAMS.page, nextPage > 0 ? String(nextPage) : null);
      if (nextSort !== undefined) setOrDelete(RUN_PARAMS.sort, serializeSort(nextSort));
    }, replace);

  const writeText = useDebouncedCallback(
    (value: string) => update({ set: { q: value.trim() ? value : null }, replace: true }),
    SEARCH_DEBOUNCE_MS,
  );

  const clearFilters = () => {
    writeText.cancel();
    setText('');
    setMint('');
    update({ set: Object.fromEntries(FILTER_PARAMS.map((key) => [key, null])) });
  };

  const showNewRuns = () =>
    writeParams((next) => {
      next.delete(RUN_PARAMS.asOf);
      next.delete(RUN_PARAMS.page);
    });

  const toggle = (id: string) =>
    setSelected((list) =>
      list.includes(id)
        ? list.filter((x) => x !== id)
        : list.length >= MAX_COMPARED_RUNS
          ? list
          : [...list, id],
    );

  useHotkeys([
    ['f', () => searchInput.current?.focus()],
    ['c', () => selected.length >= 2 && void navigate(paths.compareRuns(selected))],
  ]);

  // A backend from before server-side run search answers without statusCounts and applies only the dataset filter.
  const serverFilters = !data || data.statusCounts !== null;
  const filtered = serverFilters ? hasFilters(filters) : filters.datasetId !== null;
  const shown = data ? sortRuns(data.items, sort) : [];
  const newRunPath = filters.datasetId ? paths.newRunFor(filters.datasetId) : paths.newRun;
  const created = createdRangeProblem(filters.createdFrom, filters.createdTo);
  const dayUnavailable = Boolean(filters.day) && !clock && !status.isPending;

  let body: ReactNode;
  if (!data) {
    body = runs.isError ? (
      <ErrorState
        error={runs.error}
        title="Could not load runs"
        onRetry={() => void runs.refetch()}
      />
    ) : (
      <LoadingState label="Loading runs…" />
    );
  } else if (data.items.length === 0 && !filtered) {
    body = (
      <EmptyState
        title="No runs yet"
        description="Start a run from a stored dataset to see it here."
        action={
          <Button component={Link} to={newRunPath} variant="default">
            Start a run
          </Button>
        }
      />
    );
  } else if (data.items.length === 0) {
    const labels = activeFilterLabels(filters, datasetName);
    body = (
      <EmptyState
        title="No runs match"
        description={
          labels.length > 0
            ? `No run is ${labels.join(' · ')}.`
            : 'No run matches the filters above.'
        }
        action={
          <Button variant="default" onClick={clearFilters}>
            Clear filters
          </Button>
        }
      />
    );
  } else {
    body = (
      <RunsTable
        runs={shown}
        clock={clock}
        sort={sort}
        onSort={(key) => update({ sort: toggleSort(sort, key) })}
        selected={selected}
        onToggle={toggle}
        stale={runs.isPlaceholderData}
      />
    );
  }

  const countHint = data ? (
    <>
      {formatCount(data.total)} {data.total === 1 ? 'run' : 'runs'}
      {filtered ? ' match' : ''} · as of <Timestamp iso={data.asOf} size="xs" c="dimmed" /> · newest
      first; a column sort reorders this page only
    </>
  ) : undefined;

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

      <Stack gap="sm" role="search" aria-label="Run filters">
        <Group gap="sm" wrap="wrap" align="flex-start">
          <TextInput
            ref={searchInput}
            aria-label="Search all runs"
            placeholder="Run id, config hash, dataset id or name"
            leftSection={<IconSearch size={15} />}
            rightSection={<Kbd>F</Kbd>}
            value={text}
            maxLength={MAX_SEARCH_CHARS}
            error={queryProblem(text)}
            onChange={(e) => {
              setText(e.currentTarget.value);
              writeText(e.currentTarget.value);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') writeText.flush();
              if (e.key === 'Escape') e.currentTarget.blur();
            }}
            w={{ base: '100%', sm: 340 }}
          />
          <StatusFilterControl
            value={filters.status}
            counts={data?.statusCounts ?? undefined}
            onChange={(next) => update({ set: { status: next === 'all' ? null : next } })}
          />
        </Group>
        <Group gap="sm" wrap="wrap" align="flex-start">
          <TextInput
            label="Token"
            aria-label="Filter by token mint"
            placeholder="Token mint address"
            value={mint}
            error={mintProblem(mint)}
            onChange={(e) => {
              const value = e.currentTarget.value.trim();
              setMint(value);
              if (!mintProblem(value)) update({ set: { mint: value || null }, replace: true });
            }}
            w={{ base: '100%', sm: 300 }}
            styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
          />
          <DatasetFilter
            datasetId={filters.datasetId}
            datasetName={datasetName}
            onChange={(value) => update({ set: { datasetId: value } })}
          />
          <TextInput
            type="date"
            label="Buy's trading day"
            aria-label="Trading day of the buy"
            value={filters.day}
            error={
              dayProblem(filters.day) ??
              (dayUnavailable ? 'Trading days need the automation clock, which is off.' : null)
            }
            description={clock ? daySpanText(clock.zone, clock.dayStart) : undefined}
            onChange={(e) => update({ set: { day: e.currentTarget.value || null } })}
            w={{ base: '100%', xs: 200 }}
          />
          <TextInput
            type="date"
            label="Created from"
            value={filters.createdFrom}
            error={created.from}
            description="Your local dates"
            onChange={(e) => update({ set: { from: e.currentTarget.value || null } })}
            w={HALF_ON_PHONE}
          />
          <TextInput
            type="date"
            label="Created to"
            value={filters.createdTo}
            error={created.to}
            description="Included"
            onChange={(e) => update({ set: { to: e.currentTarget.value || null } })}
            w={HALF_ON_PHONE}
          />
          {filtered && (
            <Button variant="subtle" size="sm" mt={{ base: 0, xs: 25 }} onClick={clearFilters}>
              Clear filters
            </Button>
          )}
        </Group>
      </Stack>

      <Section
        title={
          filters.datasetId
            ? `Runs on ${datasetName || shortId(filters.datasetId)}`
            : filtered
              ? 'Matching runs'
              : 'All runs'
        }
        hint={countHint}
        padding="md"
      >
        {!serverFilters && <FiltersUnsupportedNotice byDataset={filters.datasetId !== null} />}
        {newRuns > 0 && <NewRunsNotice count={newRuns} onShow={showNewRuns} />}
        {data && runs.isError && (
          <RefreshNotice
            error={runs.error}
            updatedAt={runs.dataUpdatedAt}
            onRetry={() => void runs.refetch()}
          />
        )}
        {body}
      </Section>

      {data && (
        <Group justify="space-between">
          <Group gap="md" visibleFrom="sm">
            <Shortcut keys="F">search</Shortcut>
            <Shortcut keys="C">compare selected</Shortcut>
            <Shortcut keys="N">new run</Shortcut>
          </Group>
          {pages > 1 && (
            <Pagination
              value={page + 1}
              total={pages}
              onChange={(p) => update({ page: p - 1 })}
              size="sm"
            />
          )}
        </Group>
      )}

      <CompareBar selected={selected} onClear={() => setSelected([])} />
    </Stack>
  );
}

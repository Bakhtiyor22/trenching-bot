import {
  ActionIcon,
  Anchor,
  Box,
  Button,
  Card,
  Group,
  MultiSelect,
  ScrollArea,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { errorMessage } from '../../api/client';
import { useRuns, useRunsById } from '../../api/queries';
import type { RunDetail, Scenario } from '../../api/types';
import { useChartTokens } from '../../charts/chartTokens';
import { Pnl, Timestamp } from '../../components/DisplayValues';
import { IconX } from '../../components/Icons';
import { Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { StatusChip } from '../../components/StatusChip';
import { EmptyState, LoadingState } from '../../components/StateViews';
import { codeLabel } from '../../lib/codes';
import { formatSignedDecimal, toChartNumber } from '../../lib/decimal';
import { formatCount, formatLocalTime, mintLabel, shortId } from '../../lib/format';
import { COMPARE_PARAM, MAX_COMPARED_RUNS, paths } from '../../paths';
import { scenarioOutcome } from '../runs/scenarioOutcome';
import {
  alignByMint,
  differenceSummary,
  inputRows,
  parseCompareIds,
  pnlDeltas,
  sharedRows,
} from './compareRuns';

/** Each compared run keeps one letter everywhere on the page; its colour is the scheme's series slot. */
const LETTERS = ['A', 'B', 'C'] as const;

/** Runs offered in the picker. */
const PICKER_RUNS = 50;

function letter(slot: number): string {
  return LETTERS[slot % LETTERS.length]!;
}

/** The identity colour of a run slot (blue, orange, aqua), from the scheme in use. */
function useSlotColor() {
  const { series } = useChartTokens();
  return (slot: number) => series[slot % series.length]!;
}

function Letter({ slot }: { slot: number }) {
  const color = useSlotColor()(slot);
  return (
    <Box
      component="span"
      ff="monospace"
      fz={12}
      fw={700}
      w={22}
      h={22}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: 4,
        background: color,
        color: 'var(--console-panel)',
        flex: 'none',
      }}
    >
      {letter(slot)}
    </Box>
  );
}

function CardHeader({
  slot,
  id,
  link,
  onRemove,
}: {
  slot: number;
  id: string;
  link: boolean;
  onRemove: () => void;
}) {
  return (
    <Group gap={8} wrap="nowrap">
      <Letter slot={slot} />
      {link ? (
        <Anchor component={Link} to={paths.run(id)} ff="monospace" fw={600} style={{ flex: 1 }}>
          {shortId(id)}
        </Anchor>
      ) : (
        <Text ff="monospace" fw={600} style={{ flex: 1 }}>
          {shortId(id)}
        </Text>
      )}
      <Tooltip label="Remove from the comparison">
        <ActionIcon
          variant="subtle"
          color="gray"
          onClick={onRemove}
          aria-label={`Remove run ${shortId(id)} from the comparison`}
        >
          <IconX size={16} />
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}

/**
 * One compared run in its slot. A run that could not be loaded keeps its slot and letter, with the reason and a
 * retry, and the others still compare. One card per slot whatever its state, so its controls stay put while it loads.
 */
function RunCard({
  id,
  slot,
  detail,
  error,
  onRetry,
  onRemove,
}: {
  id: string;
  slot: number;
  detail: RunDetail | undefined;
  /** Set when the run never loaded. */
  error: unknown;
  onRetry: () => void;
  onRemove: () => void;
}) {
  const color = useSlotColor()(slot);
  const failed = !detail && error != null;
  return (
    <Card padding="md" role={failed ? 'alert' : undefined} style={{ borderTop: `3px solid ${color}` }}>
      <CardHeader slot={slot} id={id} link={!!detail} onRemove={onRemove} />
      {detail ? (
        <>
          <Box mt="sm">
            <Pnl value={detail.run.realizedPnl} trim unit={mintLabel(detail.run.quoteMint)} size="lg" fw={500} />
          </Box>
          <Text size="xs" c="dimmed" mt={4}>
            <Timestamp iso={detail.run.createdAt} size="xs" c="dimmed" /> ·{' '}
            {formatCount(detail.run.closedCount)} closed · {formatCount(detail.run.openCount)} open ·{' '}
            {formatCount(detail.run.rejectedCount)} no entry
          </Text>
        </>
      ) : failed ? (
        <>
          <Group gap="xs" mt="sm">
            <StatusChip tone="failure" glyph="✕">
              Could not load this run
            </StatusChip>
          </Group>
          <Text size="sm" mt="xs" style={{ overflowWrap: 'anywhere' }}>
            {errorMessage(error)}
          </Text>
          <Button size="xs" variant="default" mt="sm" onClick={onRetry}>
            Retry
          </Button>
        </>
      ) : (
        <LoadingState label="Loading run…" />
      )}
    </Card>
  );
}

function Verdict({ details, slots }: { details: RunDetail[]; slots: number[] }) {
  const deltas = pnlDeltas(details);
  const unit = mintLabel(details[0]!.run.quoteMint);
  return (
    <KpiStrip label="P&L difference">
      {deltas.map((delta, i) => (
        <Kpi
          key={i}
          label={`Δ P&L ${letter(slots[0]!)} − ${letter(slots[i + 1]!)}`}
          hint={delta == null ? undefined : `Run ${letter(slots[0]!)} minus run ${letter(slots[i + 1]!)}`}
        >
          {delta == null ? (
            <Text span size="sm" c="dimmed">
              Not comparable: different quote mints
            </Text>
          ) : (
            <Pnl value={delta} trim unit={unit} fz="inherit" fw={500} />
          )}
        </Kpi>
      ))}
      <Kpi label="What differs" size="sm">
        <Text span inherit style={{ fontFamily: 'var(--mantine-font-family)' }}>
          {differenceSummary(details)}
        </Text>
      </Kpi>
    </KpiStrip>
  );
}

function Inputs({ details, slots }: { details: RunDetail[]; slots: number[] }) {
  const [onlyDifferences, setOnlyDifferences] = useState(false);
  const rows = inputRows(details).filter((row) => !onlyDifferences || row.differs);
  return (
    <Section
      title="Inputs"
      hint="Everything that decides a run's result. Rows that differ are raised and marked."
      actions={
        <Switch
          label="Only differences"
          checked={onlyDifferences}
          onChange={(e) => setOnlyDifferences(e.currentTarget.checked)}
        />
      }
    >
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          No differences in the inputs.
        </Text>
      ) : (
        <ScrollArea type="auto">
          <Table highlightOnHover={false} verticalSpacing="xs" miw={480} aria-label="Inputs side by side">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Input</Table.Th>
                {details.map((d, i) => (
                  <Table.Th key={d.run.id}>
                    <Letter slot={slots[i]!} />
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr
                  key={row.label}
                  data-differs={row.differs || undefined}
                  style={{ background: row.differs ? 'var(--console-raised)' : undefined }}
                >
                  <Table.Th fw={400} c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                    {row.label}
                    {row.differs && (
                      <Text span size="xs" c="var(--console-text-2)" fw={500} ml={6}>
                        differs
                      </Text>
                    )}
                  </Table.Th>
                  {row.values.map((value, i) => (
                    <Table.Td
                      key={i}
                      ff="monospace"
                      fz="sm"
                      fw={row.differs && i > 0 && value !== row.values[0] ? 600 : undefined}
                    >
                      <Tooltip label={value} disabled={value.length <= 24}>
                        <span>{value.length > 24 ? shortId(value) : value}</span>
                      </Tooltip>
                    </Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Section>
  );
}

/** One metric as paired horizontal bars, one per run, scaled to the largest magnitude. */
function MetricBars({
  label,
  values,
  details,
  slots,
  exact,
}: {
  label: string;
  values: number[];
  details: RunDetail[];
  slots: number[];
  exact?: string[];
}) {
  const colorOf = useSlotColor();
  const largest = Math.max(0, ...values.map(Math.abs));
  return (
    <Stack gap={4}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      {values.map((value, i) => (
        <Box
          key={details[i]!.run.id}
          style={{
            display: 'grid',
            gridTemplateColumns: '22px minmax(0, 1fr) minmax(90px, auto)',
            gap: 10,
            alignItems: 'center',
          }}
        >
          <Letter slot={slots[i]!} />
          <Box
            h={10}
            style={{
              background: 'var(--console-raised)',
              borderRadius: 3,
              overflow: 'hidden',
            }}
          >
            <Box
              h="100%"
              w={largest > 0 ? `${(Math.abs(value) / largest) * 100}%` : 0}
              style={{ background: colorOf(slots[i]!), transition: 'width 400ms ease' }}
            />
          </Box>
          {exact ? (
            <Pnl value={exact[i]!} ta="right" />
          ) : (
            <Text size="sm" ff="monospace" ta="right">
              {formatCount(value)}
            </Text>
          )}
        </Box>
      ))}
    </Stack>
  );
}

function Results({ details, slots }: { details: RunDetail[]; slots: number[] }) {
  const pick = (f: (d: RunDetail) => number) => details.map(f);
  const sameMint = new Set(details.map((d) => d.run.quoteMint)).size === 1;
  const bars = { details, slots };
  return (
    <Section
      title="Results"
      hint={
        sameMint
          ? 'Bars share one scale per metric.'
          : 'P&L bars are in different quote mints; compare their signs only.'
      }
    >
      <Stack gap="md">
        <MetricBars
          label={`Realized P&L${sameMint ? ` (${mintLabel(details[0]!.run.quoteMint)})` : ''}`}
          values={details.map((d) => toChartNumber(d.run.realizedPnl) ?? 0)}
          exact={details.map((d) => d.run.realizedPnl)}
          {...bars}
        />
        <MetricBars label="Tokens" values={pick((d) => d.run.scenarioCount)} {...bars} />
        <MetricBars label="Entered" values={pick((d) => d.run.filledCount)} {...bars} />
        <MetricBars label="No entry" values={pick((d) => d.run.rejectedCount)} {...bars} />
        <MetricBars label="Closed" values={pick((d) => d.run.closedCount)} {...bars} />
        <MetricBars label="Still open" values={pick((d) => d.run.openCount)} {...bars} />
      </Stack>
    </Section>
  );
}

function OutcomeCell({ scenario }: { scenario: Scenario | null }) {
  if (!scenario) {
    return (
      <Text size="xs" c="dimmed">
        not in this run
      </Text>
    );
  }
  const outcome = scenarioOutcome(scenario);
  const reason = scenario.position ? scenario.exitUnavailableReason : scenario.rejectionReason;
  return (
    <Group gap={8} wrap="nowrap">
      <StatusChip tone={outcome.tone} glyph={outcome.glyph} title={outcome.label}>
        {outcome.tag}
      </StatusChip>
      {outcome.pnl ? (
        <Pnl value={outcome.pnl} />
      ) : reason ? (
        <Text size="xs" c="dimmed" title={reason}>
          {codeLabel(reason)}
        </Text>
      ) : null}
    </Group>
  );
}

function ScenarioAlignment({ details, slots }: { details: RunDetail[]; slots: number[] }) {
  const [scope, setScope] = useState<'shared' | 'all'>('shared');
  const all = alignByMint(details);
  const shared = sharedRows(all);
  const rows = scope === 'shared' ? shared : all;
  return (
    <Section
      title="Token by token"
      hint="Each token's cycle in every run, lined up by mint."
      actions={
        <SegmentedControl
          size="xs"
          aria-label="Which tokens"
          value={scope}
          onChange={(value) => setScope(value as 'shared' | 'all')}
          data={[
            { value: 'shared', label: `In every run (${shared.length})` },
            { value: 'all', label: `All tokens (${all.length})` },
          ]}
        />
      }
    >
      {rows.length === 0 ? (
        <EmptyState
          title="No shared tokens"
          description="These runs replayed different datasets, so no token appears in all of them. Show all tokens, or compare two runs over the same dataset with different settings."
        />
      ) : (
        <ScrollArea type="auto">
          <Table miw={560} verticalSpacing="xs" aria-label="Tokens side by side">
            <Table.Thead>
              <Table.Tr>
                <Table.Th className="console-sticky">Token</Table.Th>
                {details.map((d, i) => (
                  <Table.Th key={d.run.id}>
                    <Group gap={6} wrap="nowrap">
                      <Letter slot={slots[i]!} />
                      <Text span ff="monospace" size="xs" c="dimmed">
                        {shortId(d.run.id)}
                      </Text>
                    </Group>
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr key={row.mint}>
                  <Table.Td ff="monospace" fz="sm" className="console-sticky">
                    <Tooltip label={row.mint}>
                      <span>{shortId(row.mint)}</span>
                    </Tooltip>
                  </Table.Td>
                  {row.cells.map((cell, i) => (
                    <Table.Td key={i}>
                      <OutcomeCell scenario={cell} />
                    </Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Section>
  );
}

function RunPicker({ ids, onChange }: { ids: string[]; onChange: (ids: string[]) => void }) {
  const runs = useRuns(0, PICKER_RUNS, null);
  const options = (runs.data?.items ?? []).map((r) => ({
    value: r.id,
    label: `${shortId(r.id)} · ${formatSignedDecimal(r.realizedPnl)} ${mintLabel(r.quoteMint)} · ${formatLocalTime(r.createdAt)}`,
  }));
  // Keep chosen ids that fell off the newest page selectable, so the field never drops them.
  for (const id of ids)
    if (!options.some((o) => o.value === id)) options.push({ value: id, label: shortId(id) });
  return (
    <MultiSelect
      aria-label="Runs to compare"
      placeholder={ids.length >= MAX_COMPARED_RUNS ? undefined : 'Add a run…'}
      data={options}
      value={ids}
      onChange={onChange}
      maxValues={MAX_COMPARED_RUNS}
      searchable
      clearable
      nothingFoundMessage="No run matches"
      w="100%"
      maw={720}
    />
  );
}

export function ComparePage() {
  const [params, setParams] = useSearchParams();
  const ids = parseCompareIds(params.get(COMPARE_PARAM));
  const queries = useRunsById(ids);

  const setIds = (next: string[]) => {
    const updated = new URLSearchParams(params);
    if (next.length) updated.set(COMPARE_PARAM, next.join(','));
    else updated.delete(COMPARE_PARAM);
    setParams(updated, { replace: true });
  };
  const remove = (id: string) => setIds(ids.filter((item) => item !== id));

  // Each run keeps its slot (letter and colour) from its place in the URL, so a failed run never shifts the others.
  const loaded = queries.flatMap((q, slot) => (q.data ? [{ detail: q.data, slot }] : []));
  const details = loaded.map((item) => item.detail);
  const slots = loaded.map((item) => item.slot);
  const pending = queries.some((q) => q.data === undefined && !q.isError);

  return (
    <Stack gap="lg">
      <PageHeader
        title="Compare runs"
        description={`Up to ${MAX_COMPARED_RUNS} runs side by side. The selection lives in the URL, so a comparison can be bookmarked or shared.`}
      />
      <RunPicker ids={ids} onChange={setIds} />
      {ids.length < 2 ? (
        <EmptyState
          title="Pick at least two runs"
          description={
            <>
              Add runs above, or tick them on the{' '}
              <Anchor component={Link} to={paths.runs}>
                Runs
              </Anchor>{' '}
              page and press Compare.
            </>
          }
        />
      ) : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: ids.length }}>
            {ids.map((id, slot) => {
              const query = queries[slot]!;
              return (
                <RunCard
                  key={id}
                  id={id}
                  slot={slot}
                  detail={query.data}
                  error={query.isError ? query.error : null}
                  onRetry={() => void query.refetch()}
                  onRemove={() => remove(id)}
                />
              );
            })}
          </SimpleGrid>
          {details.length >= 2 ? (
            <>
              <Verdict details={details} slots={slots} />
              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <Inputs details={details} slots={slots} />
                <Results details={details} slots={slots} />
              </SimpleGrid>
              <ScenarioAlignment details={details} slots={slots} />
            </>
          ) : (
            !pending && (
              <EmptyState
                title="Not enough runs to compare"
                description="At least two runs must load. Retry the failed run, or remove it and add another."
              />
            )
          )}
        </>
      )}
    </Stack>
  );
}

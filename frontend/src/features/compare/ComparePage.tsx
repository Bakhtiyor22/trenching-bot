import {
  ActionIcon,
  Anchor,
  Badge,
  Box,
  Card,
  Group,
  MultiSelect,
  Paper,
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
import { useRuns, useRunsById } from '../../api/queries';
import type { RunDetail, Scenario } from '../../api/types';
import { Pnl, Timestamp } from '../../components/DisplayValues';
import { IconX } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { decimalSign, formatDecimal, pnlColor, toChartNumber } from '../../lib/decimal';
import { formatCount, formatLocalTime, mintLabel, shortId } from '../../lib/format';
import { COMPARE_PARAM, MAX_COMPARED_RUNS, paths } from '../../paths';
import { terminal } from '../../theme';
import { scenarioOutcome } from '../runs/scenarioOutcome';
import {
  alignByMint,
  differenceSummary,
  inputRows,
  parseCompareIds,
  pnlDeltas,
  sharedRows,
} from './compareRuns';

/** Each compared run keeps one letter and color everywhere on the page. */
const SLOTS = [
  { letter: 'A', color: terminal.info },
  { letter: 'B', color: terminal.violet },
  { letter: 'C', color: terminal.accent },
] as const;

/** Runs offered in the picker. */
const PICKER_RUNS = 50;

function slot(index: number) {
  return SLOTS[index % SLOTS.length]!;
}

function Letter({ index }: { index: number }) {
  const { letter, color } = slot(index);
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
        color: terminal.ground,
        flex: 'none',
      }}
    >
      {letter}
    </Box>
  );
}

function RunCard({
  detail,
  index,
  onRemove,
}: {
  detail: RunDetail;
  index: number;
  onRemove: () => void;
}) {
  const { run } = detail;
  return (
    <Card padding="md" style={{ borderTop: `3px solid ${slot(index).color}` }}>
      <Group gap={8} wrap="nowrap">
        <Letter index={index} />
        <Anchor component={Link} to={paths.run(run.id)} ff="monospace" fw={600} style={{ flex: 1 }}>
          {shortId(run.id)}
        </Anchor>
        <Tooltip label="Remove from the comparison">
          <ActionIcon
            variant="subtle"
            color="gray"
            onClick={onRemove}
            aria-label={`Remove run ${shortId(run.id)} from the comparison`}
          >
            <IconX size={16} />
          </ActionIcon>
        </Tooltip>
      </Group>
      <Group gap={6} align="baseline" mt="sm">
        <Pnl value={run.realizedPnl} fz={26} fw={500} />
        <Text size="sm" c="dimmed">
          {mintLabel(run.quoteMint)}
        </Text>
      </Group>
      <Text size="xs" c="dimmed" mt={4}>
        <Timestamp iso={run.createdAt} size="xs" c="dimmed" /> · {formatCount(run.closedCount)}{' '}
        closed · {formatCount(run.openCount)} open · {formatCount(run.rejectedCount)} no entry
      </Text>
    </Card>
  );
}

function Verdict({ details }: { details: RunDetail[] }) {
  const deltas = pnlDeltas(details);
  return (
    <Paper withBorder p="md" component="section" aria-label="P&L difference">
      <Group gap="xl" wrap="wrap" align="center">
        {deltas.map((delta, i) => (
          <Group key={i} gap={8} wrap="nowrap" align="baseline">
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: '0.08em' }}>
              Δ P&amp;L
            </Text>
            <Text ff="monospace" fz={18}>
              {slot(0).letter} − {slot(i + 1).letter} ={' '}
            </Text>
            {delta == null ? (
              <Text size="sm" c="dimmed">
                not comparable: different quote mints
              </Text>
            ) : (
              <Text ff="monospace" fz={18} fw={600} c={pnlColor(delta)}>
                {decimalSign(delta) > 0 ? '+' : ''}
                {formatDecimal(delta)} {mintLabel(details[0]!.run.quoteMint)}
              </Text>
            )}
          </Group>
        ))}
        <Text size="sm" c="dimmed" style={{ flex: '1 1 280px' }} ta="right">
          {differenceSummary(details)}
        </Text>
      </Group>
    </Paper>
  );
}

function Inputs({ details }: { details: RunDetail[] }) {
  const [onlyDifferences, setOnlyDifferences] = useState(false);
  const rows = inputRows(details).filter((row) => !onlyDifferences || row.differs);
  return (
    <Section
      title="Inputs"
      hint="Everything that decides a run's result. Highlighted rows differ."
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
          <Table highlightOnHover={false} miw={480} aria-label="Inputs side by side">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Input</Table.Th>
                {details.map((d, i) => (
                  <Table.Th key={d.run.id}>
                    <Letter index={i} />
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr
                  key={row.label}
                  style={{ background: row.differs ? 'rgba(242,180,60,0.07)' : undefined }}
                >
                  <Table.Th fw={400} c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                    {row.label}
                  </Table.Th>
                  {row.values.map((value, i) => (
                    <Table.Td
                      key={i}
                      ff="monospace"
                      fz="sm"
                      c={row.differs && i > 0 && value !== row.values[0] ? 'amber' : undefined}
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
  exact,
}: {
  label: string;
  values: number[];
  details: RunDetail[];
  exact?: string[];
}) {
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
          <Letter index={i} />
          <Box
            h={10}
            style={{
              background: 'var(--mantine-color-default-hover)',
              borderRadius: 3,
              overflow: 'hidden',
            }}
          >
            <Box
              h="100%"
              w={largest > 0 ? `${(Math.abs(value) / largest) * 100}%` : 0}
              style={{ background: slot(i).color, transition: 'width 400ms ease' }}
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

function Results({ details }: { details: RunDetail[] }) {
  const pick = (f: (d: RunDetail) => number) => details.map(f);
  const sameMint = new Set(details.map((d) => d.run.quoteMint)).size === 1;
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
          details={details}
        />
        <MetricBars label="Tokens" values={pick((d) => d.run.scenarioCount)} details={details} />
        <MetricBars label="Entered" values={pick((d) => d.run.filledCount)} details={details} />
        <MetricBars label="No entry" values={pick((d) => d.run.rejectedCount)} details={details} />
        <MetricBars label="Closed" values={pick((d) => d.run.closedCount)} details={details} />
        <MetricBars label="Still open" values={pick((d) => d.run.openCount)} details={details} />
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
  return (
    <Group gap={8} wrap="nowrap">
      <Badge color={outcome.color} variant="light" size="sm" ff="monospace" title={outcome.label}>
        {outcome.tag}
      </Badge>
      {outcome.pnl ? (
        <Pnl value={outcome.pnl} />
      ) : (
        <Text size="xs" c="dimmed">
          {scenario.position ? scenario.exitUnavailableReason : scenario.rejectionReason}
        </Text>
      )}
    </Group>
  );
}

function ScenarioAlignment({ details }: { details: RunDetail[] }) {
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
          <Table miw={560} aria-label="Tokens side by side">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Token</Table.Th>
                {details.map((d, i) => (
                  <Table.Th key={d.run.id}>
                    <Group gap={6} wrap="nowrap">
                      <Letter index={i} />
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
                  <Table.Td ff="monospace" fz="sm">
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
    label: `${shortId(r.id)} · ${formatDecimal(r.realizedPnl)} ${mintLabel(r.quoteMint)} · ${formatLocalTime(r.createdAt)}`,
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

  const failed = queries.find((q) => q.isError);
  const pending = queries.some((q) => q.isPending);
  const details = queries.flatMap((q) => (q.data ? [q.data] : []));

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
      ) : failed ? (
        <ErrorState
          error={failed.error}
          title="Could not load a run"
          onRetry={() => void failed.refetch()}
        />
      ) : pending ? (
        <LoadingState label="Loading runs…" />
      ) : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: details.length }}>
            {details.map((detail, i) => (
              <RunCard
                key={detail.run.id}
                detail={detail}
                index={i}
                onRemove={() => setIds(ids.filter((id) => id !== detail.run.id))}
              />
            ))}
          </SimpleGrid>
          <Verdict details={details} />
          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <Inputs details={details} />
            <Results details={details} />
          </SimpleGrid>
          <ScenarioAlignment details={details} />
        </>
      )}
    </Stack>
  );
}

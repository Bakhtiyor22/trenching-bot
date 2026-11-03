import { Anchor, Group, ScrollArea, Stack, Table, Text } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import { useBackfills, type AutomationBackfill, type AutomationStatus } from '../../api/automation';
import { PagingFooter, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import {
  backfillDays,
  backfillKind,
  buyBuckets,
  scanFraction,
  zoneDateTime,
} from './automationModel';
import { AutomationFrame } from './AutomationShared';
import { BackfillForm } from './BackfillForm';
import { BackfillStateChip } from './BackfillShared';

const PAGE_SIZE = 20;

/** What the backfill has produced so far, in a few words. */
function progressText(b: AutomationBackfill): string {
  if (b.state === 'QUEUED') return 'waiting for the next tick';
  if (b.state === 'SCANNING') return `scanning · ${Math.round(scanFraction(b) * 100)}% read`;
  if (b.dryRun)
    return b.summary
      ? `${b.buysPassing} buys pass · ${b.summary.distinctMints} tokens`
      : `${b.buysPassing} buys pass`;
  const buckets = buyBuckets(b.itemCounts);
  return [
    `${b.itemsCreated} ${b.itemsCreated === 1 ? 'buy' : 'buys'}`,
    buckets.replayed && `${buckets.replayed} replayed`,
    buckets.inProgress && `${buckets.inProgress} in progress`,
    buckets.issues && `${buckets.issues} failed or abandoned`,
  ]
    .filter(Boolean)
    .join(' · ');
}

function BackfillList({ status }: { status: AutomationStatus }) {
  const [page, setPage] = useState(1);
  const backfills = useBackfills(page - 1, PAGE_SIZE);
  if (backfills.isPending) return <LoadingState label="Loading backfills…" />;
  if (backfills.isError)
    return <ErrorState error={backfills.error} onRetry={() => void backfills.refetch()} />;
  if (backfills.data.items.length === 0)
    return (
      <EmptyState
        title="No backfills yet"
        description="Start with a dry run: it reads the wallet's history for the chosen days and estimates what a capture would cost."
      />
    );
  return (
    <>
      <ScrollArea type="auto">
        <Table miw={860} aria-label="Backfills">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Days</Table.Th>
              <Table.Th>Kind</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th>Progress</Table.Th>
              <Table.Th ta="right">Requests / budget</Table.Th>
              <Table.Th>Started</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {backfills.data.items.map((b) => (
              <Table.Tr key={b.id}>
                <Table.Th scope="row" fw={400}>
                  <Anchor component={Link} to={paths.automationBackfill(b.id)} fw={600} size="sm">
                    {backfillDays(b)}
                  </Anchor>
                </Table.Th>
                <Table.Td>
                  <Text size="sm">{backfillKind(b)}</Text>
                </Table.Td>
                <Table.Td>
                  <BackfillStateChip state={b.state} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {progressText(b)}
                  </Text>
                </Table.Td>
                <Table.Td ta="right" ff="monospace">
                  {b.requestsSpent.toLocaleString('en-US')} /{' '}
                  {b.requestBudget.toLocaleString('en-US')}
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {zoneDateTime(b.createdAt, status.zone)}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <PagingFooter
        page={page}
        pages={Math.ceil(backfills.data.total / PAGE_SIZE)}
        total={backfills.data.total}
        onChange={setPage}
        noun="backfills"
      />
    </>
  );
}

function Backfills({ status }: { status: AutomationStatus }) {
  return (
    <Stack gap="lg">
      <Group align="flex-start" gap="lg" wrap="wrap">
        <Stack style={{ flex: '1 1 420px', minWidth: 0 }}>
          <Section
            title="New backfill"
            hint="Fills older trading days with Decu's past buys. A dry run reads the history and estimates; a capture records and replays the buys within its request budget."
          >
            <BackfillForm status={status} />
          </Section>
        </Stack>
        <Stack style={{ flex: '1 1 320px', minWidth: 0 }}>
          <Section title="How backfills work">
            <Stack gap="xs">
              <Text size="sm">
                1. Run a <strong>dry run</strong> for the days you want. It spends only discovery
                requests and reports buys per day and an estimate.
              </Text>
              <Text size="sm">
                2. From its result, <strong>capture this range</strong> with a budget near the
                estimate. Captures run after tonight's live buys, never before them.
              </Text>
              <Text size="sm">
                3. Once every buy of a day and the 6 days before it has finished, the day gets a
                verdict.
              </Text>
              <Text size="sm" c="dimmed">
                One backfill runs at a time. A capture fills days before live coverage only.
              </Text>
            </Stack>
          </Section>
        </Stack>
      </Group>
      <Section
        title="All backfills"
        hint="Newest first. A running backfill refreshes every 15 seconds."
      >
        <BackfillList status={status} />
      </Section>
    </Stack>
  );
}

export function BackfillsPage() {
  return <AutomationFrame>{(status) => <Backfills status={status} />}</AutomationFrame>;
}

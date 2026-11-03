import { Anchor, Group, ScrollArea, Stack, Table, Text } from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import { useBackfills, type AutomationBackfill, type AutomationStatus } from '../../api/automation';
import { PagingFooter, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { formatCount } from '../../lib/format';
import { paths } from '../../paths';
import {
  backfillDays,
  backfillKind,
  buyBuckets,
  parsePage,
  scanFraction,
  zoneDateTime,
} from './automationModel';
import { AutomationFrame, RunDiscoveryNow } from './AutomationShared';
import { BackfillForm } from './BackfillForm';
import { BackfillStateChip } from './BackfillShared';

const PAGE_SIZE = 20;

/** What the backfill has produced so far, in a few words. */
function progressText(b: AutomationBackfill): string {
  if (b.state === 'QUEUED') return 'waiting for the next tick';
  if (b.state === 'SCANNING') return `scanning · ${Math.round(scanFraction(b) * 100)}% read`;
  if (b.dryRun)
    return b.summary
      ? `${formatCount(b.buysPassing)} buys pass · ${formatCount(b.summary.distinctMints)} tokens`
      : `${formatCount(b.buysPassing)} buys pass`;
  const buckets = buyBuckets(b.itemCounts);
  return [
    `${formatCount(b.itemsCreated)} ${b.itemsCreated === 1 ? 'buy' : 'buys'}`,
    buckets.replayed && `${formatCount(buckets.replayed)} replayed`,
    buckets.inProgress && `${formatCount(buckets.inProgress)} in progress`,
    buckets.issues && `${formatCount(buckets.issues)} failed or abandoned`,
  ]
    .filter(Boolean)
    .join(' · ');
}

function BackfillList({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const page = parsePage(params.get('page'));
  const setPage = (next: number) =>
    setParams(
      (prev) => {
        const updated = new URLSearchParams(prev);
        if (next === 1) updated.delete('page');
        else updated.set('page', String(next));
        return updated;
      },
      { replace: true },
    );
  const backfills = useBackfills(page - 1, PAGE_SIZE);
  return (
    <QueryView
      query={backfills}
      loadingLabel="Loading backfills…"
      errorTitle="Backfills unavailable"
    >
      {(data) =>
        data.items.length === 0 ? (
          <EmptyState
            title={page > 1 ? 'No backfills on this page' : 'No backfills yet'}
            description={
              page > 1
                ? 'Go back to the first page.'
                : "Start with a dry run: it reads the wallet's history for the chosen days and estimates what a capture would cost."
            }
          />
        ) : (
          <>
            <ScrollArea type="auto" style={{ opacity: backfills.isPlaceholderData ? 0.6 : 1 }}>
              <Table miw={860} verticalSpacing="xs" aria-label="Backfills">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th className="console-sticky">Days</Table.Th>
                    <Table.Th>Kind</Table.Th>
                    <Table.Th>State</Table.Th>
                    <Table.Th>Progress</Table.Th>
                    <Table.Th ta="right">Requests / budget</Table.Th>
                    <Table.Th>Started</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.items.map((b) => (
                    <Table.Tr key={b.id}>
                      <Table.Th scope="row" fw={400} className="console-sticky">
                        <Anchor
                          component={Link}
                          to={paths.automationBackfill(b.id)}
                          fw={600}
                          size="sm"
                        >
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
                        {formatCount(b.requestsSpent)} / {formatCount(b.requestBudget)}
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
              pages={Math.ceil(data.total / PAGE_SIZE)}
              total={data.total}
              onChange={setPage}
              noun="backfills"
            />
          </>
        )
      }
    </QueryView>
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
                estimate. Captures run after today's live buys, never before them.
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
  return (
    // The backfill form's submit is this page's primary action, so Run discovery now is secondary here.
    <AutomationFrame actions={(status) => <RunDiscoveryNow status={status} primary={false} />}>
      {(status) => <Backfills status={status} />}
    </AutomationFrame>
  );
}

import {
  Anchor,
  Button,
  Group,
  Pagination,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useDatasets, useRuns } from '../../api/queries';
import type { Dataset, RunSummary } from '../../api/types';
import { Mint, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { IconPlay } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, shortId } from '../../lib/format';
import { paths } from '../../paths';

const PAGE_SIZE = 20;

function RunsTable({
  runs,
  datasetNames,
}: {
  runs: RunSummary[];
  datasetNames: Map<string, string>;
}) {
  const navigate = useNavigate();
  return (
    <ScrollArea type="auto">
      <Table highlightOnHover verticalSpacing="sm" miw={1000}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Created</Table.Th>
            <Table.Th>Run</Table.Th>
            <Table.Th>Dataset</Table.Th>
            <Table.Th ta="right">Scenarios</Table.Th>
            <Table.Th ta="right">Filled / rejected</Table.Th>
            <Table.Th ta="right">Open / closed</Table.Th>
            <Table.Th ta="right">Realized P&amp;L</Table.Th>
            <Table.Th>Quote mint</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {runs.map((run) => (
            <Table.Tr
              key={run.id}
              style={{ cursor: 'pointer' }}
              onClick={() => void navigate(paths.run(run.id))}
            >
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                <Timestamp iso={run.createdAt} />
              </Table.Td>
              <Table.Td>
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
              </Table.Td>
              <Table.Td>
                <Text size="sm">{datasetNames.get(run.datasetId) ?? ''}</Text>
                <ShortId value={run.datasetId} c="dimmed" size="xs" />
              </Table.Td>
              <Table.Td ta="right">{formatCount(run.scenarioCount)}</Table.Td>
              <Table.Td ta="right">
                <Text span c="teal" size="sm" fw={600}>
                  {formatCount(run.filledCount)}
                </Text>
                {' / '}
                <Text span c={run.rejectedCount > 0 ? 'orange' : 'dimmed'} size="sm" fw={600}>
                  {formatCount(run.rejectedCount)}
                </Text>
              </Table.Td>
              <Table.Td ta="right">
                {formatCount(run.openCount)} / {formatCount(run.closedCount)}
              </Table.Td>
              <Table.Td ta="right">
                <Pnl value={run.realizedPnl} />
              </Table.Td>
              <Table.Td>
                <Mint mint={run.quoteMint} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const datasetId = params.get('datasetId');
  const requestedPage = Number(params.get('page') ?? '0');
  const page = Number.isSafeInteger(requestedPage) && requestedPage >= 0 ? requestedPage : 0;
  const runs = useRuns(page, PAGE_SIZE, datasetId);
  const datasets = useDatasets(0, 100);
  const datasetList: Dataset[] = datasets.data?.items ?? [];
  const datasetNames = new Map(datasetList.map((d) => [d.id, d.name]));
  const data = runs.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  const update = (next: { datasetId?: string | null; page?: number }) => {
    const nextParams = new URLSearchParams(params);
    if (next.datasetId !== undefined) {
      if (next.datasetId) nextParams.set('datasetId', next.datasetId);
      else nextParams.delete('datasetId');
      nextParams.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page > 0) nextParams.set('page', String(next.page));
      else nextParams.delete('page');
    }
    setParams(nextParams);
  };

  return (
    <Stack gap="lg">
      <PageHeader
        title="Runs"
        description="Every persisted replay run. Runs are immutable: the same dataset, configuration and engine always give the same run."
        actions={
          <Button
            component={Link}
            to={datasetId ? paths.newRunFor(datasetId) : paths.newRun}
            leftSection={<IconPlay size={16} />}
          >
            New run
          </Button>
        }
      />
      <Section
        title="Run history"
        actions={
          <Select
            aria-label="Filter by dataset"
            placeholder="All datasets"
            data={datasetList.map((d) => ({ value: d.id, label: `${d.name} · ${shortId(d.id)}` }))}
            value={datasetId}
            onChange={(value) => update({ datasetId: value })}
            clearable
            searchable
            w={300}
          />
        }
      >
        {runs.isPending ? (
          <LoadingState label="Loading runs…" />
        ) : runs.isError ? (
          <ErrorState
            error={runs.error}
            title="Could not load runs"
            onRetry={() => void runs.refetch()}
          />
        ) : data && data.items.length === 0 ? (
          <EmptyState
            title={datasetId ? 'No runs for this dataset' : 'No runs yet'}
            description="Start a run from a stored dataset to see it here."
            action={
              <Button
                component={Link}
                to={datasetId ? paths.newRunFor(datasetId) : paths.newRun}
                variant="light"
              >
                Start a run
              </Button>
            }
          />
        ) : data ? (
          <>
            <RunsTable runs={data.items} datasetNames={datasetNames} />
            <Group justify="space-between" mt="sm">
              <Text size="sm" c="dimmed">
                {formatCount(data.total)} runs
              </Text>
              {pages > 1 && (
                <Pagination
                  value={page + 1}
                  total={pages}
                  onChange={(p) => update({ page: p - 1 })}
                  size="sm"
                />
              )}
            </Group>
          </>
        ) : null}
      </Section>
    </Stack>
  );
}

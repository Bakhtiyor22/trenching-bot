import { Anchor, Box, Group, Pagination, ScrollArea, Stack, Table, Text } from '@mantine/core';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useDatasets, useDeleteDataset } from '../../api/queries';
import type { Dataset } from '../../api/types';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { DatasetInUse, DeleteDatasetText } from './DatasetDelete';
import { Mint, PairLabel, ShortId, Timestamp } from '../../components/DisplayValues';
import { PageHeader, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { formatCount, slotRange } from '../../lib/format';
import { paths } from '../../paths';
import { PAGE_PARAM, pageFromParam } from './datasetParams';
import { DatasetUpload } from './DatasetUpload';

const PAGE_SIZE = 20;

function DatasetTable({ items }: { items: Dataset[] }) {
  const navigate = useNavigate();
  const deleteDataset = useDeleteDataset();
  return (
    <ScrollArea type="auto">
      <Table highlightOnHover verticalSpacing="xs" miw={900}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th className="console-sticky">Name</Table.Th>
            <Table.Th>Id</Table.Th>
            <Table.Th>Wallet</Table.Th>
            <Table.Th ta="right">Transactions</Table.Th>
            <Table.Th ta="right">Observations</Table.Th>
            <Table.Th>Slot range</Table.Th>
            <Table.Th>Pairs</Table.Th>
            <Table.Th>Created</Table.Th>
            <Table.Th aria-label="Actions" />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((dataset) => (
            // The name is the row's link (keyboard and screen readers); a click anywhere else on the row
            // is a mouse shortcut to the same page.
            <Table.Tr
              key={dataset.id}
              style={{ cursor: 'pointer' }}
              onClick={() => void navigate(paths.dataset(dataset.id))}
            >
              <Table.Td className="console-sticky">
                <Anchor
                  component={Link}
                  to={paths.dataset(dataset.id)}
                  fw={600}
                  onClick={(e) => e.stopPropagation()}
                >
                  {dataset.name}
                </Anchor>
              </Table.Td>
              <Table.Td>
                <ShortId value={dataset.id} />
              </Table.Td>
              <Table.Td>
                <Mint mint={dataset.trackedWallet} />
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {formatCount(dataset.transactionCount)}
              </Table.Td>
              <Table.Td ta="right" ff="monospace">
                {formatCount(dataset.observationCount)}
              </Table.Td>
              <Table.Td ff="monospace" style={{ whiteSpace: 'nowrap' }}>
                {slotRange(dataset)}
              </Table.Td>
              <Table.Td>
                <Group gap={4}>
                  {dataset.pairs.map((pair) => (
                    <PairLabel key={`${pair.base}/${pair.quote}`} pair={pair} />
                  ))}
                </Group>
              </Table.Td>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                <Timestamp iso={dataset.createdAt} />
              </Table.Td>
              {/* The dialog renders in a portal, whose clicks still bubble to the row through React. */}
              <Table.Td onClick={(e) => e.stopPropagation()}>
                <ConfirmDelete
                  compact
                  label="Delete"
                  title="Delete this dataset?"
                  onConfirm={() => deleteDataset.mutateAsync(dataset.id)}
                  explainError={(error) => <DatasetInUse error={error} datasetId={dataset.id} />}
                >
                  <DeleteDatasetText name={dataset.name} />
                </ConfirmDelete>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

export function DatasetsPage() {
  const [params, setParams] = useSearchParams();
  const page = pageFromParam(params.get(PAGE_PARAM));
  const datasets = useDatasets(page, PAGE_SIZE);
  const setPage = (next: number) =>
    setParams((current) => {
      const updated = new URLSearchParams(current);
      if (next > 0) updated.set(PAGE_PARAM, String(next));
      else updated.delete(PAGE_PARAM);
      return updated;
    });

  return (
    <>
      <PageHeader
        title="Datasets"
        description="Replay datasets are stored by checksum: the same content uploaded twice is one dataset."
      />
      <Stack gap="lg">
        <Section title="Add a dataset">
          <DatasetUpload />
        </Section>
        <Section title="Stored datasets">
          <QueryView
            query={datasets}
            loadingLabel="Loading datasets…"
            errorTitle="Could not load datasets"
          >
            {(data) => {
              const pages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
              if (data.items.length === 0 && page > 0 && data.total > 0) {
                return (
                  <EmptyState
                    title="This page is empty"
                    description={`There are ${formatCount(data.total)} datasets on ${formatCount(pages)} pages.`}
                    action={
                      <Anchor component="button" type="button" onClick={() => setPage(0)}>
                        Go to the first page
                      </Anchor>
                    }
                  />
                );
              }
              if (data.items.length === 0) {
                return (
                  <EmptyState
                    title="No datasets yet"
                    description="Drop a replay dataset file above, import the sample, or prepare one from a capture."
                    action={
                      <Anchor component={Link} to={paths.captures}>
                        Go to Captures
                      </Anchor>
                    }
                  />
                );
              }
              return (
                <Box
                  style={{
                    opacity: datasets.isPlaceholderData ? 0.6 : 1,
                    transition: 'opacity 120ms',
                  }}
                >
                  <DatasetTable items={data.items} />
                  <Group justify="space-between" mt="sm">
                    <Text size="sm" c="dimmed">
                      {formatCount(data.total)} datasets
                    </Text>
                    {pages > 1 && (
                      <Pagination
                        value={page + 1}
                        total={pages}
                        onChange={(p) => setPage(p - 1)}
                        size="sm"
                      />
                    )}
                  </Group>
                </Box>
              );
            }}
          </QueryView>
        </Section>
      </Stack>
    </>
  );
}

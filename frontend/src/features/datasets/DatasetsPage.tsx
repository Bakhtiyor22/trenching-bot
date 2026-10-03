import { Anchor, Group, Pagination, ScrollArea, Stack, Table, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useDatasets } from '../../api/queries';
import type { Dataset } from '../../api/types';
import { Mint, PairLabel, ShortId, Timestamp } from '../../components/DisplayValues';
import { PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, slotRange } from '../../lib/format';
import { paths } from '../../paths';
import { DatasetUpload } from './DatasetUpload';

const PAGE_SIZE = 20;

function DatasetTable({ items }: { items: Dataset[] }) {
  const navigate = useNavigate();
  return (
    <ScrollArea type="auto">
      <Table highlightOnHover verticalSpacing="sm" miw={900}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Id</Table.Th>
            <Table.Th>Wallet</Table.Th>
            <Table.Th ta="right">Transactions</Table.Th>
            <Table.Th ta="right">Observations</Table.Th>
            <Table.Th>Slot range</Table.Th>
            <Table.Th>Pairs</Table.Th>
            <Table.Th>Created</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((dataset) => (
            <Table.Tr
              key={dataset.id}
              style={{ cursor: 'pointer' }}
              onClick={() => void navigate(paths.dataset(dataset.id))}
            >
              <Table.Td>
                <Anchor component={Link} to={paths.dataset(dataset.id)} fw={600} onClick={(e) => e.stopPropagation()}>
                  {dataset.name}
                </Anchor>
              </Table.Td>
              <Table.Td>
                <ShortId value={dataset.id} />
              </Table.Td>
              <Table.Td>
                <Mint mint={dataset.trackedWallet} />
              </Table.Td>
              <Table.Td ta="right">{formatCount(dataset.transactionCount)}</Table.Td>
              <Table.Td ta="right">{formatCount(dataset.observationCount)}</Table.Td>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>{slotRange(dataset)}</Table.Td>
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
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

export function DatasetsPage() {
  const [page, setPage] = useState(0);
  const datasets = useDatasets(page, PAGE_SIZE);
  const data = datasets.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <Stack gap="lg">
      <PageHeader
        title="Datasets"
        description="Replay datasets are stored by checksum: the same content uploaded twice is one dataset."
      />
      <Section title="Add a dataset">
        <DatasetUpload />
      </Section>
      <Section title="Stored datasets">
        {datasets.isPending ? (
          <LoadingState label="Loading datasets…" />
        ) : datasets.isError ? (
          <ErrorState error={datasets.error} title="Could not load datasets" onRetry={() => void datasets.refetch()} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No datasets yet" description="Upload a replay dataset or import the sample above." />
        ) : data ? (
          <>
            <DatasetTable items={data.items} />
            <Group justify="space-between" mt="sm">
              <Text size="sm" c="dimmed">
                {formatCount(data.total)} datasets
              </Text>
              {pages > 1 && <Pagination value={page + 1} total={pages} onChange={(p) => setPage(p - 1)} size="sm" />}
            </Group>
          </>
        ) : null}
      </Section>
    </Stack>
  );
}

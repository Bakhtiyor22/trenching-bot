import { Button, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { pairKey } from '../../api/endpoints';
import { useDataset, useDatasetObservations, useDeleteDataset } from '../../api/queries';
import type { ObservedChartMarker, PaperChartFill } from '../../api/types';
import { TradeChart } from '../../components/TradeChart';
import { SourceLinks } from '../../components/SourceLinks';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { Mint, ShortId, Timestamp } from '../../components/DisplayValues';
import { ObservationTable } from '../../components/ObservationTable';
import { IconPlay } from '../../components/Icons';
import { KeyValueTable, PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatCount, mintLabel, slotRange } from '../../lib/format';
import { paths } from '../../paths';

function ObservationsPanel({
  datasetId,
  pair,
  selectedId,
  navigationRequest,
}: {
  datasetId: string;
  pair: string | null;
  selectedId: string | null;
  navigationRequest: number;
}) {
  const observations = useDatasetObservations(datasetId, pair, pair !== null);
  if (pair === null) return <EmptyState title="This dataset has no pairs" />;
  if (observations.isPending) return <LoadingState label="Loading observations…" />;
  if (observations.isError)
    return <ErrorState error={observations.error} onRetry={() => void observations.refetch()} />;
  const items = observations.data;
  return (
    <Section title="Observations">
      <ObservationTable key={navigationRequest} observations={items} selectedId={selectedId} />
    </Section>
  );
}

export function DatasetDetailPage() {
  const { id = '' } = useParams();
  const dataset = useDataset(id);
  const navigate = useNavigate();
  const deleteDataset = useDeleteDataset();
  const [selectedPair, setSelectedPair] = useState<string | null>(null);
  const [selectedObservation, setSelectedObservation] = useState<string | null>(null);
  const [navigationRequest, setNavigationRequest] = useState(0);
  const openAction = (action: ObservedChartMarker | PaperChartFill) => {
    if (action.kind !== 'OBSERVED') return;
    setSelectedObservation(action.observationId);
    setNavigationRequest((request) => request + 1);
    document
      .getElementById(`observation-${encodeURIComponent(action.observationId)}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  if (dataset.isPending) return <LoadingState label="Loading dataset…" />;
  if (dataset.isError)
    return (
      <ErrorState
        error={dataset.error}
        title="Could not load the dataset"
        onRetry={() => void dataset.refetch()}
      />
    );

  const data = dataset.data;
  const pairOptions = data.pairs.map((pair) => ({
    value: pairKey(pair),
    label: `${mintLabel(pair.base)} / ${mintLabel(pair.quote)}`,
  }));
  const pair = selectedPair ?? pairOptions[0]?.value ?? null;

  return (
    <Stack gap="lg">
      <SourceLinks source={data.source} />
      <PageHeader
        title={data.name}
        description="Stored replay dataset. Its id is the dataset checksum."
        actions={
          <>
            <Button component={Link} to={paths.runsFor(data.id)} variant="default">
              Runs of this dataset
            </Button>
            <Button
              component={Link}
              to={paths.newRunFor(data.id)}
              leftSection={<IconPlay size={16} />}
            >
              Start run with this dataset
            </Button>
            <ConfirmDelete
              label="Delete dataset"
              title="Delete this dataset?"
              onConfirm={async () => {
                await deleteDataset.mutateAsync(data.id);
                await navigate(paths.datasets);
              }}
            >
              Removes “{data.name}” from Datasets. Runs made with it are kept, each with its own
              copy of the dataset. Uploading the same file again restores it under the same id.
            </ConfirmDelete>
          </>
        }
      />
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Section title="Summary">
          <KeyValueTable
            rows={[
              ['Id (checksum)', <ShortId key="id" value={data.id} />],
              ['Tracked wallet', <Mint key="w" mint={data.trackedWallet} />],
              ['Schema version', data.schemaVersion],
              ['Created', <Timestamp key="c" iso={data.createdAt} />],
            ]}
          />
        </Section>
        <Section title="Contents">
          <KeyValueTable
            rows={[
              ['Transactions', formatCount(data.transactionCount)],
              ['Observations', formatCount(data.observationCount)],
              ['Slot range', slotRange(data)],
              ['Pairs', formatCount(data.pairs.length)],
            ]}
          />
        </Section>
      </SimpleGrid>
      {pairOptions.length > 1 && (
        <Select
          label="Pair"
          data={pairOptions}
          value={pair}
          onChange={(value) => setSelectedPair(value)}
          allowDeselect={false}
          maw={420}
        />
      )}
      {pairOptions.length === 1 && (
        <Text size="sm" c="dimmed">
          Pair: {pairOptions[0]?.label}
        </Text>
      )}
      {pair !== null && (
        <TradeChart
          kind="dataset"
          id={data.id}
          pair={pair}
          trackedWallet={data.trackedWallet}
          onOpenAction={openAction}
        />
      )}
      <ObservationsPanel
        key={pair}
        datasetId={data.id}
        pair={pair}
        selectedId={selectedObservation}
        navigationRequest={navigationRequest}
      />
    </Stack>
  );
}

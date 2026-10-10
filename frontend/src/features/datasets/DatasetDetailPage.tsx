import { Button, Group, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { pairKey } from '../../api/endpoints';
import { useDataset, useDatasetObservations } from '../../api/queries';
import { Chart } from '../../components/Chart';
import { Mint, ShortId, Timestamp } from '../../components/DisplayValues';
import { ObservationTable } from '../../components/ObservationTable';
import { IconPlay } from '../../components/Icons';
import { KeyValueTable, PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { priceChartOption, QUALITY_STYLE } from '../../charts/priceChart';
import { formatCount, humanizeCode, mintLabel, slotRange } from '../../lib/format';
import { paths } from '../../paths';

function ObservationsPanel({ datasetId, pair }: { datasetId: string; pair: string | null }) {
  const observations = useDatasetObservations(datasetId, pair, pair !== null);
  if (pair === null) return <EmptyState title="This dataset has no pairs" />;
  if (observations.isPending) return <LoadingState label="Loading observations…" />;
  if (observations.isError) return <ErrorState error={observations.error} onRetry={() => void observations.refetch()} />;
  const items = observations.data;
  return (
    <Stack gap="lg">
      <Section
        title="Price vs slot"
        hint="Each point is an observation; its color and shape show the quality. Invalid observations are not joined by the line. Ctrl + scroll or drag the slider to zoom; drag the chart to pan."
      >
        {items.length === 0 ? (
          <EmptyState title="No observations for this pair" />
        ) : (
          <>
            <Chart option={priceChartOption(items)} height={360} ariaLabel="Observed price by slot for the selected pair" />
            <Group gap="md" mt="xs">
              {Object.entries(QUALITY_STYLE).map(([quality, style]) => (
                <Group key={quality} gap={6}>
                  <span style={{ width: 10, height: 10, borderRadius: 5, background: style.color, display: 'inline-block' }} />
                  <Text size="xs" c="dimmed">
                    {humanizeCode(quality)}
                  </Text>
                </Group>
              ))}
            </Group>
          </>
        )}
      </Section>
      <Section title="Observations">
        <ObservationTable observations={items} />
      </Section>
    </Stack>
  );
}

export function DatasetDetailPage() {
  const { id = '' } = useParams();
  const dataset = useDataset(id);
  const [selectedPair, setSelectedPair] = useState<string | null>(null);

  if (dataset.isPending) return <LoadingState label="Loading dataset…" />;
  if (dataset.isError) return <ErrorState error={dataset.error} title="Could not load the dataset" onRetry={() => void dataset.refetch()} />;

  const data = dataset.data;
  const pairOptions = data.pairs.map((pair) => ({
    value: pairKey(pair),
    label: `${mintLabel(pair.base)} / ${mintLabel(pair.quote)}`,
  }));
  const pair = selectedPair ?? pairOptions[0]?.value ?? null;

  return (
    <Stack gap="lg">
      <PageHeader
        title={data.name}
        description="Stored replay dataset. Its id is the dataset checksum."
        actions={
          <>
            <Button component={Link} to={paths.runsFor(data.id)} variant="default">
              Runs of this dataset
            </Button>
            <Button component={Link} to={paths.newRunFor(data.id)} leftSection={<IconPlay size={16} />}>
              Start run with this dataset
            </Button>
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
      <ObservationsPanel datasetId={data.id} pair={pair} />
    </Stack>
  );
}

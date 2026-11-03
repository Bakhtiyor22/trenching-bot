import { Anchor, Button, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { pairKey } from '../../api/endpoints';
import { useDataset, useDatasetObservations, useDeleteDataset } from '../../api/queries';
import type { ObservedChartMarker, PaperChartFill } from '../../api/types';
import { TradeChart } from '../../components/TradeChart';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { DatasetInUse, DeleteDatasetText } from './DatasetDelete';
import { Mint, ShortId, Timestamp } from '../../components/DisplayValues';
import { ObservationTable } from '../../components/ObservationTable';
import { IconPlay } from '../../components/Icons';
import { KeyValueTable, Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { formatCount, mintLabel, slotRange } from '../../lib/format';
import { paths } from '../../paths';
import { PAIR_PARAM, pairFromParam } from './datasetParams';

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
  if (pair === null) {
    return (
      <EmptyState
        title="This dataset has no pairs"
        description="It holds no market observations, so there is nothing to chart or replay. Upload a dataset with recorded trades, or prepare one from a capture."
        action={
          <Button component={Link} to={paths.captures} variant="default">
            Go to Captures
          </Button>
        }
      />
    );
  }
  return (
    <Section title="Observations">
      <QueryView
        query={observations}
        loadingLabel="Loading observations…"
        errorTitle="Could not load observations"
      >
        {(items) => (
          <ObservationTable key={navigationRequest} observations={items} selectedId={selectedId} />
        )}
      </QueryView>
    </Section>
  );
}

export function DatasetDetailPage() {
  const { id = '' } = useParams();
  const dataset = useDataset(id);
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const deleteDataset = useDeleteDataset();
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
  const selectPair = (value: string | null) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (value) updated.set(PAIR_PARAM, value);
        else updated.delete(PAIR_PARAM);
        return updated;
      },
      { replace: true },
    );

  return (
    <QueryView
      query={dataset}
      loadingLabel="Loading dataset…"
      errorTitle="Could not load the dataset"
    >
      {(data) => {
        const pairOptions = data.pairs.map((pair) => ({
          value: pairKey(pair),
          label: `${mintLabel(pair.base)} / ${mintLabel(pair.quote)}`,
        }));
        const pair = pairFromParam(
          params.get(PAIR_PARAM),
          pairOptions.map((option) => option.value),
        );
        return (
          <>
            <PageHeader
              breadcrumbs={[{ label: 'Datasets', to: paths.datasets }, { label: data.name }]}
              title={data.name}
              meta={
                data.source?.captureId && (
                  <>
                    From capture{' '}
                    <Anchor component={Link} to={paths.capture(data.source.captureId)} size="xs">
                      {data.source.captureDisplayName ?? data.source.captureId}
                    </Anchor>
                  </>
                )
              }
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
                    explainError={(error) => <DatasetInUse error={error} datasetId={data.id} />}
                  >
                    <DeleteDatasetText name={data.name} />
                  </ConfirmDelete>
                </>
              }
            />
            <Stack gap="lg">
              <KpiStrip label="Dataset contents" min={180}>
                <Kpi label="Pairs">{formatCount(data.pairs.length)}</Kpi>
                <Kpi label="Observations">{formatCount(data.observationCount)}</Kpi>
                <Kpi label="Transactions">{formatCount(data.transactionCount)}</Kpi>
                <Kpi label="Slot range" span="full">
                  {slotRange(data)}
                </Kpi>
              </KpiStrip>
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
                {pairOptions.length > 0 && (
                  <Section title="Pair" hint="The chart and observations below show this pair.">
                    {pairOptions.length > 1 ? (
                      <Select
                        label="Pair"
                        data={pairOptions}
                        value={pair}
                        onChange={selectPair}
                        allowDeselect={false}
                        maw={420}
                      />
                    ) : (
                      <Text size="sm" ff="monospace">
                        {pairOptions[0]?.label}
                      </Text>
                    )}
                  </Section>
                )}
              </SimpleGrid>
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
          </>
        );
      }}
    </QueryView>
  );
}

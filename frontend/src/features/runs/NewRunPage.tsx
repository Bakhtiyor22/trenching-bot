import {
  Alert,
  Button,
  Group,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { useConfigDefaults, useDataset, useDatasets, useStartRun } from '../../api/queries';
import type { ConfigDefaults, Dataset } from '../../api/types';
import { IconAlert, IconPlay } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { mintLabel, shortId } from '../../lib/format';
import { paths } from '../../paths';
import {
  FIELD_HINTS,
  FIELD_LABELS,
  formValuesFrom,
  INTEGER_FIELDS,
  rangeText,
  toRunStartRequest,
  validateRunForm,
  type RunFormValues,
} from './runForm';

/** Navigation state passed to run detail so it can say whether the run already existed. */
export interface RunStartNavigationState {
  created: boolean;
}

function datasetOptions(datasets: Dataset[]) {
  return datasets.map((d) => ({ value: d.id, label: `${d.name} · ${shortId(d.id)}` }));
}

function RunConfigForm({
  config,
  datasets,
  initialDatasetId,
}: {
  config: ConfigDefaults;
  datasets: Dataset[];
  initialDatasetId: string | null;
}) {
  const { defaults, limits } = config;
  const navigate = useNavigate();
  const startRun = useStartRun();
  const knownDataset = initialDatasetId && datasets.some((d) => d.id === initialDatasetId);
  const form = useForm<RunFormValues>({
    mode: 'controlled',
    initialValues: formValuesFrom(
      defaults,
      knownDataset ? initialDatasetId : (datasets[0]?.id ?? null),
    ),
    validate: (values) => validateRunForm(values, limits),
    validateInputOnBlur: true,
  });

  const submit = form.onSubmit((values) => {
    startRun.mutate(toRunStartRequest(values), {
      onSuccess: ({ run, created }) => {
        notifications.show({
          color: created ? 'teal' : 'blue',
          title: created ? 'Run finished' : 'Run already existed',
          message: `Run ${shortId(run.id)}`,
        });
        const state: RunStartNavigationState = { created };
        void navigate(paths.run(run.id), { state });
      },
      onError: (error) => {
        if (error instanceof ApiError && Object.keys(error.fields).length > 0) {
          form.setErrors(error.fields);
        }
      },
    });
  });

  const serverError = startRun.error;
  const fieldErrorsShown =
    serverError instanceof ApiError && Object.keys(serverError.fields).length > 0;

  return (
    <form onSubmit={submit} noValidate aria-label="New run configuration">
      <Stack gap="lg">
        <Section title="Dataset">
          <Select
            label="Dataset"
            placeholder="Choose a dataset"
            data={datasetOptions(datasets)}
            searchable
            nothingFoundMessage="No matching dataset"
            allowDeselect={false}
            withAsterisk
            {...form.getInputProps('datasetId')}
          />
        </Section>

        <Section
          title="Simulation parameters"
          hint="Prefilled with the dip-entry defaults: buy a 15% dip below the wallet's first buy at a market cap of at least $10,000, then take profit at +30% or stop out at -15% within about 30 minutes. Identical dataset, parameters and engine always produce the same run."
          actions={
            <Button
              variant="subtle"
              size="xs"
              onClick={() => form.setValues(formValuesFrom(defaults, form.values.datasetId))}
            >
              Reset to defaults
            </Button>
          }
        >
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
            <TextInput
              label="Notional"
              description={`${FIELD_HINTS.notional} At most ${limits.notionalScale} decimals (whole lamports).`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('notional')}
            />
            <TextInput
              label="Minimum market cap (USD)"
              description={`${FIELD_HINTS.minMarketCapUsd} Range ${rangeText(limits.minMarketCapUsd)}.`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('minMarketCapUsd')}
            />
            <TextInput
              label="Transaction cost (SOL)"
              description={`${FIELD_HINTS.transactionCostSol} Range ${limits.transactionCostSol.min} – ${limits.transactionCostSol.max}, at most ${limits.transactionCostScale} decimals.`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('transactionCostSol')}
            />
            {INTEGER_FIELDS.map((field) => (
              <NumberInput
                key={field}
                label={FIELD_LABELS[field]}
                description={`${FIELD_HINTS[field]} Range ${rangeText(limits[field])}.`}
                allowDecimal={false}
                allowNegative={false}
                min={limits[field].min}
                max={limits[field].max ?? undefined}
                clampBehavior="none"
                withAsterisk
                {...form.getInputProps(field)}
              />
            ))}
            <TextInput
              label="Quote mint"
              description={FIELD_HINTS.quoteMint}
              autoComplete="off"
              spellCheck={false}
              ff="monospace"
              rightSection={
                <Text size="xs" c="dimmed" pr="xs">
                  {form.values.quoteMint ? mintLabel(form.values.quoteMint.trim()) : ''}
                </Text>
              }
              rightSectionWidth={110}
              withAsterisk
              {...form.getInputProps('quoteMint')}
            />
          </SimpleGrid>
          <Text size="xs" c="dimmed" mt="md">
            Strategy {defaults.strategyName} v{defaults.strategyVersion} — strategy and simulation
            semantics are fixed in code and cannot be edited here.
          </Text>
        </Section>

        {serverError && (
          <Alert
            color="red"
            variant="light"
            icon={<IconAlert />}
            title="The run was not started"
            role="alert"
          >
            {errorMessage(serverError)}
            {fieldErrorsShown && ' See the highlighted fields.'}
          </Alert>
        )}

        <Group justify="flex-end">
          <Button
            type="submit"
            loading={startRun.isPending}
            leftSection={<IconPlay size={16} />}
            size="md"
          >
            Start run
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

export function NewRunPage() {
  const [params] = useSearchParams();
  const defaults = useConfigDefaults();
  const datasets = useDatasets(0, 100);
  const initialDatasetId = params.get('datasetId');
  const needsLinkedDataset =
    Boolean(initialDatasetId) &&
    datasets.isSuccess &&
    !datasets.data.items.some((dataset) => dataset.id === initialDatasetId);
  const linkedDataset = useDataset(initialDatasetId ?? '', needsLinkedDataset);
  const availableDatasets =
    linkedDataset.data && needsLinkedDataset
      ? [...(datasets.data?.items ?? []), linkedDataset.data]
      : (datasets.data?.items ?? []);

  return (
    <>
      <PageHeader
        title="New run"
        description="Replay a stored dataset through the paper-trading simulator. Runs are synchronous and finish in seconds."
      />
      {defaults.isPending ||
      datasets.isPending ||
      (needsLinkedDataset && linkedDataset.isPending) ? (
        <LoadingState label="Loading defaults and datasets…" />
      ) : defaults.isError ? (
        <ErrorState
          error={defaults.error}
          title="Could not load the configuration defaults"
          onRetry={() => void defaults.refetch()}
        />
      ) : datasets.isError ? (
        <ErrorState
          error={datasets.error}
          title="Could not load datasets"
          onRetry={() => void datasets.refetch()}
        />
      ) : needsLinkedDataset && linkedDataset.isError ? (
        <ErrorState
          error={linkedDataset.error}
          title="Could not load the selected dataset"
          onRetry={() => void linkedDataset.refetch()}
        />
      ) : availableDatasets.length === 0 ? (
        <Alert color="blue" variant="light" title="No datasets yet">
          Upload a dataset or import the sample on the{' '}
          <Text span component={Link} to={paths.datasets} c="blue" td="underline">
            Datasets
          </Text>{' '}
          page first.
        </Alert>
      ) : (
        <RunConfigForm
          key={initialDatasetId}
          config={defaults.data}
          datasets={availableDatasets}
          initialDatasetId={initialDatasetId}
        />
      )}
    </>
  );
}

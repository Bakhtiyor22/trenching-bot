import {
  Alert,
  Badge,
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
import { useQuery } from '@tanstack/react-query';
import { useRef } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { useConfigDefaults, useDataset, useDatasets, useStartRun } from '../../api/queries';
import type { ConfigDefaults, Dataset } from '../../api/types';
import { setupApi, type Recommendation, type RunPreset } from '../../api/setup';
import { Recommendations } from '../../components/Recommendations';
import { SourceLinks } from '../../components/SourceLinks';
import { codeLabel, presetStatusLabels } from '../../lib/codes';
import { compareDecimals, isDecimalString, trimFractionZeros } from '../../lib/decimal';
import { formatPercent } from '../../lib/percent';
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
  PERCENT_FIELDS,
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
  preset,
}: {
  config: ConfigDefaults;
  datasets: Dataset[];
  initialDatasetId: string | null;
  preset?: RunPreset;
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
  const sourceDataset = useDataset(form.values.datasetId ?? '', !!form.values.datasetId);
  const recommendations = useQuery({
    queryKey: ['run-recommendations', form.values.datasetId],
    queryFn: ({ signal }) => setupApi.runRecommendations(form.values.datasetId ?? '', signal),
    enabled: !!form.values.datasetId,
  });
  const undoValues = useRef<RunFormValues | null>(null);
  const legacy = useStartRun();
  function applyRecommendation(item: Recommendation) {
    if (item.id === 'USE_AGREED_STRATEGY' && preset) {
      undoValues.current = { ...form.values };
      form.setValues(formValuesFrom(preset.config, form.values.datasetId));
    }
    if (item.id === 'ADD_LEGACY_COMPARISON') {
      startRun.mutate(
        { ...toRunStartRequest(form.values), ...(preset ? { presetId: preset.id } : {}) },
        {
          onSuccess: (current) => {
            legacy.mutate(
              { datasetId: form.values.datasetId ?? '' },
              {
                onSuccess: (comparison) => {
                  void navigate(paths.compareRuns([current.run.id, comparison.run.id]));
                },
              },
            );
          },
        },
      );
    }
  }
  function fieldLabel(field: keyof RunFormValues, label: string) {
    const configField = field.replace('Percent', 'Bps');
    const metadata = preset?.fields.find((item) => item.field === configField);
    const original = formValuesFrom(defaults, form.values.datasetId)[field];
    const current = form.values[field];
    const equal =
      isDecimalString(String(current)) && isDecimalString(String(original))
        ? compareDecimals(String(current), String(original)) === 0
        : current === original;
    const status = !preset
      ? 'Engine default · not the agreed strategy'
      : !equal
        ? `Changed by you · agreed ${String(original)}`
        : metadata
          ? presetStatusLabels[metadata.status]
          : 'Engine default';
    return (
      <Group gap={6}>
        <Text span inherit>
          {label}
        </Text>
        <Badge
          size="xs"
          variant="light"
          color={!equal || metadata?.status === 'MISSING' ? 'yellow' : 'teal'}
        >
          {status}
        </Badge>
      </Group>
    );
  }

  const submit = form.onSubmit((values) => {
    startRun.mutate(
      { ...toRunStartRequest(values), ...(preset ? { presetId: preset.id } : {}) },
      {
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
      },
    );
  });

  const serverError = startRun.error;
  const fieldErrorsShown =
    serverError instanceof ApiError && Object.keys(serverError.fields).length > 0;

  return (
    <form onSubmit={submit} noValidate aria-label="New run configuration">
      <Stack gap="lg">
        {preset ? (
          <Alert color="teal" title="Agreed strategy · applied">
            <Stack gap="xs">
              <Text size="sm">
                {preset.source.strategy} v{preset.source.version} · {preset.engine} · spec updated{' '}
                {preset.source.updated}
              </Text>
              <Text size="sm">
                {preset.fields.filter((field) => field.status === 'AGREED').length} settings map
                exactly · {preset.unsupportedRules.length} unsupported rules ·{' '}
                {preset.fields.filter((field) => field.status === 'MISSING').length} missing setting
              </Text>
              <details>
                <summary>Read the spec and engine limitations</summary>
                <Text size="sm">Source: {preset.source.document}</Text>
                {preset.unsupportedRules.map((rule) => (
                  <Text key={rule.code} size="sm">
                    {rule.specRef}: {codeLabel(rule.code)}
                  </Text>
                ))}
              </details>
            </Stack>
          </Alert>
        ) : (
          <Alert color="yellow" title="Agreed preset unavailable">
            Using engine defaults. These are not the agreed strategy settings.
          </Alert>
        )}
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
          <SourceLinks source={sourceDataset.data?.source} />
        </Section>

        <Section
          title="Simulation parameters"
          hint="Percent values are sent exactly as entered. Identical dataset, parameters and engine always produce the same run."
          actions={
            <Button
              variant="subtle"
              size="xs"
              onClick={() => form.setValues(formValuesFrom(defaults, form.values.datasetId))}
            >
              {preset ? 'Reset to agreed' : 'Reset to defaults'}
            </Button>
          }
        >
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
            <TextInput
              label={fieldLabel('notional', 'Notional')}
              description={`${FIELD_HINTS.notional} At most ${limits.notionalScale} decimals (whole lamports).`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('notional')}
            />
            <TextInput
              label={fieldLabel('minMarketCapUsd', 'Minimum market cap (USD)')}
              description={`${FIELD_HINTS.minMarketCapUsd} Range ${rangeText(limits.minMarketCapUsd)}.`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('minMarketCapUsd')}
            />
            <TextInput
              label={fieldLabel('transactionCostSol', 'Transaction cost (SOL)')}
              description={`${FIELD_HINTS.transactionCostSol} Range ${limits.transactionCostSol.min} – ${limits.transactionCostSol.max}, at most ${limits.transactionCostScale} decimals.`}
              inputMode="decimal"
              autoComplete="off"
              withAsterisk
              {...form.getInputProps('transactionCostSol')}
            />
            {PERCENT_FIELDS.map((field) => (
              <TextInput
                key={field}
                label={fieldLabel(field, FIELD_LABELS[field])}
                description={FIELD_HINTS[field]}
                inputMode="decimal"
                withAsterisk
                {...form.getInputProps(field)}
              />
            ))}
            {INTEGER_FIELDS.map((field) => (
              <NumberInput
                key={field}
                label={fieldLabel(field, FIELD_LABELS[field])}
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
              label={fieldLabel('quoteMint', 'Quote mint')}
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
        <Section title="In plain words">
          <Text size="sm">
            Spend {trimFractionZeros(form.values.notional)} SOL on a{' '}
            {formatPercent(form.values.dipPercent)} dip. Take profit at{' '}
            {formatPercent(form.values.takeProfitPercent, { signed: true })}, or stop after a{' '}
            {formatPercent(form.values.stopLossPercent)} fall. Watch for {form.values.horizonSlots}{' '}
            slots; this observation window is not agreed in the strategy spec.
          </Text>
        </Section>
        <Recommendations
          items={recommendations.data ?? []}
          onApply={applyRecommendation}
          onUndo={() => {
            if (undoValues.current) form.setValues(undoValues.current);
          }}
        />
        {legacy.error && (
          <ErrorState error={legacy.error} title="Legacy comparison could not be started" />
        )}

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
  const preset = useQuery({
    queryKey: ['run-preset'],
    queryFn: ({ signal }) => setupApi.preset(signal),
    retry: false,
    staleTime: Infinity,
  });
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
      preset.isPending ||
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
          config={{ ...defaults.data, defaults: preset.data?.config ?? defaults.data.defaults }}
          preset={preset.data}
          datasets={availableDatasets}
          initialDatasetId={initialDatasetId}
        />
      )}
    </>
  );
}

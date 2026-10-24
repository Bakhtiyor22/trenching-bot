import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Grid,
  Group,
  NumberInput,
  Popover,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { useConfigDefaults, useDataset, useDatasets, useStartRun } from '../../api/queries';
import type { ConfigDefaults, Dataset } from '../../api/types';
import { setupApi, type Recommendation, type RunPreset } from '../../api/setup';
import { Recommendations } from '../../components/Recommendations';
import { codeLabel, MARKET_COVERAGE_ENDED, presetStatusLabels } from '../../lib/codes';
import { formatDecimal, trimFractionZeros } from '../../lib/decimal';
import { formatPercent } from '../../lib/percent';
import { IconAlert, IconPlay } from '../../components/Icons';
import { PageHeader, Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { mintLabel, shortId, slotRange } from '../../lib/format';
import { paths } from '../../paths';
import {
  changedSettings,
  formatSettingValue,
  SETTING_LABELS,
  type SettingField,
} from './configLabels';
import './NewRunPage.css';
import {
  FIELD_HINTS,
  formValuesFrom,
  INTEGER_FIELDS,
  MILLIS_FIELD,
  PERCENT_FIELDS,
  rangeText,
  SECONDS_FIELDS,
  secondsText,
  type SecondsField,
  toRunStartRequest,
  validateRunForm,
  type RunFormValues,
} from './runForm';

export interface RunStartNavigationState {
  created: boolean;
}

function datasetOptions(datasets: Dataset[]) {
  return datasets.map((d) => ({
    value: d.id,
    label: `${d.name} — ${d.pairs.length} ${d.pairs.length === 1 ? 'token' : 'tokens'}, ${d.transactionCount.toLocaleString()} trades`,
  }));
}

const SETTING_FIELDS: SettingField[] = [
  ...PERCENT_FIELDS,
  ...SECONDS_FIELDS,
  'notional',
  'minMarketCapUsd',
  'transactionCostSol',
  'latencySlots',
  'horizonSlots',
  'maxAgeSlots',
  'quoteMint',
];
const UNITS: Partial<Record<SettingField, string>> = {
  dipPercent: '%',
  takeProfitPercent: '%',
  stopLossPercent: '%',
  dipWindowSeconds: 's',
  maxHoldSeconds: 's',
  hardExitStartSeconds: 's',
  notional: 'SOL',
  minMarketCapUsd: 'USD',
  transactionCostSol: 'SOL',
  latencySlots: 'slot',
  horizonSlots: 'slots',
  maxAgeSlots: 'slots',
};
/** The form label of a preset field, which the preset names by its config field (dipBps, dipWindowMs, …). */
function fieldLabel(field: string): string {
  const seconds = (Object.keys(MILLIS_FIELD) as SecondsField[]).find(
    (key) => MILLIS_FIELD[key] === field,
  );
  const formField = seconds ?? field.replace('Bps', 'Percent');
  return SETTING_LABELS[formField as SettingField] ?? formField;
}
function specDate(value: string) {
  return new Date(value).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}
function percentBps(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value)) return null;
  const [whole = '0', fraction = ''] = value.split('.');
  return formatDecimal((BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))).toString());
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
  const legacy = useStartRun();
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
  const agreed = formValuesFrom(defaults, form.values.datasetId);
  const changed = changedSettings(form.values, defaults);
  const missingFields = preset?.fields.filter((field) => field.status === 'MISSING') ?? [];
  const isAgreed = preset !== undefined && changed.length === 0;
  async function applyRecommendation(item: Recommendation) {
    if (item.id === 'USE_AGREED_STRATEGY' && preset) {
      undoValues.current = { ...form.values };
      form.setValues(formValuesFrom(preset.config, form.values.datasetId));
    }
    if (item.id === 'ADD_LEGACY_COMPARISON') {
      // A refusal stays in startRun/legacy error state and is shown beside Start run.
      const current = await startRun
        .mutateAsync({
          ...toRunStartRequest(form.values),
          ...(preset ? { presetId: preset.id } : {}),
        })
        .catch(() => null);
      if (!current) return;
      const comparison = await legacy
        .mutateAsync({ datasetId: form.values.datasetId ?? '' })
        .catch(() => null);
      if (comparison) await navigate(paths.compareRuns([current.run.id, comparison.run.id]));
    }
  }
  function startLegacyRun() {
    legacy.mutate(
      { datasetId: form.values.datasetId ?? '' },
      { onSuccess: ({ run }) => void navigate(paths.run(run.id)) },
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
          if (error instanceof ApiError && Object.keys(error.fields).length > 0)
            form.setErrors(error.fields);
        },
      },
    );
  });
  const serverError = startRun.error;
  const fieldErrorsShown =
    serverError instanceof ApiError && Object.keys(serverError.fields).length > 0;

  function settingCard(field: SettingField) {
    const isClock = (SECONDS_FIELDS as readonly string[]).includes(field);
    const presetField = isClock
      ? MILLIS_FIELD[field as SecondsField]
      : field.replace('Percent', 'Bps');
    const metadata = preset?.fields.find((item) => item.field === presetField);
    const edited = changed.includes(field);
    const missing = metadata?.status === 'MISSING';
    const status = !preset
      ? 'Engine default · not the agreed strategy'
      : edited
        ? `Changed by you · agreed ${formatSettingValue(field, agreed[field])}`
        : metadata
          ? presetStatusLabels[metadata.status]
          : 'Engine default';
    const isInteger = (INTEGER_FIELDS as readonly string[]).includes(field);
    const bps = field.endsWith('Percent') ? percentBps(String(form.values[field])) : null;
    const unit = UNITS[field];
    const prefix =
      field === 'takeProfitPercent'
        ? '+'
        : field === 'stopLossPercent'
          ? '−'
          : field === 'minMarketCapUsd'
            ? '$'
            : undefined;
    const inputProps = {
      label: SETTING_LABELS[field],
      withAsterisk: !isClock,
      leftSection: prefix,
      rightSection: unit ? (
        <Text size="xs" c="dimmed">
          {unit}
        </Text>
      ) : undefined,
      rightSectionWidth: unit ? 52 : undefined,
      ...form.getInputProps(field),
    };
    return (
      <Card
        key={field}
        padding="md"
        withBorder
        style={{
          background: missing
            ? 'var(--mantine-color-red-light)'
            : edited
              ? 'var(--mantine-color-yellow-light)'
              : undefined,
          borderColor: missing
            ? 'var(--mantine-color-red-8)'
            : edited
              ? 'var(--mantine-color-yellow-8)'
              : undefined,
        }}
      >
        <Stack gap="xs">
          <Badge
            size="xs"
            variant="light"
            color={missing ? 'red' : edited ? 'yellow' : 'teal'}
            style={{ maxWidth: '100%', whiteSpace: 'normal', height: 'auto', lineHeight: 1.6 }}
          >
            {status}
          </Badge>
          {isInteger ? (
            <NumberInput
              {...inputProps}
              allowDecimal={false}
              allowNegative={false}
              min={limits[field as (typeof INTEGER_FIELDS)[number]].min}
              max={limits[field as (typeof INTEGER_FIELDS)[number]].max ?? undefined}
              clampBehavior="none"
            />
          ) : (
            <TextInput
              {...inputProps}
              inputMode={field === 'quoteMint' ? 'text' : 'decimal'}
              autoComplete="off"
              spellCheck={false}
              ff={field === 'quoteMint' ? 'monospace' : undefined}
            />
          )}
          <Text size="xs" c="dimmed">
            {FIELD_HINTS[field]}
            {bps !== null && ` ${formatPercent(String(form.values[field]))} = ${bps} bps.`}
            {isInteger && ` Range ${rangeText(limits[field as (typeof INTEGER_FIELDS)[number]])}.`}
            {isClock &&
              ` Range ${secondsText(limits[MILLIS_FIELD[field as SecondsField]].min)} – ${secondsText(limits[MILLIS_FIELD[field as SecondsField]].max)} s.`}
            {field === 'quoteMint' && ` ${mintLabel(form.values.quoteMint.trim())}.`}
          </Text>
          {metadata?.noteCode && (
            <Text size="xs" c={missing ? 'red' : 'yellow'}>
              {codeLabel(metadata.noteCode)}
            </Text>
          )}
          {preset && edited && (
            <Button
              variant="subtle"
              color="yellow"
              size="compact-xs"
              style={{ alignSelf: 'flex-start' }}
              onClick={() => form.setFieldValue(field, agreed[field])}
            >
              Reset to {formatSettingValue(field, agreed[field])}
            </Button>
          )}
        </Stack>
      </Card>
    );
  }

  return (
    <form onSubmit={submit} noValidate aria-label="New run configuration">
      <Stack gap="lg">
        {preset ? (
          <Section
            title={`Agreed strategy: ${preset.source.strategy} v${preset.source.version}`}
            actions={
              <Group gap="xs">
                <Badge color={isAgreed ? 'teal' : 'yellow'}>
                  {isAgreed ? 'Applied' : `Changed · ${changed.length} settings`}
                </Badge>
                <Button variant="default" size="xs" onClick={() => form.setValues(agreed)}>
                  Reset to agreed
                </Button>
                <Popover position="bottom-end" width={320}>
                  <Popover.Target>
                    <Button variant="subtle" size="xs">
                      Read the spec
                    </Button>
                  </Popover.Target>
                  <Popover.Dropdown>
                    <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                      {preset.source.document}
                    </Text>
                  </Popover.Dropdown>
                </Popover>
              </Group>
            }
          >
            <Text size="sm" mb="md">
              From the strategy spec, last updated {specDate(preset.source.updated)}. You can change
              any setting below; changed ones are marked.
            </Text>
            <Alert color="yellow" title="Simulator limitations">
              <Stack gap="xs">
                <Text size="sm">
                  This simulator ({preset.engine}) applies{' '}
                  {preset.fields.filter((field) => field.status === 'AGREED').length} of the agreed
                  rules exactly.
                </Text>
                {preset.unsupportedRules.length > 0 && (
                  <Text size="sm">
                    {preset.unsupportedRules.length} rules need the new decu-dip v2 engine, which is
                    planned but not built yet:{' '}
                    {preset.unsupportedRules.map((rule) => codeLabel(rule.code)).join(', ')}.
                  </Text>
                )}
                {missingFields.length > 0 && (
                  <Text size="sm">
                    {missingFields.length}{' '}
                    {missingFields.length === 1 ? 'setting has' : 'settings have'} no agreed value
                    for this simulator:{' '}
                    {missingFields.map((field) => fieldLabel(field.field).toLowerCase()).join(', ')}
                    . {missingFields.length === 1 ? 'It is' : 'They are'} flagged below rather than
                    guessed.
                  </Text>
                )}
              </Stack>
            </Alert>
          </Section>
        ) : (
          <Alert color="yellow" title="Engine defaults">
            These are not the agreed strategy settings.
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
          {sourceDataset.data?.source?.captureId && (
            <Text size="xs" mt="xs">
              From capture{' '}
              <Text
                span
                component={Link}
                to={paths.capture(sourceDataset.data.source.captureId)}
                c="blue"
                td="underline"
              >
                {sourceDataset.data.source.captureDisplayName ??
                  shortId(sourceDataset.data.source.captureId)}
              </Text>
              .
            </Text>
          )}
          {sourceDataset.data?.firstSlot != null && sourceDataset.data.lastSlot != null && (
            <Text size="xs" c="dimmed" mt="xs">
              Slots {slotRange(sourceDataset.data)}
            </Text>
          )}
        </Section>
        <Grid align="flex-start">
          <Grid.Col span={{ base: 12, lg: 8 }}>
            <Section
              title="Simulation parameters"
              hint="Identical dataset, parameters and engine always produce the same run."
              actions={
                !preset ? (
                  <Button variant="subtle" size="xs" onClick={() => form.setValues(agreed)}>
                    Reset to defaults
                  </Button>
                ) : undefined
              }
            >
              <SimpleGrid cols={{ base: 1, sm: 2 }}>{SETTING_FIELDS.map(settingCard)}</SimpleGrid>
            </Section>
          </Grid.Col>
          <Grid.Col span={{ base: 12, lg: 4 }} className="run-plain-words">
            <Box>
              <Section title="In plain words">
                <Stack gap="md">
                  <Text size="sm">
                    After Decu's first buy of a token, wait for the price to drop{' '}
                    <strong>{formatPercent(form.values.dipPercent)}</strong> below his price. Then
                    buy <strong>{trimFractionZeros(form.values.notional)} SOL</strong>, and sell at{' '}
                    <strong>+{formatPercent(form.values.takeProfitPercent)}</strong> or{' '}
                    <strong>−{formatPercent(form.values.stopLossPercent)}</strong> from our fill.
                    {form.values.dipWindowSeconds.trim() !== '' && (
                      <>
                        {' '}
                        Only a dip within <strong>{form.values.dipWindowSeconds} s</strong> of his
                        buy counts.
                      </>
                    )}
                    {form.values.maxHoldSeconds.trim() !== '' &&
                      form.values.hardExitStartSeconds.trim() !== '' && (
                        <>
                          {' '}
                          Otherwise sell at the latest{' '}
                          <strong>{form.values.maxHoldSeconds} s</strong> after our fill or{' '}
                          <strong>{form.values.hardExitStartSeconds} s</strong> after his buy,
                          whichever comes first.
                        </>
                      )}
                  </Text>
                  <Text size="sm">
                    Settings you changed: <strong>{changed.length}</strong>
                    {changed.length > 0
                      ? ` (${changed.map((field) => SETTING_LABELS[field]).join(', ')})`
                      : ''}
                    .
                  </Text>
                  <Button
                    type="submit"
                    loading={startRun.isPending || legacy.isPending}
                    leftSection={<IconPlay size={16} />}
                    size="md"
                  >
                    Start run
                  </Button>
                  {serverError && (
                    <RunStartError
                      error={serverError}
                      fieldErrorsShown={fieldErrorsShown}
                      onRunLegacy={startLegacyRun}
                      legacyPending={legacy.isPending}
                    />
                  )}
                  {legacy.error && (
                    <ErrorState
                      error={legacy.error}
                      title="The old-defaults run could not be started"
                    />
                  )}
                </Stack>
              </Section>
            </Box>
          </Grid.Col>
        </Grid>
        <Recommendations
          items={(recommendations.data ?? []).filter(
            (item) => item.id !== 'USE_AGREED_STRATEGY' || !isAgreed,
          )}
          onApply={applyRecommendation}
          appliedIds={isAgreed ? ['USE_AGREED_STRATEGY'] : []}
          onUndo={() => {
            if (undoValues.current) form.setValues(undoValues.current);
          }}
        />
      </Stack>
    </form>
  );
}

/** Why the run was refused, beside Start run. A dataset cut short by the old cutoff gets a way forward. */
function RunStartError({
  error,
  fieldErrorsShown,
  onRunLegacy,
  legacyPending,
}: {
  error: Error;
  fieldErrorsShown: boolean;
  onRunLegacy: () => void;
  legacyPending: boolean;
}) {
  const coverageEnded = error instanceof ApiError && error.code === MARKET_COVERAGE_ENDED;
  return (
    <Alert
      color="red"
      variant="light"
      icon={<IconAlert />}
      title={
        coverageEnded
          ? 'This dataset ends before these settings can finish'
          : 'The run was not started'
      }
      role="alert"
    >
      <Stack gap="xs">
        <Text size="sm">
          {coverageEnded
            ? 'Its capture stopped recording the token once the outcome under the old default rules was decided, so a run with these settings would be cut short. Run the old defaults on it, or capture the token again with the market cutoff set to the whole window.'
            : `${errorMessage(error)}${fieldErrorsShown ? ' See the highlighted fields.' : ''}`}
        </Text>
        {coverageEnded && (
          <Button size="xs" variant="light" onClick={onRunLegacy} loading={legacyPending}>
            Run the old defaults instead
          </Button>
        )}
        {error instanceof ApiError && <CodeText code={error.code} />}
      </Stack>
    </Alert>
  );
}

export function NewRunPage() {
  const [params] = useSearchParams();
  const [continueDefaults, setContinueDefaults] = useState(false);
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
      ) : preset.isError && !continueDefaults ? (
        <Alert color="yellow" title="Agreed strategy could not be loaded">
          <Stack gap="sm">
            <Text size="sm">
              The agreed strategy settings are unavailable. Retry, or continue with this simulator's
              engine defaults; they are not the agreed strategy.
            </Text>
            <Text size="xs">Technical code: STRATEGY_PRESET_UNAVAILABLE</Text>
            <Group>
              <Button
                variant="default"
                loading={preset.isFetching}
                onClick={() => void preset.refetch()}
              >
                Retry
              </Button>
              <Button onClick={() => setContinueDefaults(true)}>Continue with defaults</Button>
            </Group>
          </Stack>
        </Alert>
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
          key={`${initialDatasetId}/${preset.data?.id ?? 'defaults'}`}
          config={{ ...defaults.data, defaults: preset.data?.config ?? defaults.data.defaults }}
          preset={preset.data}
          datasets={availableDatasets}
          initialDatasetId={initialDatasetId}
        />
      )}
    </>
  );
}

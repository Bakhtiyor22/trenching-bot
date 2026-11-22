import {
  Alert,
  Box,
  Button,
  Checkbox,
  Grid,
  Group,
  List,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm, type UseFormReturnType } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router';
import {
  useEditVariation,
  type AutomationVariation,
  type VariationEditResult,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import type { ConfigLimits } from '../../api/types';
import { IconAlert } from '../../components/Icons';
import { Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { mintLabel } from '../../lib/format';
import { paths } from '../../paths';
import { SETTING_LABELS, type SettingField } from '../runs/configLabels';
import {
  FIELD_HINTS,
  formValuesFrom,
  INTEGER_FIELDS,
  MILLIS_FIELD,
  PERCENT_FIELDS,
  placeFieldErrors,
  PRICE_MEASURE_LABELS,
  rangeText,
  SECONDS_FIELDS,
  secondsText,
  type RunFormValues,
  type SecondsField,
} from '../runs/runForm';
import {
  editFieldReasons,
  settingChanges,
  unplacedReasonsText,
  validateVariationForm,
  variationConfigRequest,
  variationEditErrorText,
} from './automationModel';

/** The settings in the form's order: entry and exit first, then the rules, sizes and timing. */
const SETTING_FIELDS: SettingField[] = [
  ...PERCENT_FIELDS,
  ...SECONDS_FIELDS,
  'minSignalSol',
  'priceMeasure',
  'followWalletSells',
  'notional',
  'minMarketCapUsd',
  'transactionCostSol',
  'latencySlots',
  'horizonSlots',
  'maxAgeSlots',
  'quoteMint',
];
const INPUT_FIELDS: readonly (keyof RunFormValues)[] = SETTING_FIELDS;
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
  minSignalSol: 'SOL',
  latencySlots: 'slots',
  horizonSlots: 'slots',
  maxAgeSlots: 'slots',
};

/** Where the edit's new variation is shown after saving, and how its page says so. */
export interface VariationCreatedState {
  createdFrom: string;
  created: boolean;
}

function SettingInput({
  field,
  form,
  limits,
  captureMinSol,
  edited,
}: {
  field: SettingField;
  form: UseFormReturnType<RunFormValues>;
  limits: ConfigLimits;
  captureMinSol: string;
  edited: boolean;
}) {
  const isClock = (SECONDS_FIELDS as readonly string[]).includes(field);
  const isInteger = (INTEGER_FIELDS as readonly string[]).includes(field);
  const unit = UNITS[field];
  const props = {
    label: SETTING_LABELS[field],
    withAsterisk: !isClock && field !== 'priceMeasure',
    description: edited ? 'Changed' : undefined,
    rightSection: unit ? (
      <Text size="xs" c="dimmed">
        {unit}
      </Text>
    ) : undefined,
    rightSectionWidth: unit ? 48 : undefined,
    ...form.getInputProps(field),
  };
  const hint = [
    FIELD_HINTS[field],
    isInteger ? `Range ${rangeText(limits[field as (typeof INTEGER_FIELDS)[number]])}.` : '',
    isClock
      ? `Range ${secondsText(limits[MILLIS_FIELD[field as SecondsField]].min)} – ${secondsText(limits[MILLIS_FIELD[field as SecondsField]].max)} s.`
      : '',
    field === 'quoteMint' ? `${mintLabel(form.values.quoteMint.trim())}.` : '',
    field === 'minSignalSol'
      ? `A variation needs one, at least the capture filter (${captureMinSol} SOL).`
      : '',
  ]
    .filter(Boolean)
    .join(' ');
  const input =
    field === 'followWalletSells' ? (
      <Checkbox
        label={SETTING_LABELS[field]}
        description={edited ? 'Changed' : undefined}
        {...form.getInputProps(field, { type: 'checkbox' })}
      />
    ) : field === 'priceMeasure' ? (
      <Select
        {...props}
        data={limits.priceMeasure.map((value) => ({ value, label: PRICE_MEASURE_LABELS[value] }))}
        allowDeselect={false}
      />
    ) : isInteger ? (
      <NumberInput
        {...props}
        allowDecimal={false}
        allowNegative={false}
        clampBehavior="none"
        min={limits[field as (typeof INTEGER_FIELDS)[number]].min}
        max={limits[field as (typeof INTEGER_FIELDS)[number]].max ?? undefined}
      />
    ) : (
      <TextInput
        {...props}
        inputMode={field === 'quoteMint' ? 'text' : 'decimal'}
        autoComplete="off"
        spellCheck={false}
        ff={field === 'quoteMint' ? 'monospace' : undefined}
      />
    );
  return (
    <Box
      p="sm"
      data-edited={edited || undefined}
      style={{
        border: `1px solid ${edited ? 'var(--console-line-strong)' : 'var(--mantine-color-default-border)'}`,
        borderRadius: 8,
        background: edited ? 'var(--console-raised)' : undefined,
      }}
    >
      <Stack gap={6}>
        {input}
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      </Stack>
    </Box>
  );
}

/**
 * Edits a variation: every setting prefilled from it, the complete configuration posted. Beside the form, before
 * saving: each change ("Take profit 55% → 80%") and what saving does. A refused edit puts the backend's field reasons
 * on their inputs; any other refusal is said in words.
 */
export function VariationEditForm({
  variation,
  limits,
  captureMinSol,
  nextLabel,
  onCancel,
}: {
  variation: AutomationVariation;
  limits: ConfigLimits;
  /** The capture filter from `/status`: a variation's minimum signal size must be at least this. */
  captureMinSol: string;
  /** The label the new variation will probably get, for the text only. */
  nextLabel: string | null;
  onCancel: () => void;
}) {
  const { label } = variation;
  const navigate = useNavigate();
  const edit = useEditVariation(label);
  const form = useForm<RunFormValues>({
    mode: 'controlled',
    initialValues: formValuesFrom(variation.config, null),
    validate: (values) => validateVariationForm(values, limits, captureMinSol),
    validateInputOnBlur: true,
  });
  const changes = settingChanges(form.values, variation.config);
  const changed = new Set(changes.map((c) => c.field));

  function saved({ variation: created, created: isNew }: VariationEditResult) {
    notifications.show({
      color: isNew ? 'teal' : 'blue',
      title: isNew ? `${created.label} created` : `${created.label} already existed`,
      message: isNew
        ? `${created.label} replaces ${label} and runs only on buys captured from now on.`
        : `This edit was saved earlier; ${created.label} is the variation it created.`,
    });
    const state: VariationCreatedState = { createdFrom: label, created: isNew };
    void navigate(paths.automationVariation(created.label), { state });
  }

  const submit = form.onSubmit((values) => {
    edit.mutate(variationConfigRequest(values), {
      onSuccess: saved,
      onError: (error) => {
        if (!(error instanceof ApiError)) return;
        const { inputs } = placeFieldErrors(editFieldReasons(error.fields), INPUT_FIELDS);
        if (Object.keys(inputs).length > 0) form.setErrors(inputs);
      },
    });
  });

  const error = edit.error;
  const placed =
    error instanceof ApiError
      ? placeFieldErrors(editFieldReasons(error.fields), INPUT_FIELDS)
      : null;
  const highlighted = Object.keys(placed?.inputs ?? {}).filter(
    (field) => form.errors[field] != null,
  ).length;
  // No answer, or a server failure: the same submit again reuses its request id, so it cannot save twice.
  const retryable = error instanceof ApiError && (error.status === 0 || error.status >= 500);

  return (
    <form onSubmit={submit} noValidate aria-label={`Edit variation ${label}`}>
      <Grid align="flex-start">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Section
            title={`Edit ${label}`}
            hint="Every setting is sent, prefilled with this variation's own. Changed settings are marked."
            actions={
              <Button
                variant="subtle"
                size="xs"
                onClick={() => form.setValues(formValuesFrom(variation.config, null))}
                disabled={changes.length === 0}
              >
                Reset all
              </Button>
            }
          >
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              {SETTING_FIELDS.map((field) => (
                <SettingInput
                  key={field}
                  field={field}
                  form={form}
                  limits={limits}
                  captureMinSol={captureMinSol}
                  edited={changed.has(field)}
                />
              ))}
            </SimpleGrid>
          </Section>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Box style={{ position: 'sticky', top: 16 }}>
            <Section title="Before you save">
              <Stack gap="md">
                {changes.length === 0 ? (
                  <Text size="sm" c="dimmed">
                    No setting changed yet. Change at least one to save a new variation.
                  </Text>
                ) : (
                  <Stack gap={6}>
                    <Text size="sm" fw={600}>
                      {changes.length} {changes.length === 1 ? 'change' : 'changes'}
                    </Text>
                    <List size="sm" spacing={4} aria-label="Changes">
                      {changes.map((c) => (
                        <List.Item key={c.field}>
                          {c.label}{' '}
                          <Text span ff="monospace" size="sm">
                            {c.from} → {c.to}
                          </Text>
                        </List.Item>
                      ))}
                    </List>
                  </Stack>
                )}
                <Stack gap={6}>
                  <Text size="sm" fw={600}>
                    What saving does
                  </Text>
                  <List size="sm" spacing={4}>
                    <List.Item>
                      {label} is archived: it is no longer replayed, and its existing runs stay
                      where they are.
                    </List.Item>
                    <List.Item>
                      {nextLabel
                        ? `A new variation, ${nextLabel} unless another edit is saved first,`
                        : 'A new variation'}{' '}
                      replaces it with these settings, from the next captured buy on.
                    </List.Item>
                    <List.Item>
                      Buys already replayed are not replayed again, so the new variation starts with
                      no runs.
                    </List.Item>
                  </List>
                </Stack>
                <Group gap="sm">
                  <Button type="submit" loading={edit.isPending} disabled={changes.length === 0}>
                    {retryable ? 'Save again' : `Archive ${label} and save`}
                  </Button>
                  <Button variant="default" onClick={onCancel} disabled={edit.isPending}>
                    Cancel
                  </Button>
                </Group>
                {error && (
                  <Alert
                    color="red"
                    variant="light"
                    icon={<IconAlert />}
                    title="The edit was not saved"
                    role="alert"
                  >
                    <Stack gap="xs">
                      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                        {[
                          variationEditErrorText(error, label),
                          unplacedReasonsText(placed?.unplaced ?? []),
                          highlighted > 0
                            ? `See the highlighted ${highlighted === 1 ? 'field' : 'fields'}.`
                            : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      </Text>
                      {error instanceof ApiError && <CodeText code={error.code} />}
                    </Stack>
                  </Alert>
                )}
              </Stack>
            </Section>
          </Box>
        </Grid.Col>
      </Grid>
    </form>
  );
}

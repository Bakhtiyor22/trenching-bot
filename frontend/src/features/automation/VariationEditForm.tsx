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
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import {
  useCreateVariation,
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
  nameOf,
  settingChanges,
  unplacedReasonsText,
  validateVariationForm,
  variationConfigRequest,
  variationCreateErrorText,
  variationEditErrorText,
} from './automationModel';
import { useVariationBook } from './useVariationBook';

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

/**
 * Where a saved form's new variation is shown, and how its page says so: `createdFrom` is the edited variation's label,
 * or null for a new variation that replaces nothing; `created` is false when the backend returned an earlier one.
 */
export interface VariationCreatedState {
  createdFrom: string | null;
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
 * A variation's settings form, for an edit or for a new variation. Every setting is prefilled (from the edited
 * variation, or from the one a new variation copies) and the complete configuration is posted. Beside the form, before
 * saving: each change ("Take profit 55% → 80%"), the name it saves as, and what saving does. A refused save puts the
 * backend's field reasons on their inputs; any other refusal is said in words.
 */
export function VariationEditForm({
  mode = 'edit',
  variation,
  limits,
  captureMinSol,
  savesAs,
  sourcePicker,
  onCancel,
}: {
  /** `edit` archives `variation` and saves its next revision; `create` saves a new variation copying `variation`. */
  mode?: 'edit' | 'create';
  variation: AutomationVariation;
  limits: ConfigLimits;
  /** The capture filter from `/status`: a variation's minimum signal size must be at least this. */
  captureMinSol: string;
  /** The name it will be saved as (`V03.3`, or `V16` for a new one), for the text only; null when not known yet. */
  savesAs: string | null;
  /** For a new variation: the choice of whose settings to copy, shown above the settings. */
  sourcePicker?: ReactNode;
  onCancel: () => void;
}) {
  const creating = mode === 'create';
  const { label } = variation;
  const name = nameOf(variation);
  const book = useVariationBook();
  const navigate = useNavigate();
  const edit = useEditVariation(label);
  const create = useCreateVariation();
  const save = creating ? create : edit;
  const form = useForm<RunFormValues>({
    mode: 'controlled',
    initialValues: formValuesFrom(variation.config, null),
    validate: (values) => validateVariationForm(values, limits, captureMinSol),
    validateInputOnBlur: true,
  });
  const changes = settingChanges(form.values, variation.config);
  const changed = new Set(changes.map((c) => c.field));
  // An unchanged copy of an active variation would be refused as a duplicate; an archived one's settings may return.
  const unchanged = changes.length === 0;
  const blocked = creating ? unchanged && variation.state === 'ACTIVE' : unchanged;

  function saved({ variation: stored, created: isNew }: VariationEditResult) {
    const storedName = nameOf(stored);
    notifications.show({
      color: isNew ? 'teal' : 'blue',
      title: isNew ? `${storedName} created` : `${storedName} already existed`,
      message: isNew
        ? creating
          ? `${storedName} runs only on buys captured from now on.`
          : `${storedName} replaces ${name} and runs only on buys captured from now on.`
        : `This was saved earlier; ${storedName} is the variation it created.`,
    });
    const state: VariationCreatedState = { createdFrom: creating ? null : label, created: isNew };
    void navigate(paths.automationVariation(stored.label), { state });
  }

  const submit = form.onSubmit((values) => {
    save.mutate(variationConfigRequest(values), {
      onSuccess: saved,
      onError: (error) => {
        if (!(error instanceof ApiError)) return;
        const { inputs } = placeFieldErrors(editFieldReasons(error.fields), INPUT_FIELDS);
        if (Object.keys(inputs).length > 0) form.setErrors(inputs);
      },
    });
  });

  const error = save.error;
  const placed =
    error instanceof ApiError
      ? placeFieldErrors(editFieldReasons(error.fields), INPUT_FIELDS)
      : null;
  const highlighted = Object.keys(placed?.inputs ?? {}).filter(
    (field) => form.errors[field] != null,
  ).length;
  // No answer, or a server failure: the same submit again reuses its request id, so it cannot save twice.
  // A request id another change already used is dropped, so the next save sends a fresh one.
  const retryable =
    error instanceof ApiError &&
    (error.status === 0 || error.status >= 500 || error.code === 'VARIATION_REQUEST_REUSED');
  const errorText = creating
    ? variationCreateErrorText(error, book.name)
    : variationEditErrorText(error, name, book.name);

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label={creating ? 'New variation' : `Edit variation ${name}`}
    >
      <Grid align="flex-start">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Section
            title={creating ? `Settings, copied from ${name}` : `Edit ${name}`}
            hint={
              creating
                ? 'Every setting is sent. Change at least one from an active variation; changed settings are marked.'
                : "Every setting is sent, prefilled with this variation's own. Changed settings are marked."
            }
            actions={
              <Button
                variant="subtle"
                size="xs"
                onClick={() => form.setValues(formValuesFrom(variation.config, null))}
                disabled={unchanged}
              >
                Reset all
              </Button>
            }
          >
            <Stack gap="md">
              {sourcePicker}
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
            </Stack>
          </Section>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Box style={{ position: 'sticky', top: 16 }}>
            <Section title="Before you save">
              <Stack gap="md">
                {savesAs && (
                  <Text size="sm">
                    Saves as{' '}
                    <Text span ff="monospace" fw={600} inherit>
                      {savesAs}
                    </Text>
                    {creating ? ', unless another variation is created first.' : '.'}
                  </Text>
                )}
                {unchanged ? (
                  <Text size="sm" c="dimmed">
                    {creating
                      ? variation.state === 'ACTIVE'
                        ? `No setting changed from ${name} yet. Change at least one: an active variation already has these settings.`
                        : `No setting changed from ${name}, which is archived, so its settings can run again.`
                      : 'No setting changed yet. Change at least one to save a new variation.'}
                  </Text>
                ) : (
                  <Stack gap={6}>
                    <Text size="sm" fw={600}>
                      {changes.length} {changes.length === 1 ? 'change' : 'changes'}
                      {creating ? ` from ${name}` : ''}
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
                  {creating ? (
                    <List size="sm" spacing={4}>
                      <List.Item>
                        A new variation is replayed on every buy captured from now on, next to the
                        active ones.
                      </List.Item>
                      <List.Item>
                        Buys already replayed are not replayed again, so it starts with no runs.
                      </List.Item>
                      <List.Item>
                        Nothing else changes: {name} and every other variation stay as they are.
                      </List.Item>
                    </List>
                  ) : (
                    <List size="sm" spacing={4}>
                      <List.Item>
                        {name} is archived: it is no longer replayed, and its existing runs stay
                        where they are.
                      </List.Item>
                      <List.Item>
                        {savesAs ? `${savesAs}, the next version of ${name},` : 'Its next version'}{' '}
                        replaces it with these settings, from the next captured buy on.
                      </List.Item>
                      <List.Item>
                        Buys already replayed are not replayed again, so the new version starts with
                        no runs.
                      </List.Item>
                    </List>
                  )}
                </Stack>
                <Group gap="sm">
                  <Button type="submit" loading={save.isPending} disabled={blocked}>
                    {retryable
                      ? 'Save again'
                      : creating
                        ? `Create ${savesAs ?? 'variation'}`
                        : `Archive ${name} and save`}
                  </Button>
                  <Button variant="default" onClick={onCancel} disabled={save.isPending}>
                    Cancel
                  </Button>
                </Group>
                {error && (
                  <Alert
                    color="red"
                    variant="light"
                    icon={<IconAlert />}
                    title={creating ? 'The variation was not created' : 'The edit was not saved'}
                    role="alert"
                  >
                    <Stack gap="xs">
                      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                        {[
                          errorText,
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

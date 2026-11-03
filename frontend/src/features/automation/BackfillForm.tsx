import {
  Alert,
  Button,
  Checkbox,
  Group,
  Stack,
  Text,
  Textarea,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useCreateBackfill, type AutomationStatus } from '../../api/automation';
import { ApiError } from '../../api/client';
import { isSolanaAddress } from '../../lib/base58';
import { compareDecimals, isDecimalString } from '../../lib/decimal';
import { formatCount } from '../../lib/format';
import { paths } from '../../paths';
import {
  addDays,
  BACKFILL_LIMITS,
  dayInfo,
  daySpanText,
  daysBetween,
  DEFAULT_DRY_RUN_BUDGET,
  isBackfillRefusal,
  isFilteredBackfill,
  isRealDate,
  parseBackfillPrefill,
  parseMints,
  tradingDayOf,
  zoneDateTime,
} from './automationModel';
import { BackfillRefusal } from './BackfillShared';

const LAMPORT_DECIMALS = 9;
const DATE_WIDTH = { base: '100%', xs: 170 } as const;
/** Link keys in words, for the note about values the form ignored. */
const PREFILL_NAMES: Record<string, string> = {
  from: 'first day',
  to: 'last day',
  dryRun: 'dry run',
  budget: 'budget',
  min: 'minimum signal size',
};
/** Monospace for the typed number only, not the label and description. */
const MONO_INPUT = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

interface Values {
  fromDay: string;
  toDay: string;
  dryRun: boolean;
  budget: string;
  minSignalSol: string;
  mintsText: string;
}

/** Client checks mirroring the backend's (plan Rule 35); the backend's own field errors still win. */
function validate(values: Values, today: string): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!isRealDate(values.fromDay)) errors.fromDay = 'Choose the first day';
  if (!isRealDate(values.toDay)) errors.toDay = 'Choose the last day';
  if (!errors.fromDay && !errors.toDay) {
    const days = daysBetween(values.fromDay, values.toDay) + 1;
    if (days < 1) errors.toDay = 'Must not be before the first day';
    else if (days > BACKFILL_LIMITS.maxDays)
      errors.toDay = `At most ${BACKFILL_LIMITS.maxDays} days at a time`;
    if (values.toDay >= today) errors.toDay = 'Must be a finished trading day (before today)';
  }
  const min = values.dryRun ? BACKFILL_LIMITS.minDryRunBudget : BACKFILL_LIMITS.minCaptureBudget;
  const budget = Number(values.budget);
  if (!/^\d+$/.test(values.budget.trim()) || budget < min || budget > BACKFILL_LIMITS.maxBudget)
    errors.budget = `A whole number from ${formatCount(min)} to ${formatCount(BACKFILL_LIMITS.maxBudget)}`;
  const minSol = values.minSignalSol.trim();
  if (minSol !== '') {
    if (!isDecimalString(minSol) || minSol.startsWith('-'))
      errors.minSignalSol = 'A plain decimal such as 2.5';
    else if ((minSol.split('.')[1] ?? '').replace(/0+$/, '').length > LAMPORT_DECIMALS)
      errors.minSignalSol = 'At most 9 decimals (whole lamports)';
    else if (compareDecimals(minSol, BACKFILL_LIMITS.captureMinSol) < 0)
      errors.minSignalSol = `At least ${BACKFILL_LIMITS.captureMinSol} SOL, the capture filter`;
  }
  const { mints, invalid } = parseMints(values.mintsText, isSolanaAddress);
  if (invalid.length > 0) errors.mints = `Not a valid address: ${invalid.slice(0, 2).join(', ')}`;
  else if (mints.length > BACKFILL_LIMITS.maxMints)
    errors.mints = `At most ${BACKFILL_LIMITS.maxMints} tokens`;
  return errors;
}

/** Field names in the backend's 400 body, mapped to this form's fields. */
const SERVER_FIELDS: Record<string, string> = {
  fromDay: 'fromDay',
  toDay: 'toDay',
  requestBudget: 'budget',
  minSignalSol: 'minSignalSol',
  mints: 'mints',
};

/**
 * The new-backfill form. It defaults to a dry run, so the first step always estimates before anything is captured.
 * The URL can pre-fill it (from a finished dry run's "Capture this range"): `from`, `to`, `dryRun`, `budget`, `min`.
 * Only valid link values are used; the rest keep their defaults and a note says which were ignored.
 */
export function BackfillForm({ status }: { status: AutomationStatus }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const today = tradingDayOf(new Date().toISOString(), status.zone, status.tradingDayStart);
  const coveredDay = status.coveredFrom
    ? tradingDayOf(status.coveredFrom, status.zone, status.tradingDayStart)
    : null;
  const defaultTo = coveredDay ? addDays(coveredDay, -1) : addDays(today, -1);
  const [prefill] = useState(() => parseBackfillPrefill(params));
  const [values, setValues] = useState<Values>(() => ({
    fromDay: prefill.fromDay ?? addDays(defaultTo, -6),
    toDay: prefill.toDay ?? defaultTo,
    dryRun: prefill.dryRun,
    budget:
      prefill.budget ??
      String(prefill.dryRun ? DEFAULT_DRY_RUN_BUDGET : BACKFILL_LIMITS.minCaptureBudget),
    minSignalSol: prefill.minSignalSol ?? '',
    mintsText: '',
  }));
  const [touched, setTouched] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(prefill.minSignalSol != null);
  const create = useCreateBackfill();
  const errors = validate(values, today);
  const serverErrors: Record<string, string> = {};
  if (create.error instanceof ApiError)
    for (const [field, message] of Object.entries(create.error.fields))
      serverErrors[SERVER_FIELDS[field] ?? field] = message;
  const shown = (field: keyof Values) =>
    (touched ? errors[field] : undefined) ?? serverErrors[field];
  const { mints } = parseMints(values.mintsText, isSolanaAddress);
  const filtered = isFilteredBackfill(values.minSignalSol, mints);
  const days =
    isRealDate(values.fromDay) && isRealDate(values.toDay)
      ? daysBetween(values.fromDay, values.toDay) + 1
      : 0;
  const set = <K extends keyof Values>(key: K, value: Values[K]) => {
    create.reset();
    setValues((v) => ({ ...v, [key]: value }));
  };
  const setDryRun = (dryRun: boolean) => {
    create.reset();
    setValues((v) => {
      const budget = Number(v.budget);
      const min = dryRun ? BACKFILL_LIMITS.minDryRunBudget : BACKFILL_LIMITS.minCaptureBudget;
      // Lift a dry-run budget to the capture minimum when switching, so the form stays valid.
      return { ...v, dryRun, budget: !dryRun && budget < min ? String(min) : v.budget };
    });
  };
  const submit = () => {
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    const body = {
      fromDay: values.fromDay,
      toDay: values.toDay,
      dryRun: values.dryRun,
      requestBudget: Number(values.budget),
      ...(values.minSignalSol.trim() ? { minSignalSol: values.minSignalSol.trim() } : {}),
      ...(mints.length > 0 ? { mints } : {}),
    };
    create.mutate(
      { formKey: JSON.stringify(body), body },
      { onSuccess: (backfill) => void navigate(paths.automationBackfill(backfill.id)) },
    );
  };
  const budgetNumber = Number(values.budget) || 0;

  return (
    <form
      noValidate
      aria-label="New backfill"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <Stack gap="md">
        {prefill.ignored.length > 0 && (
          <Alert color="gray" variant="light" p="xs">
            <Text size="sm">
              The link&apos;s {prefill.ignored.map((key) => PREFILL_NAMES[key] ?? key).join(', ')}{' '}
              {prefill.ignored.length === 1 ? 'was' : 'were'} not valid, so the form uses its
              default instead.
            </Text>
          </Alert>
        )}
        <Group gap="sm" wrap="wrap" align="flex-start">
          <TextInput
            type="date"
            label="From day"
            value={values.fromDay}
            max={addDays(today, -1)}
            onChange={(e) => set('fromDay', e.currentTarget.value)}
            error={shown('fromDay')}
            w={DATE_WIDTH}
          />
          <TextInput
            type="date"
            label="To day"
            value={values.toDay}
            max={addDays(today, -1)}
            onChange={(e) => set('toDay', e.currentTarget.value)}
            error={shown('toDay')}
            w={DATE_WIDTH}
          />
        </Group>
        <Text size="xs" c="dimmed" mt={-8}>
          {days > 0
            ? `${days} trading ${days === 1 ? 'day' : 'days'}, ${dayInfo(values.fromDay).label} → ${dayInfo(values.toDay).label}, each ${daySpanText(status.zone, status.tradingDayStart)}.`
            : `Each day runs ${daySpanText(status.zone, status.tradingDayStart)}.`}{' '}
          {status.coveredFrom &&
            `Live discovery covers from ${zoneDateTime(status.coveredFrom, status.zone)}; a capture backfill is cut off there.`}
        </Text>
        <Checkbox
          checked={values.dryRun}
          onChange={(e) => setDryRun(e.currentTarget.checked)}
          label={
            <span>
              <strong>Dry run</strong> · count buys and estimate the requests a capture would need;
              nothing is captured
            </span>
          }
        />
        <TextInput
          label="Request budget"
          inputMode="numeric"
          value={values.budget}
          onChange={(e) => set('budget', e.currentTarget.value)}
          error={shown('budget')}
          description={`At least ${formatCount(values.dryRun ? BACKFILL_LIMITS.minDryRunBudget : BACKFILL_LIMITS.minCaptureBudget)} for a ${values.dryRun ? 'dry run' : 'capture'}, at most ${formatCount(BACKFILL_LIMITS.maxBudget)}. It counts inside the shared 24 h cap, and live capture keeps a reserve.`}
          maw={360}
          styles={MONO_INPUT}
        />
        <UnstyledButton
          onClick={() => setFiltersOpen((o) => !o)}
          aria-expanded={filtersOpen}
          style={{ alignSelf: 'flex-start' }}
        >
          <Text size="sm" fw={600}>
            {filtersOpen ? '▾' : '▸'} Filters (optional)
            {filtered && (
              <Text span size="sm" c="orange" fw={400}>
                {' '}
                · filtered
              </Text>
            )}
          </Text>
        </UnstyledButton>
        {filtersOpen && (
          <Stack gap="sm">
            <TextInput
              label="Minimum signal size, SOL, fees included"
              placeholder={`${BACKFILL_LIMITS.captureMinSol} (the capture filter)`}
              value={values.minSignalSol}
              onChange={(e) => set('minSignalSol', e.currentTarget.value)}
              error={shown('minSignalSol')}
              inputMode="decimal"
              maw={360}
              styles={MONO_INPUT}
            />
            <Textarea
              label={`Tokens, one mint per line (up to ${BACKFILL_LIMITS.maxMints})`}
              placeholder="Leave empty for every token"
              value={values.mintsText}
              onChange={(e) => set('mintsText', e.currentTarget.value)}
              error={(touched ? errors.mints : undefined) ?? serverErrors.mints}
              rows={4}
              ff="monospace"
              spellCheck={false}
            />
          </Stack>
        )}
        {filtered && (
          <Alert color="orange" variant="light" title="Filtered backfill">
            A token list, or a minimum above {BACKFILL_LIMITS.captureMinSol} SOL, makes this
            backfill filtered. It captures those buys, but its days do not count as covered and it
            does not count toward day verdicts.
          </Alert>
        )}
        {create.isError && (
          <Alert color="red" variant="light" title="The backfill was not started" role="alert">
            {isBackfillRefusal(create.error) ? (
              <BackfillRefusal error={create.error} activeBackfillId={status.activeBackfillId} />
            ) : create.error instanceof ApiError && Object.keys(create.error.fields).length > 0 ? (
              'See the highlighted fields.'
            ) : create.error instanceof ApiError ? (
              create.error.message
            ) : (
              'The backend could not be reached.'
            )}
          </Alert>
        )}
        <Stack gap={4} align="flex-start">
          <Button type="submit" loading={create.isPending}>
            {values.dryRun ? 'Start dry run' : 'Start capture backfill'}
          </Button>
          <Text size="xs" c="dimmed">
            {values.dryRun
              ? `Spends discovery requests only, up to ${formatCount(budgetNumber)}; nothing is captured.`
              : `Spends up to ${formatCount(budgetNumber)} requests.`}
          </Text>
        </Stack>
      </Stack>
    </form>
  );
}

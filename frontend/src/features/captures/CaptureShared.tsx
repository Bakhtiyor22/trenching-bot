import { Alert, Button, Group, NumberInput, SimpleGrid, Stack, Text } from '@mantine/core';
import { ApiError } from '../../api/client';
import type {
  CaptureBudget,
  CaptureConfig,
  CaptureOperation,
  CaptureSummaryStatus,
} from '../../api/captures';
import { CodeText } from '../../components/PlainLanguage';
import { ErrorState } from '../../components/StateViews';
import { StatusChip } from '../../components/StatusChip';
import { statusGlyphs, statusLabels, statusTones } from '../../lib/codes';
import { stageStateChip } from './captureLabels';
import { reasonText } from './captureReasons';
import { operationNotice } from './operationNotice';

const QUALIFICATION_ERRORS = new Set(['EXPORT_CHANGED', 'EXPORT_REQUIRED', 'UNSUPPORTED_SCOPE']);

/** A capture's overall status as a chip: glyph or dot, a word and the tone of what it means. */
export function CaptureStatusChip({ status }: { status: CaptureSummaryStatus }) {
  return (
    <StatusChip tone={statusTones[status]} glyph={statusGlyphs[status]}>
      {statusLabels[status]}
    </StatusChip>
  );
}

/** A stage, case or operation state as a chip; `label` replaces the default words. */
export function CaptureStatus({ state, label }: { state: string; label?: string }) {
  const chip = stageStateChip(state);
  return (
    <StatusChip tone={chip.tone} glyph={chip.glyph}>
      {label ?? chip.label}
    </StatusChip>
  );
}

export function CommandError({
  error,
  uncertain,
  retry,
}: {
  error: unknown;
  uncertain: boolean;
  retry: () => void;
}) {
  if (error == null && !uncertain) return null;
  const guidance =
    error instanceof ApiError && QUALIFICATION_ERRORS.has(error.code)
      ? reasonText(error.code)
      : null;
  return (
    <Stack gap="xs">
      {error != null && <ErrorState error={error} title="Action could not complete" />}
      {guidance && error instanceof ApiError && guidance !== error.message && (
        <Text size="sm" c="red">
          {guidance}
        </Text>
      )}
      {error instanceof ApiError && Object.keys(error.fields).length > 0 && (
        <Text size="sm" c="red">
          {Object.entries(error.fields)
            .map(([field, message]) => `${field}: ${message}`)
            .join(' · ')}
        </Text>
      )}
      {uncertain && (
        <Alert title="Request outcome is unknown" color="orange">
          <Stack gap="sm">
            <Text size="sm">
              Reconcile the original request before starting another action. Retrying the same
              request cannot start duplicate work.
            </Text>
            <Button variant="default" onClick={retry}>
              Reconcile and retry request
            </Button>
          </Stack>
        </Alert>
      )}
    </Stack>
  );
}

export function BudgetFields({
  value,
  onChange,
  config,
  market,
  disabled,
}: {
  value: CaptureBudget;
  onChange: (value: CaptureBudget) => void;
  config: CaptureConfig;
  market: boolean;
  disabled: boolean;
}) {
  const prefix = market ? 'market' : 'wallet';
  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }}>
      <NumberInput
        label="Request allowance"
        value={value.requestBudget}
        min={1}
        max={config.bounds[`${prefix}RequestsMax` as const]}
        allowDecimal={false}
        allowNegative={false}
        disabled={disabled}
        onChange={(v) => onChange({ ...value, requestBudget: Number(v) })}
      />
      <NumberInput
        label="Byte allowance"
        description="Bytes, per explicit operation"
        value={value.byteBudget}
        min={1}
        max={config.bounds[`${prefix}BytesMax` as const]}
        allowDecimal={false}
        allowNegative={false}
        disabled={disabled}
        onChange={(v) => onChange({ ...value, byteBudget: Number(v) })}
      />
      <NumberInput
        label="Operation deadline (minutes)"
        description="This run stops at the deadline; resume to continue"
        value={value.operationTimeoutMinutes}
        min={1}
        max={config.bounds.operationTimeoutMinutesMax}
        allowDecimal={false}
        allowNegative={false}
        disabled={disabled}
        onChange={(v) => onChange({ ...value, operationTimeoutMinutes: Number(v) })}
      />
    </SimpleGrid>
  );
}

/** Why an operation stopped, failed or waits, shown whenever it is not running or succeeded; nothing otherwise. */
export function OperationNotice({
  operation,
  stageReason,
}: {
  operation: Pick<CaptureOperation, 'state' | 'code' | 'message'>;
  stageReason?: string | null;
}) {
  const notice = operationNotice(operation, stageReason);
  if (!notice) return null;
  return (
    <Alert
      color={notice.color}
      title={notice.title}
      aria-label={`Operation ${operation.state.toLowerCase()}: ${notice.title}`}
    >
      <Stack gap={4}>
        <Text size="sm">{notice.detail}</Text>
        <CodeText code={notice.code} />
      </Stack>
    </Alert>
  );
}

/** The live cue beside a region that polls: a pulsing dot and a word, shown only while it polls. */
export function LiveCue({ active }: { active: boolean }) {
  if (!active) return null;
  return (
    <Group gap={6} wrap="nowrap">
      <span
        className="console-live-dot"
        style={{ background: 'var(--console-gain)' }}
        aria-hidden
      />
      <Text size="xs" c="dimmed">
        Updating live
      </Text>
    </Group>
  );
}

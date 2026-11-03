import { Anchor, Badge, Box, Group, Stack, Text } from '@mantine/core';
import { Link } from 'react-router';
import type { AutomationBackfill, BackfillState } from '../../api/automation';
import { ApiError } from '../../api/client';
import { paths } from '../../paths';
import { backfillStateText, REFUSAL_TEXT, scanFraction, zoneDateTime } from './automationModel';

const STATE_STYLE: Record<
  BackfillState,
  { color: string; glyph: string; variant: 'light' | 'outline' }
> = {
  QUEUED: { color: 'gray', glyph: '…', variant: 'outline' },
  SCANNING: { color: 'blue', glyph: '●', variant: 'light' },
  CAPTURING: { color: 'amber', glyph: '●', variant: 'light' },
  DONE: { color: 'blue', glyph: '✓', variant: 'light' },
  STOPPED_BUDGET: { color: 'red', glyph: '■', variant: 'light' },
  CANCELLED: { color: 'gray', glyph: '✕', variant: 'light' },
};

/** A backfill's state with its glyph and word. */
export function BackfillStateChip({ state }: { state: BackfillState }) {
  const style = STATE_STYLE[state];
  return (
    <Badge
      color={style.color}
      variant={style.variant}
      radius="xl"
      tt="none"
      leftSection={<span aria-hidden="true">{style.glyph}</span>}
      style={{ whiteSpace: 'nowrap' }}
    >
      {backfillStateText[state]}
    </Badge>
  );
}

function Bar({
  parts,
  label,
  max,
}: {
  parts: Array<{ value: number; color: string }>;
  label: string;
  max: number;
}) {
  const total = parts.reduce((sum, p) => sum + p.value, 0);
  return (
    <Group
      gap={2}
      wrap="nowrap"
      h={8}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={Math.min(total, max)}
      style={{
        borderRadius: 4,
        overflow: 'hidden',
        background: 'var(--mantine-color-default-border)',
      }}
    >
      {parts
        .filter((p) => p.value > 0)
        .map((p, i) => (
          <Box
            key={i}
            h="100%"
            style={{
              width: `${Math.min(100, (p.value / Math.max(1, max)) * 100)}%`,
              background: p.color,
            }}
          />
        ))}
    </Group>
  );
}

/** How far back the newest-first scan has read, as a bar and in words. */
export function ScanProgress({ backfill, zone }: { backfill: AutomationBackfill; zone: string }) {
  const fraction = scanFraction(backfill);
  const done = fraction >= 1 || backfill.state === 'DONE';
  return (
    <Stack gap={6}>
      <Bar
        parts={[{ value: Math.round(fraction * 100), color: 'var(--automation-replayed)' }]}
        label="Scan progress, percent"
        max={100}
      />
      <Text size="sm">
        {backfill.scannedTo == null ? (
          backfill.state === 'QUEUED' ? (
            'Waiting for the next scheduler tick'
          ) : (
            'Not started'
          )
        ) : (
          <>
            Read back to <strong>{zoneDateTime(backfill.scannedTo, zone)}</strong>
            {done ? ' · finished' : ` · ${Math.round(fraction * 100)}%`}
          </>
        )}
      </Text>
    </Stack>
  );
}

/** Requests spent against the budget, split into discovery and captures. */
export function BudgetMeter({ backfill }: { backfill: AutomationBackfill }) {
  return (
    <Stack gap={6}>
      <Bar
        parts={[
          { value: backfill.discoveryRequests, color: 'var(--automation-skipped)' },
          { value: backfill.itemRequests, color: 'var(--automation-replayed)' },
        ]}
        label="Requests spent of the budget"
        max={backfill.requestBudget}
      />
      <Text size="sm" ff="monospace">
        {backfill.requestsSpent.toLocaleString('en-US')} /{' '}
        {backfill.requestBudget.toLocaleString('en-US')}
        <Text span size="sm" c="dimmed" ff="inherit">
          {' '}
          · {backfill.discoveryRequests.toLocaleString('en-US')} discovery +{' '}
          {backfill.itemRequests.toLocaleString('en-US')} captures
        </Text>
      </Text>
    </Stack>
  );
}

/** A plain explanation of a backfill or run-now refusal; render it only when {@link isBackfillRefusal} holds. */
export function BackfillRefusal({
  error,
  activeBackfillId,
}: {
  error: unknown;
  activeBackfillId?: string | null;
}) {
  const code = error instanceof ApiError ? error.code : null;
  if (code === 'BACKFILL_ACTIVE')
    return (
      <>
        Another backfill is still running for this wallet, and only one can run at a time. Wait for
        it to finish, or cancel it
        {activeBackfillId && (
          <>
            :{' '}
            <Anchor component={Link} to={paths.automationBackfill(activeBackfillId)}>
              open the running backfill
            </Anchor>
          </>
        )}
        .
      </>
    );
  return <>{code ? (REFUSAL_TEXT[code] ?? null) : null}</>;
}

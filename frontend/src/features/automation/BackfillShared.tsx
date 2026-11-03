import { Anchor, Stack, Text } from '@mantine/core';
import { Link } from 'react-router';
import type { AutomationBackfill, BackfillState } from '../../api/automation';
import { ApiError } from '../../api/client';
import { Meter } from '../../components/Meter';
import { StatusChip, type StatusTone } from '../../components/StatusChip';
import { formatCount } from '../../lib/format';
import { paths } from '../../paths';
import { backfillStateText, REFUSAL_TEXT, scanFraction, zoneDateTime } from './automationModel';

const STATE_CHIP: Record<BackfillState, { tone: StatusTone; glyph?: string }> = {
  QUEUED: { tone: 'pending', glyph: '◷' },
  SCANNING: { tone: 'running' },
  CAPTURING: { tone: 'running' },
  DONE: { tone: 'success', glyph: '✓' },
  STOPPED_BUDGET: { tone: 'attention', glyph: '▲' },
  CANCELLED: { tone: 'neutral', glyph: '–' },
};

/** A backfill's state with its glyph (or running dot) and word. */
export function BackfillStateChip({ state }: { state: BackfillState }) {
  const chip = STATE_CHIP[state];
  return (
    <StatusChip tone={chip.tone} glyph={chip.glyph}>
      {backfillStateText[state]}
    </StatusChip>
  );
}

/** How far back the newest-first scan has read, as a meter and in words. */
export function ScanProgress({ backfill, zone }: { backfill: AutomationBackfill; zone: string }) {
  const fraction = scanFraction(backfill);
  const done = fraction >= 1 || backfill.state === 'DONE';
  const percent = Math.round(fraction * 100);
  return (
    <Stack gap={6}>
      <Meter label="Scan progress, percent" value={percent} max={100} />
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
            {done ? ' · finished' : ` · ${percent}%`}
          </>
        )}
      </Text>
    </Stack>
  );
}

/** Requests spent against the budget, with the split into discovery and captures in words. */
export function BudgetMeter({ backfill }: { backfill: AutomationBackfill }) {
  return (
    <Stack gap={6}>
      <Meter
        label="Requests spent of the budget"
        value={backfill.requestsSpent}
        max={backfill.requestBudget}
      />
      <Text size="sm" ff="monospace">
        {formatCount(backfill.requestsSpent)} / {formatCount(backfill.requestBudget)}
        <Text span size="sm" c="dimmed" ff="inherit">
          {' '}
          · {formatCount(backfill.discoveryRequests)} discovery +{' '}
          {formatCount(backfill.itemRequests)} captures
        </Text>
      </Text>
    </Stack>
  );
}

/** A plain explanation of a backfill or run-now refusal; render it only when `isBackfillRefusal` holds. */
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

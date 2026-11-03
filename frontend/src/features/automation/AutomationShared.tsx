import { Alert, Anchor, Box, Button, Code, Group, List, Stack, Text, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import {
  isAutomationOff,
  useAutomationStatus,
  useRunDiscovery,
  type AutomationDaySummary,
  type AutomationSession,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { ShortId } from '../../components/DisplayValues';
import { Meter, MeterLegend } from '../../components/Meter';
import { PageHeader, SubNav, type Crumb } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { RefreshNotice, type QueryLike } from '../../components/QueryView';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { StatusChip, type StatusTone } from '../../components/StatusChip';
import { formatSignedDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import {
  automationMode,
  BUY_BUCKETS,
  bucketLabel,
  buyBuckets,
  dayInfo,
  isBackfillRefusal,
  outcomeExplanation,
  outcomeLabel,
  pendingText,
  pnlBar,
  shortSol,
  statusGlyph,
  statusText,
  variationChange,
  zoneDateTime,
  zoneLabel,
  zoneTime,
  type AutomationMode,
  type BuyBucket,
} from './automationModel';
import { BackfillRefusal } from './BackfillShared';

const EVALUATION_TONE: Record<EvaluationStatus, StatusTone> = {
  SUCCESS: 'success',
  NO_SUCCESS: 'failure',
  NO_RESULT: 'neutral',
  PENDING: 'pending',
};

/**
 * A verdict with its glyph and word, never colour alone. PENDING is a state, not a verdict: it shows a clock, an
 * outline instead of a fill, and says which day it waits for.
 */
export function EvaluationChip({
  status,
  pendingDay,
}: {
  status: EvaluationStatus;
  pendingDay?: string | null;
}) {
  const chip = (
    <StatusChip tone={EVALUATION_TONE[status]} glyph={statusGlyph[status]}>
      {statusText[status]}
    </StatusChip>
  );
  if (status !== 'PENDING') return chip;
  const why = pendingText(pendingDay);
  return (
    <Tooltip label={why}>
      <span aria-label={`Pending, ${why}`} style={{ display: 'inline-flex' }}>
        {chip}
      </span>
    </Tooltip>
  );
}

/** A compact signed SOL value with the exact signed decimal in a tooltip, coloured by sign. */
export function SolValue({
  value,
  places = 4,
  strong = false,
}: {
  value: string;
  places?: number;
  strong?: boolean;
}) {
  const short = shortSol(value, places);
  const color = short.startsWith('+') ? 'teal' : short.startsWith('−') ? 'red' : undefined;
  return (
    <Tooltip label={`${formatSignedDecimal(value)} SOL`}>
      <Text
        span
        ff="monospace"
        size="sm"
        fw={strong ? 600 : 400}
        c={color}
        style={{ whiteSpace: 'nowrap' }}
      >
        {short}
      </Text>
    </Tooltip>
  );
}

/** Worst case as a bar from the 0 SOL midline, realized as a tick, on a scale shared by every row. */
export function PnlBarCell({
  worst,
  realized,
  scale,
  faded = false,
  label,
}: {
  worst: string;
  realized: string;
  scale: number;
  faded?: boolean;
  label: string;
}) {
  const bar = pnlBar(worst, realized, scale);
  return (
    <Box
      role="img"
      aria-label={`${label}: worst case ${formatSignedDecimal(worst)} SOL, realized ${formatSignedDecimal(realized)} SOL`}
      pos="relative"
      h={18}
      miw={140}
    >
      <Box pos="absolute" top={-4} bottom={-4} left="50%" w={1} bg="var(--console-line-strong)" />
      {bar.width > 0 && (
        <Box
          pos="absolute"
          top={3}
          h={12}
          style={{
            left: `${bar.left}%`,
            width: `${bar.width}%`,
            background: bar.negative ? 'var(--console-loss)' : 'var(--console-gain)',
            borderRadius: bar.negative ? '4px 0 0 4px' : '0 4px 4px 0',
            opacity: faded ? 0.4 : 1,
          }}
        />
      )}
      <Box
        pos="absolute"
        top={-1}
        h={20}
        w={2}
        bg="var(--console-text)"
        style={{ left: `calc(${bar.tick}% - 1px)` }}
      />
    </Box>
  );
}

/** The bar's key, shown once above the scoreboard. */
export function PnlBarLegend() {
  return (
    <Group gap="md" wrap="wrap">
      <Group gap={6} wrap="nowrap">
        <Box w={12} h={8} bg="var(--console-gain)" style={{ borderRadius: 2 }} />
        <Box w={12} h={8} bg="var(--console-loss)" style={{ borderRadius: 2 }} />
        <Text size="xs" c="dimmed">
          Worst case (bar)
        </Text>
      </Group>
      <Group gap={6} wrap="nowrap">
        <Box w={2} h={14} bg="var(--console-text)" />
        <Text size="xs" c="dimmed">
          Realized (tick)
        </Text>
      </Group>
    </Group>
  );
}

/** Signals needed in a 7-day window for a result. */
export const SIGNALS_NEEDED = 10;

/** Signals seen against the number a result needs. */
export function SignalsMeter({
  signals,
  needed = SIGNALS_NEEDED,
}: {
  signals: number;
  needed?: number;
}) {
  return (
    <Group gap={8} wrap="nowrap" miw={110}>
      <Box style={{ flex: 1 }}>
        <Meter
          label={`${signals} of ${needed} signals needed`}
          value={Math.min(signals, needed)}
          max={needed}
          color={signals >= needed ? 'var(--console-info)' : 'var(--console-neutral)'}
        />
      </Box>
      <Text span ff="monospace" size="sm" w={28} ta="right">
        {signals}
      </Text>
    </Group>
  );
}

const BUCKET_COLOR: Record<BuyBucket, string> = {
  replayed: 'var(--automation-replayed)',
  inProgress: 'var(--automation-progress)',
  skipped: 'var(--automation-skipped)',
  issues: 'var(--automation-issues)',
};
/** Segment order keeps red and amber apart (see styles.css). */
const SEGMENT_ORDER: BuyBucket[] = ['replayed', 'inProgress', 'skipped', 'issues'];

/** A day's or session's buys as a segmented meter, and its counts in words. */
export function BuyStateBar({
  counts,
  captureEnabled = true,
}: {
  counts: AutomationSession['itemCounts'];
  captureEnabled?: boolean;
}) {
  const buckets = buyBuckets(counts);
  const total = BUY_BUCKETS.reduce((sum, b) => sum + buckets[b], 0);
  const shown = SEGMENT_ORDER.filter((b) => buckets[b] > 0);
  const words = shown.map((b) => `${buckets[b]} ${bucketLabel(b, captureEnabled).toLowerCase()}`);
  return (
    <Stack gap={4} miw={160}>
      {total > 0 && (
        <Meter
          label="Buy states"
          segments={shown.map((b) => ({
            value: buckets[b],
            color: BUCKET_COLOR[b],
            label: bucketLabel(b, captureEnabled).toLowerCase(),
          }))}
        />
      )}
      <Text size="xs" c="dimmed">
        {total} {total === 1 ? 'buy' : 'buys'}
        {words.length > 0 && ` · ${words.join(' · ')}`}
      </Text>
    </Stack>
  );
}

export function BuyStateLegend({ captureEnabled = true }: { captureEnabled?: boolean }) {
  return (
    <MeterLegend
      segments={SEGMENT_ORDER.map((b) => ({
        value: 0,
        color: BUCKET_COLOR[b],
        label: b,
        text: bucketLabel(b, captureEnabled),
      }))}
    />
  );
}

/** A time on the automation's clock, with the zone and the UTC instant in a tooltip. */
export function ZoneTime({
  iso,
  zone,
  withDate = false,
  seconds = false,
}: {
  iso: string | null | undefined;
  zone: string;
  withDate?: boolean;
  seconds?: boolean;
}) {
  if (!iso)
    return (
      <Text span size="sm" c="dimmed">
        —
      </Text>
    );
  return (
    <Tooltip label={`${iso} (UTC)`}>
      <Text span size="sm" ff="monospace" style={{ whiteSpace: 'nowrap' }}>
        <time dateTime={iso}>
          {withDate ? zoneDateTime(iso, zone) : zoneTime(iso, zone, seconds)}
        </time>
      </Text>
    </Tooltip>
  );
}

/** A sweep run's outcome in words, with its explanation and the stored code in a tooltip. */
export function OutcomeText({ outcome }: { outcome: string }) {
  return (
    <Tooltip
      multiline
      w={320}
      label={
        <Stack gap={2}>
          <Text size="xs">{outcomeExplanation(outcome) ?? outcomeLabel(outcome)}</Text>
          <CodeText code={outcome} />
        </Stack>
      }
    >
      <Text span size="sm">
        {outcomeLabel(outcome)}
      </Text>
    </Tooltip>
  );
}

/** A run id as a link to the run, shortened, with the full id in a tooltip. */
export function RunLink({ runId }: { runId: string | null }) {
  if (!runId)
    return (
      <Text size="sm" c="dimmed">
        no run
      </Text>
    );
  return (
    <Anchor component={Link} to={paths.run(runId)} size="sm" aria-label={`Open run ${runId}`}>
      <ShortId value={runId} copy={false} c="inherit" />
    </Anchor>
  );
}

/** What to set on the backend to turn automation on. Names only; no value is read or shown. */
export function AutomationOffState() {
  return (
    <EmptyState
      title="Automation is off"
      description={
        <Stack gap="xs" align="center">
          <span>
            The backend answers no automation endpoints, so automation or the console is switched
            off. Turn it on in the backend's local configuration and restart it:
          </span>
          <List size="sm" ta="left" spacing={4}>
            <List.Item>
              <Code>trenching.automation.enabled=true</Code> to find Decu's buys every trading day
            </List.Item>
            <List.Item>
              <Code>trenching.automation.capture-enabled=true</Code> to also capture, replay and
              score them (off = discovery only)
            </List.Item>
          </List>
        </Stack>
      }
    />
  );
}

const MODE: Record<AutomationMode, { text: string; tone: StatusTone }> = {
  OFF: { text: 'Off', tone: 'neutral' },
  DISCOVERY_ONLY: { text: 'Discovery only', tone: 'neutral' },
  CAPTURING: { text: 'Capturing', tone: 'running' },
};

/** The automation mode as a chip: Capturing runs (a dot), Discovery only and Off are neutral. */
export function ModeChip({ status }: { status: AutomationStatus }) {
  const mode = MODE[automationMode(status)];
  return <StatusChip tone={mode.tone}>{mode.text}</StatusChip>;
}

/** "Run discovery now": queue one live discovery pass, whatever the mode or window. */
export function RunDiscoveryNow({
  status,
  primary = true,
}: {
  status: AutomationStatus;
  /** The page's one filled button; secondary where the page has its own primary (the backfill form). */
  primary?: boolean;
}) {
  const run = useRunDiscovery();
  const queued = status.manualRunQueued || (run.isPending && !run.isError);
  return (
    <Stack gap={6} align="flex-end" maw={360}>
      <Tooltip label="Scan Decu's wallet for new buys now; capture follows on the next minute's tick">
        <Button
          variant={primary ? 'filled' : 'default'}
          onClick={() => run.mutate()}
          loading={run.isPending}
          disabled={!status.enabled || status.manualRunQueued}
        >
          {queued ? 'Queued…' : 'Run discovery now'}
        </Button>
      </Tooltip>
      {run.isError && (
        <Alert color="red" variant="light" p="xs" role="alert" title="Discovery was not queued">
          <Text size="xs">
            {isBackfillRefusal(run.error) ? (
              <BackfillRefusal error={run.error} />
            ) : run.error instanceof ApiError ? (
              run.error.message
            ) : (
              'The backend could not be reached.'
            )}
          </Text>
        </Alert>
      )}
      {run.data?.alreadyQueued && !status.manualRunQueued && (
        <Text size="xs" c="dimmed">
          A run was already queued.
        </Text>
      )}
    </Stack>
  );
}

const TABS = [
  { to: paths.automation, label: 'Overview', end: true },
  { to: paths.automationDays, label: 'Days' },
  { to: paths.automationBackfills, label: 'Backfills' },
  { to: paths.automationSweepRuns, label: 'Sweep runs' },
];

/** A header slot that may need the status (the zone, today); it renders only once the status is known. */
export type FrameSlot = ReactNode | ((status: AutomationStatus) => ReactNode);

function slot(value: FrameSlot, status: AutomationStatus | undefined): ReactNode {
  if (typeof value !== 'function') return value;
  return status ? value(status) : null;
}

const DEFAULT_DESCRIPTION =
  "Decu's buys are found, captured and replayed under 10 strategy variations. Each variation is scored over the last 7 trading days; nothing is adopted automatically.";

/**
 * The Automation section's frame: the one page header (breadcrumb, title, mode chip, "Times in UTC+5", actions, the
 * four URL-driven tabs) and the status gate. Its children render once `/status` answered, and get the status (for the
 * zone and the plan); a 404 shows how to turn automation on. A failed background refresh of the status keeps the page
 * (and any open dialog) on screen with a notice instead of replacing it.
 */
export function AutomationFrame({
  title = 'Automation',
  description = DEFAULT_DESCRIPTION,
  breadcrumbs,
  status: statusSlot,
  actions,
  children,
}: {
  title?: ReactNode;
  description?: FrameSlot;
  breadcrumbs?: Crumb[];
  /** The title's chip; the automation mode by default. */
  status?: FrameSlot;
  /** Header actions; "Run discovery now" by default. Pass `null` for none. */
  actions?: FrameSlot;
  children: (status: AutomationStatus) => ReactNode;
}) {
  const query = useAutomationStatus();
  const data = query.data;
  return (
    <>
      <PageHeader
        title={title}
        breadcrumbs={breadcrumbs}
        description={slot(description, data)}
        status={
          statusSlot === undefined ? (
            data ? (
              <ModeChip status={data} />
            ) : null
          ) : (
            slot(statusSlot, data)
          )
        }
        meta={data ? `Times in ${zoneLabel(data.zone)}` : undefined}
        actions={
          actions === undefined ? (
            data ? (
              <RunDiscoveryNow status={data} />
            ) : null
          ) : (
            slot(actions, data)
          )
        }
        tabs={<SubNav label="Automation views" items={TABS} />}
      />
      {data === undefined ? (
        query.isError ? (
          isAutomationOff(query.error) ? (
            <AutomationOffState />
          ) : (
            <ErrorState
              error={query.error}
              title="Automation status unavailable"
              onRetry={() => void query.refetch()}
            />
          )
        ) : (
          <LoadingState label="Loading automation status…" />
        )
      ) : (
        <>
          {query.isError && (
            <RefreshNotice
              error={query.error}
              updatedAt={query.dataUpdatedAt}
              onRetry={() => void query.refetch()}
            />
          )}
          {children(data)}
        </>
      )}
    </>
  );
}

/**
 * Several queries a view needs together: a loader until each has data, the full error only for one that never loaded,
 * and otherwise the view with one {@link RefreshNotice} for a failed background refresh.
 */
export function QueryGate({
  queries,
  loadingLabel,
  errorTitle,
  children,
}: {
  queries: Array<QueryLike<unknown>>;
  loadingLabel?: string;
  errorTitle?: string;
  children: () => ReactNode;
}) {
  const missing = queries.find((q) => q.data === undefined && q.isError);
  if (missing)
    return (
      <ErrorState error={missing.error} title={errorTitle} onRetry={() => void missing.refetch()} />
    );
  if (queries.some((q) => q.data === undefined)) return <LoadingState label={loadingLabel} />;
  const failed = queries.find((q) => q.isError);
  return (
    <>
      {failed && (
        <RefreshNotice
          error={failed.error}
          updatedAt={Math.min(...queries.map((q) => q.dataUpdatedAt))}
          onRetry={() => queries.forEach((q) => void q.refetch())}
        />
      )}
      {children()}
    </>
  );
}

/** Problems the operator should see before anything else. */
export function StatusAlerts({ status }: { status: AutomationStatus }) {
  return (
    <>
      {!status.rpcConfigured && (
        <Alert color="red" variant="light" title="No Solana RPC configured">
          Discovery cannot read Decu's buys. Configure <Code>SOLANA_RPC_URL</Code> in the backend
          process.
        </Alert>
      )}
      {status.discoveryProblem && (
        <Alert color="red" variant="light" title="Discovery problem">
          {status.discoveryProblem}
        </Alert>
      )}
      {status.enabled && !status.captureEnabled && (
        <Alert color="gray" variant="light" title="Discovery only">
          Buys are found and grouped into trading days, but nothing is captured, replayed or scored,
          and no capture requests are spent, so every day stays Pending. Turn on{' '}
          <Code>trenching.automation.capture-enabled</Code> once the daily volume is known; the days
          found so far are then captured and complete.
        </Alert>
      )}
      {status.watermark?.resumeBefore && (
        <Alert color="gray" variant="light" title="Catching up">
          Discovery is working through more buys than one scan reads; the next checks continue from
          where this one stopped.
        </Alert>
      )}
    </>
  );
}

/** "UTC+5" next to a heading, so every time on the page is read on the right clock. */
export function ZoneNote({ zone }: { zone: string }) {
  return (
    <Text span size="xs" c="dimmed">
      Times in {zoneLabel(zone)}
    </Text>
  );
}

/** A link to a trading day, named by its date; `itemId` opens that buy in it. */
export function DayLink({
  day,
  children,
  itemId,
}: {
  day: string;
  children?: ReactNode;
  itemId?: string | null;
}) {
  return (
    <Anchor component={Link} to={paths.automationDay(day, itemId)} fw={600} size="sm">
      {children ?? dayInfo(day).label}
    </Anchor>
  );
}

/** A day's best verdict and variation, or Pending with the day it waits for. */
export function BestOfDay({ day, planId }: { day: AutomationDaySummary; planId: string }) {
  if (!day.bestStatus)
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  if (day.bestStatus === 'PENDING')
    return (
      <Group gap={6} wrap="wrap">
        <EvaluationChip status="PENDING" pendingDay={day.pendingDay} />
        <Text span size="xs" c="dimmed" visibleFrom="md">
          {pendingText(day.pendingDay)}
        </Text>
      </Group>
    );
  return (
    <Group gap={6} wrap="nowrap">
      <EvaluationChip status={day.bestStatus} />
      <Text span ff="monospace" size="sm" fw={600}>
        {day.bestLabel}
      </Text>
      <Text span size="xs" c="dimmed" visibleFrom="md">
        {day.bestLabel ? variationChange(planId, day.bestLabel) : null}
      </Text>
    </Group>
  );
}

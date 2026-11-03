import {
  Alert,
  Anchor,
  Badge,
  Box,
  Code,
  Group,
  List,
  Stack,
  Tabs,
  Text,
  Tooltip,
} from '@mantine/core';
import type { ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import {
  isAutomationOff,
  useAutomationStatus,
  type AutomationSession,
  type AutomationStatus,
  type EvaluationStatus,
} from '../../api/automation';
import { PageHeader } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import {
  BUY_BUCKETS,
  bucketLabel,
  buyBuckets,
  pnlBar,
  shortSol,
  statusGlyph,
  statusText,
  zoneDateTime,
  zoneLabel,
  zoneTime,
  type BuyBucket,
} from './automationModel';

const STATUS_COLOR: Record<EvaluationStatus, string> = {
  SUCCESS: 'teal',
  NO_SUCCESS: 'red',
  NO_RESULT: 'gray',
};

/** A status with its icon and word, never colour alone. */
export function StatusChip({ status, suffix }: { status: EvaluationStatus; suffix?: ReactNode }) {
  return (
    <Badge
      color={STATUS_COLOR[status]}
      variant="light"
      radius="xl"
      tt="none"
      leftSection={<span aria-hidden="true">{statusGlyph[status]}</span>}
      style={{ whiteSpace: 'nowrap' }}
    >
      {statusText[status]}
      {suffix}
    </Badge>
  );
}

/** A compact signed SOL value with the exact decimal in a tooltip, coloured by sign. */
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
    <Tooltip label={`${formatDecimal(value)} SOL`}>
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
      aria-label={`${label}: worst case ${formatDecimal(worst)} SOL, realized ${formatDecimal(realized)} SOL`}
      pos="relative"
      h={18}
      miw={140}
    >
      <Box
        pos="absolute"
        top={-4}
        bottom={-4}
        left="50%"
        w={1}
        bg="var(--mantine-color-default-border)"
      />
      {bar.width > 0 && (
        <Box
          pos="absolute"
          top={3}
          h={12}
          style={{
            left: `${bar.left}%`,
            width: `${bar.width}%`,
            background: bar.negative ? 'var(--mantine-color-red-6)' : 'var(--mantine-color-teal-6)',
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
        bg="var(--mantine-color-text)"
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
        <Box w={12} h={8} bg="var(--mantine-color-teal-6)" style={{ borderRadius: 2 }} />
        <Box w={12} h={8} bg="var(--mantine-color-red-6)" style={{ borderRadius: 2 }} />
        <Text size="xs" c="dimmed">
          Worst case (bar)
        </Text>
      </Group>
      <Group gap={6} wrap="nowrap">
        <Box w={2} h={14} bg="var(--mantine-color-text)" />
        <Text size="xs" c="dimmed">
          Realized (tick)
        </Text>
      </Group>
    </Group>
  );
}

/** Signals seen against the number a result needs. */
export function SignalsMeter({ signals, needed = 10 }: { signals: number; needed?: number }) {
  const enough = signals >= needed;
  return (
    <Group gap={8} wrap="nowrap" miw={110}>
      <Box
        role="meter"
        aria-label={`${signals} of ${needed} signals needed`}
        aria-valuemin={0}
        aria-valuemax={needed}
        aria-valuenow={Math.min(signals, needed)}
        h={6}
        style={{
          flex: 1,
          borderRadius: 3,
          overflow: 'hidden',
          background: 'var(--mantine-color-default-border)',
        }}
      >
        <Box
          h="100%"
          style={{
            width: `${Math.min(100, (signals / needed) * 100)}%`,
            background: enough ? 'var(--automation-replayed)' : 'var(--automation-skipped)',
          }}
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

/** A night's buys as a stacked bar with 2 px gaps, and its counts in words. */
export function BuyStateBar({
  counts,
  captureEnabled = true,
}: {
  counts: AutomationSession['itemCounts'];
  captureEnabled?: boolean;
}) {
  const buckets = buyBuckets(counts);
  const total = BUY_BUCKETS.reduce((sum, b) => sum + buckets[b], 0);
  const words = SEGMENT_ORDER.filter((b) => buckets[b] > 0).map(
    (b) => `${buckets[b]} ${bucketLabel(b, captureEnabled).toLowerCase()}`,
  );
  return (
    <Stack gap={4} miw={160}>
      {total > 0 && (
        <Group
          gap={2}
          wrap="nowrap"
          h={10}
          aria-hidden="true"
          style={{ borderRadius: 3, overflow: 'hidden' }}
        >
          {SEGMENT_ORDER.filter((b) => buckets[b] > 0).map((b) => (
            <Box
              key={b}
              h="100%"
              miw={6}
              style={{ flex: `${buckets[b]} 1 0`, background: BUCKET_COLOR[b] }}
            />
          ))}
        </Group>
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
    <Group gap="md" wrap="wrap">
      {SEGMENT_ORDER.map((b) => (
        <Group key={b} gap={6} wrap="nowrap">
          <Box w={10} h={10} style={{ borderRadius: 2, background: BUCKET_COLOR[b] }} />
          <Text size="xs" c="dimmed">
            {bucketLabel(b, captureEnabled)}
          </Text>
        </Group>
      ))}
    </Group>
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
              <Code>trenching.automation.enabled=true</Code> to find Decu's buys every night
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

const TABS = [
  { value: paths.automation, label: 'Overview' },
  { value: paths.automationNights, label: 'Nights' },
  { value: paths.automationSweepRuns, label: 'Sweep runs' },
];

function activeTab(pathname: string): string {
  if (pathname.startsWith(paths.automationSweepRuns)) return paths.automationSweepRuns;
  if (pathname.startsWith(paths.automationNights)) return paths.automationNights;
  return paths.automation;
}

/**
 * The Automation section's frame: title, the three URL-driven tabs and the status gate. Its children render only once
 * `/status` answered, and get the status (for the zone and the plan); a 404 shows how to turn automation on.
 */
export function AutomationFrame({
  title = 'Automation',
  description = "Decu's buys are found every night, captured, and replayed under 10 strategy variations. Each variation is scored over the last 7 nights; nothing is adopted automatically.",
  breadcrumb,
  actions,
  children,
}: {
  title?: ReactNode;
  description?: ReactNode;
  breadcrumb?: ReactNode;
  actions?: ReactNode;
  children: (status: AutomationStatus) => ReactNode;
}) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const status = useAutomationStatus();
  return (
    <Stack gap="lg">
      {breadcrumb}
      <PageHeader
        title={title}
        description={description}
        actions={
          <Group gap="sm" wrap="wrap">
            {actions}
            <Tabs
              value={activeTab(pathname)}
              onChange={(v) => v && navigate(v)}
              variant="pills"
              radius="sm"
            >
              <Tabs.List>
                {TABS.map((tab) => (
                  <Tabs.Tab key={tab.value} value={tab.value}>
                    {tab.label}
                  </Tabs.Tab>
                ))}
              </Tabs.List>
            </Tabs>
          </Group>
        }
      />
      {status.isPending ? (
        <LoadingState label="Loading automation status…" />
      ) : status.isError ? (
        isAutomationOff(status.error) ? (
          <AutomationOffState />
        ) : (
          <ErrorState
            error={status.error}
            title="Automation status unavailable"
            onRetry={() => void status.refetch()}
          />
        )
      ) : (
        children(status.data)
      )}
    </Stack>
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
        <Alert color="blue" variant="light" title="Discovery only">
          Buys are found and grouped into nights, but nothing is captured, replayed or scored, and
          no capture requests are spent. Turn on <Code>trenching.automation.capture-enabled</Code>{' '}
          once the nightly volume is known.
        </Alert>
      )}
      {status.watermark?.resumeBefore && (
        <Alert color="amber" variant="light" title="Catching up">
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

/** A link to a night, named by its night. */
export function NightLink({
  id,
  children,
  itemId,
}: {
  id: string;
  children: ReactNode;
  itemId?: string | null;
}) {
  return (
    <Anchor component={Link} to={paths.automationNight(id, itemId)} fw={600} size="sm">
      {children}
    </Anchor>
  );
}

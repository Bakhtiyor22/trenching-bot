import { Badge, Card, Group, SimpleGrid, Stack, Text, ThemeIcon, Tooltip } from '@mantine/core';
import { useEffect, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { DASHBOARD_POLL_MS, useDashboard, useSolanaStatus } from '../../api/queries';
import type { Dashboard, Gauges, SolanaStatus } from '../../api/types';
import { Chart } from '../../components/Chart';
import { Mint, Pnl, Timestamp } from '../../components/DisplayValues';
import { IconCheck, IconInfo, IconX } from '../../components/Icons';
import { PageHeader, Section, Stat } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { formatBytes, formatCount, formatDuration, formatPercent } from '../../lib/format';
import { paths } from '../../paths';
import { outcomeColor, outcomeLabel } from '../solana/solanaOutcome';
import {
  rejectionsOption,
  recentPnlOption,
  runIdFromClick,
  sparklineOption,
} from './dashboardCharts';
import { GAUGE_HISTORY_MS, gaugeHistory, useGaugeHistory, type GaugeSample } from './gaugeHistory';

type TileTone = 'ok' | 'warn' | 'bad' | 'neutral';

const TONE_COLOR: Record<TileTone, string> = { ok: 'teal', warn: 'yellow', bad: 'red', neutral: 'gray' };

function HealthTile({
  title,
  status,
  tone,
  detail,
}: {
  title: string;
  status: string;
  tone: TileTone;
  detail?: ReactNode;
}) {
  const icon = tone === 'ok' ? <IconCheck /> : tone === 'neutral' ? <IconInfo /> : <IconX />;
  return (
    <Card withBorder radius="md" padding="md" aria-label={`${title} health: ${status}`}>
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <Stack gap={4}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
            {title}
          </Text>
          <Text fw={700} size="lg" c={tone === 'neutral' ? undefined : TONE_COLOR[tone]}>
            {status}
          </Text>
          {detail && (
            <Text size="xs" c="dimmed">
              {detail}
            </Text>
          )}
        </Stack>
        <ThemeIcon variant="light" color={TONE_COLOR[tone]} radius="xl" size="lg">
          {icon}
        </ThemeIcon>
      </Group>
    </Card>
  );
}

function statusTone(status: string | null | undefined): TileTone {
  if (!status) return 'neutral';
  if (status === 'UP') return 'ok';
  if (status === 'UNKNOWN' || status === 'OUT_OF_SERVICE') return 'warn';
  return 'bad';
}

/** The Solana tile is neutral, never red, when no RPC is configured. */
function solanaTile(status: SolanaStatus | undefined, error: boolean) {
  if (error) return { status: 'Unknown', tone: 'warn' as TileTone, detail: 'Status endpoint failed' };
  if (!status) return { status: 'Checking…', tone: 'neutral' as TileTone };
  if (!status.configured) {
    return { status: 'Not configured', tone: 'neutral' as TileTone, detail: 'Set SOLANA_RPC_URL to enable' };
  }
  const check = status.lastCheck;
  const where = [status.cluster, status.host].filter(Boolean).join(' · ');
  if (!check) return { status: 'Configured', tone: 'neutral' as TileTone, detail: `${where} — not checked yet` };
  const color = outcomeColor(check.outcome);
  const tone: TileTone = color === 'teal' ? 'ok' : color === 'yellow' ? 'warn' : color === 'red' ? 'bad' : 'neutral';
  return { status: outcomeLabel(check.outcome), tone, detail: where };
}

function KpiTile({ label, children, hint }: { label: string; children: ReactNode; hint?: ReactNode }) {
  return (
    <Card withBorder radius="md" padding="md">
      <Stat label={label} value={children} hint={hint} />
    </Card>
  );
}

function SplitValue({ left, right, leftLabel, rightLabel, rightColor }: {
  left: number | undefined;
  right: number | undefined;
  leftLabel: string;
  rightLabel: string;
  rightColor?: string;
}) {
  if (left === undefined || right === undefined) {
    return (
      <Text fw={700} size="xl" c="dimmed">
        —
      </Text>
    );
  }
  return (
    <Group gap="xs" align="baseline">
      <Text fw={700} size="xl">
        {formatCount(left)}
      </Text>
      <Text size="sm" c="dimmed">
        {leftLabel} /
      </Text>
      <Text fw={700} size="xl" c={right > 0 ? rightColor : undefined}>
        {formatCount(right)}
      </Text>
      <Text size="sm" c="dimmed">
        {rightLabel}
      </Text>
    </Group>
  );
}

const HISTORY_HINT = `Sparklines are kept in this browser only: the last ${GAUGE_HISTORY_MS / 60_000} minutes of 5-second polls. They reset when you reload the page and pause while the tab is hidden.`;

function Sparkline({
  title,
  samples,
  pick,
  color,
  unit,
  current,
}: {
  title: string;
  samples: GaugeSample[];
  pick: (s: GaugeSample) => number | null;
  color: string;
  unit: string;
  current: ReactNode;
}) {
  const hasData = samples.some((s) => pick(s) != null);
  return (
    <Card withBorder radius="md" padding="md">
      <Group justify="space-between" mb={4}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          {title}
        </Text>
        <Text fw={600} size="sm">
          {current}
        </Text>
      </Group>
      {hasData ? (
        <Chart option={sparklineOption(samples, pick, { color, unit })} height={70} ariaLabel={`${title} over the last 30 minutes`} />
      ) : (
        <Text size="xs" c="dimmed" h={70} pt="lg" ta="center">
          Collecting samples…
        </Text>
      )}
    </Card>
  );
}

const NO_GAUGES: Gauges = {
  heapUsedBytes: null,
  heapMaxBytes: null,
  uptimeMs: null,
  httpRequestCount: null,
  cpuUsage: null,
  httpMeanLatencyMs: null,
};

/** Every gauge may be missing (meter unavailable or an older backend); default each to null. */
function gaugesOf(data: Dashboard): Gauges {
  return { ...NO_GAUGES, ...(data.gauges ?? {}) };
}

function DashboardContent({ data }: { data: Dashboard }) {
  const navigate = useNavigate();
  const samples = useGaugeHistory();
  const solana = useSolanaStatus();
  const solanaInfo = solanaTile(solana.data, solana.isError);
  const gauges = gaugesOf(data);
  const rejections = data.rejectionsByReason ?? [];
  const recent = data.runs?.recent ?? [];
  const pnlByMint = data.realizedPnlByQuoteMint;

  return (
    <Stack gap="lg">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <HealthTile title="Application" status={data.health?.status ?? 'Unknown'} tone={statusTone(data.health?.status)} detail={<>Uptime {formatDuration(gauges.uptimeMs)}</>} />
        <HealthTile
          title="Database"
          status={data.health?.database ?? 'Unknown'}
          tone={statusTone(data.health?.database)}
        />
        <HealthTile title="Solana" status={solanaInfo.status} tone={solanaInfo.tone} detail={solanaInfo.detail} />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
        <KpiTile label="Runs" hint={data.runs?.last24h != null ? `${formatCount(data.runs.last24h)} in the last 24 h` : undefined}>
          <Text fw={700} size="xl">
            {formatCount(data.runs?.total)}
          </Text>
        </KpiTile>
        <KpiTile label="Scenarios">
          <SplitValue left={data.scenarios?.filled} right={data.scenarios?.rejected} leftLabel="filled" rightLabel="rejected" rightColor="orange" />
        </KpiTile>
        <KpiTile label="Positions">
          <SplitValue left={data.positions?.open} right={data.positions?.closed} leftLabel="open" rightLabel="closed" />
        </KpiTile>
        <KpiTile label="Realized P&L by quote mint" hint="Never summed across different mints">
          {pnlByMint === undefined ? (
            <Text fw={700} size="xl" c="dimmed">
              —
            </Text>
          ) : pnlByMint.length === 0 ? (
            <Text size="sm" c="dimmed">
              No closed positions yet
            </Text>
          ) : (
            <Stack gap={2}>
              {pnlByMint.map((row) => (
                <Group key={row.quoteMint} gap="xs" wrap="nowrap">
                  <Pnl value={row.realizedPnl} size="md" />
                  <Mint mint={row.quoteMint} />
                </Group>
              ))}
            </Stack>
          )}
        </KpiTile>
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <Section title="Rejections by reason" hint="Rejected entry scenarios across all runs.">
          {rejections.length === 0 ? (
            <EmptyState title="No rejection data" />
          ) : (
            <Chart option={rejectionsOption(rejections)} height={260} ariaLabel="Bar chart of rejected scenarios by reason" />
          )}
        </Section>
        <Section title="Realized P&L of recent runs" hint="The 20 newest runs, in each run's own quote mint. Click a bar to open the run.">
          {recent.length === 0 ? (
            <EmptyState title="No runs yet" description="Start a run to see its realized P&L here." />
          ) : (
            <Chart
              option={recentPnlOption(recent)}
              height={260}
              ariaLabel="Bar chart of realized P&L per recent run"
              onEvents={{
                click: (params) => {
                  const id = runIdFromClick(params);
                  if (id) void navigate(paths.run(id));
                },
              }}
            />
          )}
        </Section>
      </SimpleGrid>

      <Section title="Runtime" hint={HISTORY_HINT}>
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
          <Sparkline
            title="Heap used"
            samples={samples}
            pick={(s) => (s.heapUsedBytes == null ? null : s.heapUsedBytes / 1024 / 1024)}
            color="#228be6"
            unit="MB"
            current={<>{formatBytes(gauges.heapUsedBytes)} <Text span c="dimmed" size="xs">of {formatBytes(gauges.heapMaxBytes)}</Text></>}
          />
          <Sparkline
            title="HTTP mean latency"
            samples={samples}
            pick={(s) => s.httpMeanLatencyMs}
            color="#7950f2"
            unit="ms"
            current={gauges.httpMeanLatencyMs == null ? '—' : `${gauges.httpMeanLatencyMs.toFixed(1)} ms`}
          />
          <Sparkline
            title="HTTP request rate"
            samples={samples}
            pick={(s) => s.httpRequestRate}
            color="#15aabf"
            unit="req/s"
            current={gauges.httpRequestCount == null ? '—' : `${formatCount(gauges.httpRequestCount)} total`}
          />
          <Card withBorder radius="md" padding="md">
            <Stack gap="sm">
              <Stat label="Uptime" value={<Text fw={700}>{formatDuration(gauges.uptimeMs)}</Text>} />
              <Stat label="Process CPU" value={<Text fw={700}>{formatPercent(gauges.cpuUsage)}</Text>} />
            </Stack>
          </Card>
        </SimpleGrid>
      </Section>
    </Stack>
  );
}

export function DashboardPage() {
  const dashboard = useDashboard();
  const { data } = dashboard;

  useEffect(() => {
    if (data) gaugeHistory.record(data.generatedAt, gaugesOf(data));
  }, [data]);

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Health, run outcomes and runtime gauges. Refreshes every 5 seconds while this tab is visible."
        actions={
          data && (
            <Tooltip label={`Polling every ${DASHBOARD_POLL_MS / 1000} s; paused while the tab is hidden`} withArrow>
              <Badge variant="light" color={dashboard.isError ? 'red' : 'gray'} size="lg">
                {dashboard.isError ? 'Update failed · ' : 'Updated '}
                <Timestamp iso={data.generatedAt} size="xs" />
              </Badge>
            </Tooltip>
          )
        }
      />
      {dashboard.isPending ? (
        <LoadingState label="Loading dashboard…" />
      ) : dashboard.isError && !data ? (
        <ErrorState error={dashboard.error} title="Dashboard unavailable" onRetry={() => void dashboard.refetch()} />
      ) : data ? (
        <DashboardContent data={data} />
      ) : null}
    </>
  );
}

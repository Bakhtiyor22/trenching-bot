import { Badge, Box, Button, Card, Group, SimpleGrid, Stack, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { useEffect, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { DASHBOARD_POLL_MS, useDashboard, useDatasets, useSolanaStatus } from '../../api/queries';
import type { Dashboard, Dataset, Gauges, RecentRun, SolanaStatus } from '../../api/types';
import { Chart } from '../../components/Chart';
import { Mint, Pnl, Timestamp } from '../../components/DisplayValues';
import { IconPlay } from '../../components/Icons';
import { Flash, Kpi, KPI_VALUE_SIZE, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { decimalSign, formatDecimal, pnlColor, toChartNumber } from '../../lib/decimal';
import { formatBytes, formatCount, formatDuration, formatLocalTime, formatPercent, humanizeCode, mintLabel, shortId } from '../../lib/format';
import { paths } from '../../paths';
import { terminal } from '../../theme';
import { outcomeColor, outcomeLabel } from '../solana/solanaOutcome';
import { cumulativePnlOption, runIdFromClick, sparklineOption } from './dashboardCharts';
import { GAUGE_HISTORY_MS, gaugeHistory, useGaugeHistory, type GaugeSample } from './gaugeHistory';

type TileTone = 'ok' | 'warn' | 'bad' | 'neutral';

const TONE_COLOR: Record<TileTone, string> = { ok: terminal.gain, warn: '#F7D068', bad: terminal.loss, neutral: terminal.dimmed };

/** Recent runs listed beside the P&L chart, and entries in the activity feed. */
const RECENT_LIST_SIZE = 6;
const ACTIVITY_SIZE = 6;
const ACTIVITY_DATASETS = 5;

function HealthCell({ title, status, tone, detail }: { title: string; status: string; tone: TileTone; detail?: ReactNode }) {
  return (
    <Box aria-label={`${title} health: ${status}`} className="console-kpi" p="md">
      <Group gap={10} wrap="nowrap" align="flex-start">
        <span
          className={tone === 'ok' ? 'console-live-dot' : undefined}
          style={{ display: 'inline-block', width: 8, height: 8, marginTop: 6, borderRadius: '50%', flex: 'none', background: TONE_COLOR[tone] }}
        />
        <Stack gap={2} style={{ minWidth: 0 }}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={500} style={{ letterSpacing: '0.08em' }}>
            {title}
          </Text>
          {/* Neutral states keep the default text color, never an error color. */}
          <Text fw={600} c={tone === 'neutral' ? undefined : tone === 'ok' ? 'teal' : tone === 'warn' ? 'yellow' : 'red'}>
            {status}
          </Text>
          {detail && (
            <Text size="xs" c="dimmed">
              {detail}
            </Text>
          )}
        </Stack>
      </Group>
    </Box>
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

function BigValue({ children, c }: { children: ReactNode; c?: string }) {
  return (
    <Text ff="monospace" fz={KPI_VALUE_SIZE} fw={500} lh={1.15} c={c}>
      {children}
    </Text>
  );
}

function SplitValue({ left, right, leftLabel, rightLabel }: { left: number | undefined; right: number | undefined; leftLabel: string; rightLabel: string }) {
  if (left === undefined || right === undefined) return <BigValue c="dimmed">—</BigValue>;
  return (
    <BigValue>
      <Flash watch={left}>{formatCount(left)}</Flash>{' '}
      <Text span size="sm" c="dimmed" ff="inherit">
        {leftLabel}
      </Text>{' '}
      · <Flash watch={right}>{formatCount(right)}</Flash>{' '}
      <Text span size="sm" c="dimmed" ff="inherit">
        {rightLabel}
      </Text>
    </BigValue>
  );
}

/** A thin proportional bar, e.g. filled vs rejected. */
function RatioBar({ parts, label }: { parts: Array<{ value: number; color: string }>; label: string }) {
  const total = parts.reduce((sum, p) => sum + p.value, 0);
  return (
    <Box h={6} mt={8} style={{ display: 'flex', gap: 2, borderRadius: 3, overflow: 'hidden', background: terminal.neutral }} aria-label={label} role="img">
      {total > 0 && parts.map((p, i) => <Box key={i} style={{ flex: p.value, background: p.color }} />)}
    </Box>
  );
}

function RecentRunsList({ recent }: { recent: RecentRun[] }) {
  // Bar lengths only: the float never reaches the screen as a number.
  const size = (r: RecentRun) => Math.abs(toChartNumber(r.realizedPnl) ?? 0);
  const largest = recent.reduce((max, r) => Math.max(max, size(r)), 0);
  return (
    <Card padding={0} component="section" aria-label="Recent runs" style={{ display: 'flex', flexDirection: 'column' }}>
      <Group justify="space-between" p="md" pb="xs">
        <Text fw={600} fz={15}>
          Recent runs
        </Text>
        <Text component={Link} to={paths.runs} size="sm" c="amber">
          All runs →
        </Text>
      </Group>
      {recent.length === 0 ? (
        <EmptyState title="No runs yet" description="Start a run to see its realized P&L here." />
      ) : (
        recent.slice(0, RECENT_LIST_SIZE).map((run) => {
          const width = largest > 0 ? Math.max(4, (size(run) / largest) * 100) : 0;
          const color = decimalSign(run.realizedPnl) < 0 ? terminal.loss : decimalSign(run.realizedPnl) > 0 ? terminal.gain : terminal.dimmed;
          return (
            <UnstyledButton
              key={run.id}
              component={Link}
              to={paths.run(run.id)}
              className="console-row-button"
              px="md"
              py={10}
              style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}
            >
              <Group justify="space-between" wrap="nowrap" gap="sm">
                <Text ff="monospace" size="sm">
                  {shortId(run.id)}
                </Text>
                <Text ff="monospace" size="sm" fw={600} c={pnlColor(run.realizedPnl)}>
                  {`${formatDecimal(run.realizedPnl)} ${mintLabel(run.quoteMint)}`}
                </Text>
              </Group>
              <Group justify="space-between" wrap="nowrap" gap="sm" mt={4}>
                <Text size="xs" c="dimmed">
                  {formatLocalTime(run.createdAt)}
                </Text>
                <Box h={4} w={`${width * 0.4}%`} style={{ borderRadius: 2, background: color }} />
              </Group>
            </UnstyledButton>
          );
        })
      )}
      <Box style={{ flex: 1 }} />
      <Button component={Link} to={paths.newRun} m="md" leftSection={<IconPlay size={16} />}>
        Start a run
      </Button>
    </Card>
  );
}

function FunnelRow({ label, value, of, color }: { label: string; value: number | undefined; of: number; color: string }) {
  const width = value !== undefined && of > 0 ? (value / of) * 100 : 0;
  return (
    <Box style={{ display: 'grid', gridTemplateColumns: '110px minmax(0, 1fr) 48px', gap: 12, alignItems: 'center' }}>
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Box h={20} style={{ background: 'var(--mantine-color-default-hover)', borderRadius: 4, overflow: 'hidden' }}>
        <Box h="100%" w={`${width}%`} style={{ background: color, transition: 'width 400ms ease' }} />
      </Box>
      <Text size="sm" ff="monospace" ta="right">
        <Flash watch={value}>{formatCount(value)}</Flash>
      </Text>
    </Box>
  );
}

function ScenarioFunnel({ data }: { data: Dashboard }) {
  const filled = data.scenarios?.filled;
  const rejected = data.scenarios?.rejected;
  const cycles = filled !== undefined && rejected !== undefined ? filled + rejected : undefined;
  const rejections = data.rejectionsByReason ?? [];
  const largest = Math.max(0, ...rejections.map((r) => r.count));
  return (
    <Section title="Scenario funnel" hint="From each first buy to its exit, across all runs.">
      <Stack gap={10}>
        <FunnelRow label="Cycles" value={cycles} of={cycles ?? 0} color={terminal.neutral} />
        <FunnelRow label="Entered" value={filled} of={cycles ?? 0} color={terminal.info} />
        <FunnelRow label="Closed" value={data.positions?.closed} of={cycles ?? 0} color={terminal.accent} />
        <FunnelRow label="Still open" value={data.positions?.open} of={cycles ?? 0} color={terminal.violet} />
      </Stack>
      <Text size="xs" c="dimmed" mt="lg" mb="xs">
        Rejections by reason
      </Text>
      {rejections.length === 0 ? (
        <EmptyState title="No rejection data" />
      ) : (
        <Stack gap={6}>
          {rejections.map((r) => (
            <Box key={r.reason} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 120px 32px', gap: 10, alignItems: 'center' }}>
              <Text size="xs" c={r.count > 0 ? undefined : 'dimmed'} title={r.reason}>
                {humanizeCode(r.reason)}
              </Text>
              <Box h={6} style={{ background: 'var(--mantine-color-default-hover)', borderRadius: 3, overflow: 'hidden' }}>
                <Box h="100%" w={largest > 0 ? `${(r.count / largest) * 100}%` : 0} style={{ background: terminal.rejected }} />
              </Box>
              <Text size="xs" ff="monospace" ta="right" c={r.count > 0 ? undefined : 'dimmed'}>
                {formatCount(r.count)}
              </Text>
            </Box>
          ))}
        </Stack>
      )}
    </Section>
  );
}

interface ActivityItem {
  key: string;
  at: string;
  color: string;
  text: ReactNode;
  to: string;
}

/** Finished runs and imported datasets, newest first. */
function activityItems(recent: RecentRun[], datasets: Dataset[]): ActivityItem[] {
  const runs = recent.map((r): ActivityItem => ({
    key: `run-${r.id}`,
    at: r.createdAt,
    color: decimalSign(r.realizedPnl) < 0 ? terminal.loss : terminal.gain,
    to: paths.run(r.id),
    text: (
      <>
        Run <Text span ff="monospace">{shortId(r.id)}</Text> finished ·{' '}
        <Text span ff="monospace" c={pnlColor(r.realizedPnl)}>
          {formatDecimal(r.realizedPnl)} {mintLabel(r.quoteMint)}
        </Text>
      </>
    ),
  }));
  const imports = datasets.map((d): ActivityItem => ({
    key: `dataset-${d.id}`,
    at: d.createdAt,
    color: terminal.info,
    to: paths.dataset(d.id),
    text: (
      <>
        Dataset <b>{d.name}</b> imported · {formatCount(d.observationCount)} observations
      </>
    ),
  }));
  return [...runs, ...imports].sort((a, b) => b.at.localeCompare(a.at)).slice(0, ACTIVITY_SIZE);
}

function Activity({ recent }: { recent: RecentRun[] }) {
  const datasets = useDatasets(0, ACTIVITY_DATASETS);
  const items = activityItems(recent, datasets.data?.items ?? []);
  return (
    <Section title="Activity" hint="Finished runs and imported datasets.">
      {items.length === 0 ? (
        <EmptyState title="Nothing yet" description="Import a dataset and start a run." />
      ) : (
        <Stack gap={0}>
          {items.map((item) => (
            <UnstyledButton key={item.key} component={Link} to={item.to} className="console-row-button" py={9} px={4} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
              <Box style={{ display: 'grid', gridTemplateColumns: '10px minmax(0, 1fr) auto', gap: 12, alignItems: 'baseline' }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: item.color }} />
                <Text size="sm">{item.text}</Text>
                <Text size="xs" c="dimmed" ff="monospace">
                  {formatLocalTime(item.at)}
                </Text>
              </Box>
            </UnstyledButton>
          ))}
        </Stack>
      )}
    </Section>
  );
}

const HISTORY_HINT = `The last ${GAUGE_HISTORY_MS / 60_000} minutes of 5-second polls, kept in this browser only. They reset on reload and pause while the tab is hidden.`;

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
    <Box p="sm" style={{ background: 'var(--mantine-color-default-hover)', borderRadius: 6 }}>
      <Group justify="space-between" mb={4} wrap="nowrap">
        <Text size="xs" c="dimmed">
          {title}
        </Text>
        <Text fw={500} size="sm" ff="monospace">
          {current}
        </Text>
      </Group>
      {hasData ? (
        <Chart option={sparklineOption(samples, pick, { color, unit })} height={56} ariaLabel={`${title} over the last 30 minutes`} />
      ) : (
        <Text size="xs" c="dimmed" h={56} pt="md" ta="center">
          Collecting samples…
        </Text>
      )}
    </Box>
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

function Runtime({ gauges }: { gauges: Gauges }) {
  const samples = useGaugeHistory();
  return (
    <Section
      title="Runtime"
      hint={HISTORY_HINT}
      actions={
        <Group gap="lg">
          <Text size="xs" c="dimmed">
            Uptime{' '}
            <Text span ff="monospace" c="var(--mantine-color-text)">
              {formatDuration(gauges.uptimeMs)}
            </Text>
          </Text>
          <Text size="xs" c="dimmed">
            CPU{' '}
            <Text span ff="monospace" c="var(--mantine-color-text)">
              {formatPercent(gauges.cpuUsage)}
            </Text>
          </Text>
        </Group>
      }
    >
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Sparkline
          title="Heap used"
          samples={samples}
          pick={(s) => (s.heapUsedBytes == null ? null : s.heapUsedBytes / 1024 / 1024)}
          color={terminal.info}
          unit="MB"
          current={
            <>
              {formatBytes(gauges.heapUsedBytes)}{' '}
              <Text span c="dimmed" size="xs">
                of {formatBytes(gauges.heapMaxBytes)}
              </Text>
            </>
          }
        />
        <Sparkline
          title="HTTP mean latency"
          samples={samples}
          pick={(s) => s.httpMeanLatencyMs}
          color={terminal.accent}
          unit="ms"
          current={gauges.httpMeanLatencyMs == null ? '—' : `${gauges.httpMeanLatencyMs.toFixed(1)} ms`}
        />
        <Sparkline
          title="HTTP request rate"
          samples={samples}
          pick={(s) => s.httpRequestRate}
          color={terminal.violet}
          unit="req/s"
          current={gauges.httpRequestCount == null ? '—' : `${formatCount(gauges.httpRequestCount)} total`}
        />
      </SimpleGrid>
    </Section>
  );
}

function DashboardContent({ data }: { data: Dashboard }) {
  const navigate = useNavigate();
  const solana = useSolanaStatus();
  const solanaInfo = solanaTile(solana.data, solana.isError);
  const gauges = gaugesOf(data);
  const recent = data.runs?.recent ?? [];
  const pnlByMint = data.realizedPnlByQuoteMint;

  return (
    <Stack gap="lg">
      <KpiStrip label="Health" min={200}>
        <HealthCell title="Application" status={data.health?.status ?? 'Unknown'} tone={statusTone(data.health?.status)} detail={<>Uptime {formatDuration(gauges.uptimeMs)}</>} />
        <HealthCell title="Database" status={data.health?.database ?? 'Unknown'} tone={statusTone(data.health?.database)} />
        <HealthCell title="Solana" status={solanaInfo.status} tone={solanaInfo.tone} detail={solanaInfo.detail} />
      </KpiStrip>

      <KpiStrip label="Key figures">
        <Kpi label="Realized P&L by quote mint" hint="Never summed across different mints">
          {pnlByMint === undefined ? (
            <BigValue c="dimmed">—</BigValue>
          ) : pnlByMint.length === 0 ? (
            <Text size="sm" c="dimmed">
              No closed positions yet
            </Text>
          ) : (
            <Stack gap={2}>
              {pnlByMint.map((row) => (
                <Group key={row.quoteMint} gap="xs" wrap="nowrap" align="baseline">
                  <Flash watch={row.realizedPnl}>
                    <Pnl value={row.realizedPnl} fz={KPI_VALUE_SIZE} fw={500} />
                  </Flash>
                  <Mint mint={row.quoteMint} copy={false} />
                </Group>
              ))}
            </Stack>
          )}
        </Kpi>
        <Kpi label="Runs" hint={data.runs?.last24h != null ? `${formatCount(data.runs.last24h)} in the last 24 h` : undefined}>
          <BigValue>
            <Flash watch={data.runs?.total}>{formatCount(data.runs?.total)}</Flash>
          </BigValue>
        </Kpi>
        <Kpi label="Scenarios">
          <SplitValue left={data.scenarios?.filled} right={data.scenarios?.rejected} leftLabel="filled" rightLabel="rejected" />
          {data.scenarios && (
            <RatioBar
              label={`${data.scenarios.filled} filled, ${data.scenarios.rejected} rejected`}
              parts={[
                { value: data.scenarios.filled, color: terminal.info },
                { value: data.scenarios.rejected, color: terminal.rejected },
              ]}
            />
          )}
        </Kpi>
        <Kpi label="Positions">
          <SplitValue left={data.positions?.open} right={data.positions?.closed} leftLabel="open" rightLabel="closed" />
        </Kpi>
      </KpiStrip>

      <Box style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--mantine-spacing-lg)', alignItems: 'stretch' }}>
        <Box style={{ flex: '2 1 560px', minWidth: 0 }}>
          <Section
            title="Cumulative realized P&L"
            hint={`Running total over the ${recent.length} newest runs, one line per quote mint. Ctrl + scroll to zoom, drag to pan, click a step to open its run.`}
          >
            {recent.length === 0 ? (
              <EmptyState title="Nothing to chart yet" description="The running total starts with the first finished run." />
            ) : (
              <Chart
                option={cumulativePnlOption(recent)}
                height={300}
                ariaLabel="Step chart of the running total of realized P&L, per quote mint"
                onEvents={{
                  click: (params) => {
                    const id = runIdFromClick(params);
                    if (id) void navigate(paths.run(id));
                  },
                }}
              />
            )}
          </Section>
        </Box>
        <Box style={{ flex: '1 1 320px', minWidth: 0, display: 'flex' }}>
          <Box style={{ flex: 1 }}>
            <RecentRunsList recent={recent} />
          </Box>
        </Box>
      </Box>

      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <ScenarioFunnel data={data} />
        <Activity recent={recent} />
      </SimpleGrid>

      <Runtime gauges={gauges} />
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
        description="Health, run outcomes and runtime, refreshed every 5 seconds while this tab is visible."
        actions={
          data && (
            <Tooltip label={`Polling every ${DASHBOARD_POLL_MS / 1000} s; paused while the tab is hidden`}>
              <Badge
                variant="outline"
                color={dashboard.isError ? 'red' : 'gray'}
                size="lg"
                tt="none"
                fw={400}
                leftSection={<span className={dashboard.isError ? undefined : 'console-live-dot'} style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: dashboard.isError ? terminal.loss : terminal.gain }} />}
              >
                {dashboard.isError ? 'Update failed · ' : 'Live · '}
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

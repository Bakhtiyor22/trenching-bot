import {
  Anchor,
  Box,
  Button,
  Card,
  Group,
  ScrollArea,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAutomationDays, useAutomationStatus } from '../../api/automation';
import { ApiError } from '../../api/client';
import { DASHBOARD_POLL_MS, useDashboard, useDatasets, useSolanaStatus } from '../../api/queries';
import type { Dashboard, Dataset, Gauges, RecentRun, SolanaStatus } from '../../api/types';
import { Chart } from '../../components/Chart';
import { Pnl, Timestamp } from '../../components/DisplayValues';
import { Meter } from '../../components/Meter';
import { Flash, Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { StatusChip } from '../../components/StatusChip';
import { useChartTokens } from '../../charts/chartTokens';
import { decimalSign, formatSignedDecimal, pnlColor } from '../../lib/decimal';
import {
  formatBytes,
  formatCount,
  formatDuration,
  formatLocalTime,
  formatPercent,
  humanizeCode,
  mintLabel,
  shortId,
} from '../../lib/format';
import { paths } from '../../paths';
import {
  automationMode,
  dayInfo,
  defaultScoredDay,
  statusGlyph,
  statusText,
  variationChange,
} from '../automation/automationModel';
import { clusterLabel, outcomeLabel, outcomeTone } from '../solana/solanaOutcome';
import { cumulativePnlOption, cumulativePnlSteps, runIdFromClick, sparklineOption } from './dashboardCharts';
import { GAUGE_HISTORY_MS, gaugeHistory, useGaugeHistory, type GaugeSample } from './gaugeHistory';

type HealthTone = 'ok' | 'warn' | 'bad' | 'neutral';

const TONE_COLOR: Record<HealthTone, string> = {
  ok: 'var(--console-gain)',
  warn: 'var(--console-rejected)',
  bad: 'var(--console-loss)',
  neutral: 'var(--mantine-color-dimmed)',
};

/** Recent runs listed beside the P&L chart, and entries in the activity feed. */
const RECENT_LIST_SIZE = 6;
const ACTIVITY_SIZE = 6;
const ACTIVITY_DATASETS = 5;
const ROW_BORDER = '1px solid var(--mantine-color-default-border)';

function healthTone(status: string | null | undefined): HealthTone {
  if (!status) return 'neutral';
  if (status === 'UP') return 'ok';
  if (status === 'UNKNOWN' || status === 'OUT_OF_SERVICE') return 'warn';
  return 'bad';
}

/** A backend health code as a word: "UP" reads "Up", a missing one "Unknown". */
function healthWord(status: string | null | undefined): string {
  return status ? humanizeCode(status) : 'Unknown';
}

/** The Solana item is neutral, never red, when no RPC is configured. */
function solanaHealth(status: SolanaStatus | undefined, error: boolean) {
  if (error) return { status: 'Unknown', tone: 'warn' as HealthTone, detail: 'status endpoint failed' };
  if (!status) return { status: 'Checking…', tone: 'neutral' as HealthTone };
  if (!status.configured) {
    return { status: 'Not configured', tone: 'neutral' as HealthTone, detail: 'Set SOLANA_RPC_URL to enable' };
  }
  const check = status.lastCheck;
  const where = [status.cluster ? clusterLabel(status.cluster) : null, status.host].filter(Boolean).join(' · ');
  if (!check) return { status: 'Configured', tone: 'neutral' as HealthTone, detail: `${where} — not checked yet` };
  const tone = outcomeTone(check.outcome);
  const health: HealthTone = tone === 'success' || tone === 'ready' ? 'ok' : tone === 'failure' ? 'bad' : tone === 'attention' ? 'warn' : 'neutral';
  return { status: outcomeLabel(check.outcome), tone: health, detail: where };
}

function HealthItem({ title, status, tone, detail }: { title: string; status: string; tone: HealthTone; detail?: ReactNode }) {
  return (
    <Group gap={8} wrap="nowrap" aria-label={`${title} health: ${status}`} align="baseline">
      <span
        className={tone === 'ok' ? 'console-live-dot' : undefined}
        style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', flex: 'none', background: TONE_COLOR[tone], alignSelf: 'center' }}
      />
      <Text size="sm">
        {title}{' '}
        {/* Neutral states keep the default text colour, never an error colour. */}
        <Text span fw={500} c={tone === 'ok' ? undefined : tone === 'neutral' ? undefined : tone === 'warn' ? 'orange' : 'red'}>
          {status}
        </Text>
        {detail && (
          <Text span c="dimmed">
            {' '}
            · {detail}
          </Text>
        )}
      </Text>
    </Group>
  );
}

function HealthLine({ data }: { data: Dashboard }) {
  const solana = useSolanaStatus();
  const solanaInfo = solanaHealth(solana.data, solana.isError);
  const gauges = gaugesOf(data);
  return (
    <Card component="section" aria-label="System health" py="sm" px="md">
      <Group gap="xs" wrap="wrap" style={{ columnGap: 'var(--mantine-spacing-xl)' }}>
        <HealthItem
          title="Application"
          status={healthWord(data.health?.status)}
          tone={healthTone(data.health?.status)}
          detail={gauges.uptimeMs != null ? `up ${formatDuration(gauges.uptimeMs)}` : undefined}
        />
        <HealthItem title="Database" status={healthWord(data.health?.database)} tone={healthTone(data.health?.database)} />
        <HealthItem title="Solana" status={solanaInfo.status} tone={solanaInfo.tone} detail={solanaInfo.detail} />
        <Box style={{ flex: 1 }} />
        <Anchor component={Link} to={paths.solana} size="sm">
          Check Solana
        </Anchor>
      </Group>
    </Card>
  );
}

function CountValue({ value, unit }: { value: number | undefined; unit?: string }) {
  if (value === undefined) return <Text c="dimmed" fz="inherit" ff="inherit">—</Text>;
  return (
    <>
      <Flash watch={value}>{formatCount(value)}</Flash>
      {unit && <span className="console-unit">{unit}</span>}
    </>
  );
}

function KeyFigures({ data }: { data: Dashboard }) {
  const pnlByMint = data.realizedPnlByQuoteMint;
  const filled = data.scenarios?.filled;
  const rejected = data.scenarios?.rejected;
  const scenarios = filled !== undefined && rejected !== undefined ? filled + rejected : undefined;
  return (
    <KpiStrip label="Key figures" min={200}>
      <Kpi label="Realized P&L" size="hero" span="full" hint="Closed positions of every run, per quote mint. Never summed across mints.">
        {pnlByMint === undefined ? (
          <Text c="dimmed" fz="inherit" ff="inherit">
            —
          </Text>
        ) : pnlByMint.length === 0 ? (
          <Text size="sm" c="dimmed" ff="var(--mantine-font-family)">
            No closed positions yet
          </Text>
        ) : (
          <Stack gap={2}>
            {pnlByMint.map((row) => (
              <Flash key={row.quoteMint} watch={row.realizedPnl}>
                <Pnl value={row.realizedPnl} trim unit={mintLabel(row.quoteMint)} fz="inherit" />
              </Flash>
            ))}
          </Stack>
        )}
      </Kpi>
      <Kpi label="Runs" hint={data.runs?.last24h != null ? `${formatCount(data.runs.last24h)} in the last 24 h` : undefined}>
        <CountValue value={data.runs?.total} />
      </Kpi>
      <Kpi
        label="Scenarios"
        hint={filled !== undefined && rejected !== undefined ? `${formatCount(filled)} filled · ${formatCount(rejected)} rejected` : undefined}
      >
        <CountValue value={scenarios} />
        {filled !== undefined && rejected !== undefined && (
          <Box mt={8}>
            <Meter
              label="Scenarios"
              segments={[
                { value: filled, color: 'var(--console-info)', label: 'filled' },
                { value: rejected, color: 'var(--console-rejected)', label: 'rejected' },
              ]}
            />
          </Box>
        )}
      </Kpi>
      <Kpi label="Positions" hint={data.positions ? `${formatCount(data.positions.closed)} closed` : undefined}>
        <CountValue value={data.positions?.open} unit="open" />
      </Kpi>
    </KpiStrip>
  );
}

/** The chart's data as a table: each run's step and the running total, newest first. */
function CumulativeTable({ recent }: { recent: RecentRun[] }) {
  const rows = [...cumulativePnlSteps(recent).values()].flat().sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return (
    <ScrollArea type="auto">
      <Table miw={560}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Finished</Table.Th>
            <Table.Th>Run</Table.Th>
            <Table.Th ta="right">Run P&amp;L</Table.Th>
            <Table.Th ta="right">Running total</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.runId}>
              <Table.Td>
                <Timestamp iso={row.createdAt} />
              </Table.Td>
              <Table.Td>
                <Anchor component={Link} to={paths.run(row.runId)} ff="monospace" size="sm">
                  {shortId(row.runId)}
                </Anchor>
              </Table.Td>
              <Table.Td ta="right">
                <Pnl value={row.step} unit={mintLabel(row.quoteMint)} />
              </Table.Td>
              <Table.Td ta="right">
                <Pnl value={row.total} unit={mintLabel(row.quoteMint)} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function CumulativePnl({ recent }: { recent: RecentRun[] }) {
  const navigate = useNavigate();
  const tokens = useChartTokens();
  const [view, setView] = useState<'chart' | 'table'>('chart');
  return (
    <Section
      title="Cumulative realized P&L"
      hint={`Running total over the ${recent.length} newest runs, one line per quote mint. Each step is a run: click it to open the run; Ctrl + scroll zooms, drag pans.`}
      actions={
        recent.length > 0 && (
          <SegmentedControl
            size="xs"
            value={view}
            onChange={(value) => setView(value as 'chart' | 'table')}
            data={[
              { value: 'chart', label: 'Chart' },
              { value: 'table', label: 'Table' },
            ]}
            aria-label="Cumulative P&L view"
          />
        )
      }
    >
      {recent.length === 0 ? (
        <EmptyState title="Nothing to chart yet" description="The running total starts with the first finished run." />
      ) : view === 'table' ? (
        <CumulativeTable recent={recent} />
      ) : (
        <Chart
          option={cumulativePnlOption(recent, undefined, tokens)}
          height={300}
          ariaLabel="Step chart of the running total of realized P&L, per quote mint; red while the total is below zero"
          onEvents={{
            click: (params) => {
              const id = runIdFromClick(params);
              if (id) void navigate(paths.run(id));
            },
          }}
        />
      )}
    </Section>
  );
}

function RecentRunsList({ recent }: { recent: RecentRun[] }) {
  return (
    <Card padding={0} component="section" aria-label="Recent runs" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Group justify="space-between" p="lg" pb="sm">
        <Text fw={600} fz={15}>
          Recent runs
        </Text>
        <Anchor component={Link} to={paths.runs} size="sm">
          All runs
        </Anchor>
      </Group>
      {recent.length === 0 ? (
        <EmptyState title="No runs yet" description="Start a run to see its realized P&L here." />
      ) : (
        recent.slice(0, RECENT_LIST_SIZE).map((run) => (
          <UnstyledButton
            key={run.id}
            component={Link}
            to={paths.run(run.id)}
            className="console-row-button"
            px="lg"
            py={10}
            style={{ borderTop: ROW_BORDER }}
          >
            <Group justify="space-between" wrap="nowrap" gap="sm">
              <Text ff="monospace" size="sm">
                {shortId(run.id)}
              </Text>
              <Pnl value={run.realizedPnl} unit={mintLabel(run.quoteMint)} />
            </Group>
            <Text size="xs" c="dimmed" mt={2}>
              {formatLocalTime(run.createdAt)}
            </Text>
          </UnstyledButton>
        ))
      )}
      <Box style={{ flex: 1 }} />
      <Box p="lg" pt="md" style={{ borderTop: ROW_BORDER }}>
        <Button component={Link} to={paths.newRun} fullWidth>
          Start a run
        </Button>
      </Box>
    </Card>
  );
}

function OutcomeRow({ label, value, of, color }: { label: string; value: number | undefined; of: number; color: string }) {
  return (
    <Box style={{ display: 'grid', gridTemplateColumns: '96px minmax(0, 1fr) 48px', gap: 12, alignItems: 'center' }}>
      <Text size="sm">{label}</Text>
      <Meter label={label} value={value ?? 0} max={of} color={color} size="lg" />
      <Text size="sm" ff="monospace" ta="right">
        <Flash watch={value}>{formatCount(value)}</Flash>
      </Text>
    </Box>
  );
}

function ScenarioOutcomes({ data }: { data: Dashboard }) {
  const filled = data.scenarios?.filled;
  const rejected = data.scenarios?.rejected;
  const cycles = filled !== undefined && rejected !== undefined ? filled + rejected : undefined;
  const rejections = data.rejectionsByReason ?? [];
  const largest = Math.max(0, ...rejections.map((r) => r.count));
  return (
    <Section title="How scenarios ended" hint="Every cycle of the stored runs, from Decu's first buy to its exit. The bars share one scale.">
      <Stack gap={10}>
        <OutcomeRow label="Cycles" value={cycles} of={cycles ?? 0} color="var(--console-neutral)" />
        <OutcomeRow label="Entered" value={filled} of={cycles ?? 0} color="var(--console-info)" />
        <OutcomeRow label="Closed" value={data.positions?.closed} of={cycles ?? 0} color="var(--console-text-2)" />
        <OutcomeRow label="Still open" value={data.positions?.open} of={cycles ?? 0} color="var(--console-neutral)" />
        <OutcomeRow label="Rejected" value={rejected} of={cycles ?? 0} color="var(--console-rejected)" />
      </Stack>
      <Text size="xs" c="dimmed" mt="lg" mb="xs" fw={500}>
        Why scenarios were rejected
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
              <Meter label={humanizeCode(r.reason)} value={r.count} max={largest} color="var(--console-rejected)" />
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

const MODE_CHIP = {
  OFF: { tone: 'neutral', text: 'Off' },
  DISCOVERY_ONLY: { tone: 'neutral', text: 'Discovery only' },
  CAPTURING: { tone: 'running', text: 'Capturing' },
} as const;

const VERDICT_TONE = { SUCCESS: 'success', NO_SUCCESS: 'failure', NO_RESULT: 'neutral', PENDING: 'pending' } as const;

/** The unattended part at a glance: mode, the newest verdict and the request cap. Read-only; it opens the Automation page. */
function AutomationSummary() {
  const status = useAutomationStatus();
  const off = status.error instanceof ApiError && status.error.status === 404;
  const days = useAutomationDays({}, status.data !== undefined);
  return (
    <Section
      title="Automation"
      hint="Decu's buys, captured and replayed under the sweep plan."
      actions={
        <Anchor component={Link} to={paths.automation} size="sm">
          Open
        </Anchor>
      }
    >
      {off ? (
        <Text size="sm" c="dimmed">
          Automation is off on this backend. The Automation page says which settings turn it on.
        </Text>
      ) : (
        <QueryView query={status} loadingLabel="Loading automation…" errorTitle="Automation status unavailable">
          {(s) => {
            const mode = MODE_CHIP[automationMode(s)];
            const scored = defaultScoredDay(days.data?.items ?? []);
            const today = days.data?.items[0];
            return (
              <Stack gap="md">
                <Group gap="sm" wrap="wrap">
                  <StatusChip tone={mode.tone}>{mode.text}</StatusChip>
                  <Text size="sm" c="dimmed">
                    {s.mode === 'ALWAYS' ? 'always on' : `window ${s.pollFrom}–${s.pollTo}`}
                    {today ? ` · today ${today.buys === 0 ? 'no buys yet' : `${formatCount(today.buys)} buys`}` : ''}
                  </Text>
                </Group>
                <Stack gap={4}>
                  <span className="console-label">
                    Latest verdict{scored ? ` · ${dayInfo(scored.day).label}` : ''}
                  </span>
                  {scored?.bestStatus ? (
                    <Group gap="xs" wrap="wrap">
                      <StatusChip tone={VERDICT_TONE[scored.bestStatus]} glyph={statusGlyph[scored.bestStatus]}>
                        {statusText[scored.bestStatus]}
                      </StatusChip>
                      {scored.bestLabel && (
                        <Text size="sm">
                          <Text span ff="monospace" fw={500}>
                            {scored.bestLabel}
                          </Text>{' '}
                          <Text span c="dimmed">
                            {variationChange(s.sweepPlanId, scored.bestLabel) ?? ''}
                          </Text>
                        </Text>
                      )}
                    </Group>
                  ) : (
                    <Text size="sm" c="dimmed">
                      No day scored yet
                    </Text>
                  )}
                </Stack>
                <Stack gap={6}>
                  <Group justify="space-between" gap="xs">
                    <span className="console-label">Requests, last 24 h</span>
                    <Text size="xs" ff="monospace">
                      {formatCount(s.requestsLast24h)} / {formatCount(s.maxRequestsPer24h)}
                    </Text>
                  </Group>
                  <Meter label="Requests used of the 24 hour cap" value={s.requestsLast24h} max={s.maxRequestsPer24h} />
                </Stack>
              </Stack>
            );
          }}
        </QueryView>
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

function signDot(value: string): string {
  const sign = decimalSign(value);
  return sign < 0 ? 'var(--console-loss)' : sign > 0 ? 'var(--console-gain)' : 'var(--console-neutral)';
}

/** Finished runs and imported datasets, newest first. */
function activityItems(recent: RecentRun[], datasets: Dataset[]): ActivityItem[] {
  const runs = recent.map((r): ActivityItem => ({
    key: `run-${r.id}`,
    at: r.createdAt,
    color: signDot(r.realizedPnl),
    to: paths.run(r.id),
    text: (
      <>
        Run <Text span ff="monospace">{shortId(r.id)}</Text> finished ·{' '}
        <Text span ff="monospace" c={pnlColor(r.realizedPnl)}>
          {formatSignedDecimal(r.realizedPnl)} {mintLabel(r.quoteMint)}
        </Text>
      </>
    ),
  }));
  const imports = datasets.map((d): ActivityItem => ({
    key: `dataset-${d.id}`,
    at: d.createdAt,
    color: 'var(--console-info)',
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
            <UnstyledButton key={item.key} component={Link} to={item.to} className="console-row-button" py={9} px={4} style={{ borderBottom: ROW_BORDER }}>
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
  unit,
  current,
}: {
  title: string;
  samples: GaugeSample[];
  pick: (s: GaugeSample) => number | null;
  unit: string;
  current: ReactNode;
}) {
  const tokens = useChartTokens();
  const hasData = samples.some((s) => pick(s) != null);
  return (
    <Stack gap={4}>
      <Group justify="space-between" wrap="nowrap" align="baseline">
        <span className="console-label">{title}</span>
        <Text fw={500} size="sm" ff="monospace">
          {current}
        </Text>
      </Group>
      {hasData ? (
        <Chart
          option={sparklineOption(samples, pick, { color: tokens.dimmed, unit, latest: tokens.accent })}
          height={48}
          ariaLabel={`${title} over the last 30 minutes`}
        />
      ) : (
        <Text size="xs" c="dimmed" h={48} pt="md" ta="center">
          Collecting samples…
        </Text>
      )}
    </Stack>
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
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="lg">
        <Sparkline
          title="Heap used"
          samples={samples}
          pick={(s) => (s.heapUsedBytes == null ? null : s.heapUsedBytes / 1024 / 1024)}
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
          unit="ms"
          current={gauges.httpMeanLatencyMs == null ? '—' : `${gauges.httpMeanLatencyMs.toFixed(1)} ms`}
        />
        <Sparkline
          title="HTTP request rate"
          samples={samples}
          pick={(s) => s.httpRequestRate}
          unit="req/s"
          current={gauges.httpRequestCount == null ? '—' : `${formatCount(gauges.httpRequestCount)} total`}
        />
      </SimpleGrid>
    </Section>
  );
}

function DashboardContent({ data }: { data: Dashboard }) {
  const recent = data.runs?.recent ?? [];
  return (
    <Stack gap="lg">
      <HealthLine data={data} />
      <KeyFigures data={data} />

      <Box style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--mantine-spacing-lg)', alignItems: 'stretch' }}>
        <Box style={{ flex: '2 1 560px', minWidth: 0 }}>
          <CumulativePnl recent={recent} />
        </Box>
        <Box style={{ flex: '1 1 300px', minWidth: 0 }}>
          <RecentRunsList recent={recent} />
        </Box>
      </Box>

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
        <ScenarioOutcomes data={data} />
        <AutomationSummary />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="lg">
        <Activity recent={recent} />
        <Runtime gauges={gaugesOf(data)} />
      </SimpleGrid>
    </Stack>
  );
}

function LiveBadge({ failed, at }: { failed: boolean; at: string }) {
  return (
    <Tooltip label={`Polling every ${DASHBOARD_POLL_MS / 1000} s; paused while the tab is hidden`}>
      <Group
        gap={8}
        wrap="nowrap"
        px="sm"
        h={28}
        role="status"
        style={{ border: '1px solid var(--mantine-color-default-border)', borderRadius: 14 }}
      >
        <span
          className={failed ? undefined : 'console-live-dot'}
          style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: failed ? 'var(--console-loss)' : 'var(--console-gain)' }}
        />
        <Text size="xs" c={failed ? 'red' : undefined}>
          {failed ? 'Update failed · ' : 'Live · '}
        </Text>
        <Timestamp iso={at} size="xs" ff="monospace" />
      </Group>
    </Tooltip>
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
        description="Did the strategy make or lose money, and is the system healthy? Refreshed every 5 seconds while this tab is visible."
        actions={data && <LiveBadge failed={dashboard.isError} at={data.generatedAt} />}
      />
      <QueryView query={dashboard} loadingLabel="Loading dashboard…" errorTitle="Dashboard unavailable">
        {(loaded) => <DashboardContent data={loaded} />}
      </QueryView>
    </>
  );
}

import { Alert, Box, Button, Group, ScrollArea, Select, Stack, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Fragment } from 'react';
import { useSearchParams } from 'react-router';
import { setupApi, type UsagePeriod, type UsageProvider, type UsageReport } from '../../api/setup';
import { Chart } from '../../components/Chart';
import { RelativeTime } from '../../components/DisplayValues';
import { Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { providerLabels, purposeLabels, taskLabels } from '../../lib/codes';
import {
  aggregateUsage,
  allCaptureChoices,
  groupUsageCalls,
  dataSize,
  sortUsageTasks,
} from './usageData';

const PERIOD_LABELS: Record<UsagePeriod, string> = {
  LAST_HOUR: 'Last hour',
  LAST_24_HOURS: 'Last 24 hours',
  LAST_7_DAYS: 'Last 7 days',
  ALL: 'All recorded',
};
const PROVIDER_COLORS: Record<UsageProvider, string> = {
  SOLANA_RPC: '#65a9ff',
  BINANCE: '#e5ad52',
  TELEGRAM: '#9099a4',
};
const PROVIDER_NOTES: Partial<Record<UsageProvider, string>> = {
  SOLANA_RPC: 'your configured provider; address hidden because it can contain a key',
  BINANCE: 'public market data, no key',
};
function recordingSentence(report: UsageReport) {
  return report.recordingStartedAt
    ? `Recording started ${new Date(report.recordingStartedAt).toLocaleString()}; nothing before that is counted.`
    : 'Nothing has been recorded yet.';
}
function ShareBar({
  percent,
  color = 'var(--mantine-color-blue-5)',
}: {
  percent: string;
  color?: string;
}) {
  return (
    <Box
      h={4}
      miw={65}
      bg="var(--mantine-color-dark-4)"
      style={{ borderRadius: 3, overflow: 'hidden' }}
    >
      <Box h="100%" style={{ width: `${percent}%`, maxWidth: '100%', background: color }} />
    </Box>
  );
}
function UsageContent({
  report,
  period,
  updatedAt,
  onShowDay,
}: {
  report: UsageReport;
  period: UsagePeriod;
  updatedAt: number;
  onShowDay: () => void;
}) {
  const points = aggregateUsage(report.series, period === 'LAST_HOUR' ? 300 : 3600);
  const times = [...new Set(points.map((point) => point.bucketStart))];
  const providers = [...new Set(points.map((point) => point.provider))];
  const periodText = PERIOD_LABELS[period].toLowerCase();
  const empty = report.totals.requests === 0 && report.totals.cacheHits === 0;
  const taskTotal = report.byTask.reduce((sum, task) => sum + task.totals.requests, 0);
  return (
    <Stack gap="lg">
      <Text size="sm">
        Showing{' '}
        <strong>
          {new Date(report.from).toLocaleString()} → {new Date(report.to).toLocaleString()}
        </strong>{' '}
        (your local time), in {period === 'LAST_HOUR' ? '5-minute' : 'hourly'} buckets. Updated{' '}
        <RelativeTime iso={new Date(updatedAt).toISOString()} />. {recordingSentence(report)}
      </Text>
      <KpiStrip label="API usage totals">
        <Kpi label="Requests sent" hint={`HTTP calls, all APIs, ${periodText}`}>
          {report.totals.requests.toLocaleString()}
        </Kpi>
        <Kpi label="Data received" hint={`response bodies, ${periodText}`}>
          {dataSize(report.totals.responseBytes)}
        </Kpi>
        <Kpi label="Rate-limited replies" hint="HTTP 429 answers, retried automatically">
          {report.totals.rateLimitedResponses.toLocaleString()}
        </Kpi>
        <Kpi label="Served from cache" hint="transactions, no request sent">
          {report.totals.cacheHits.toLocaleString()}
        </Kpi>
      </KpiStrip>
      {empty ? (
        <EmptyState
          title={`No calls in the ${periodText}`}
          description={recordingSentence(report)}
          action={
            period === 'LAST_HOUR' ? (
              <Button variant="default" onClick={onShowDay}>
                Show last 24 hours
              </Button>
            ) : undefined
          }
        />
      ) : (
        <Section title={`Requests sent per ${period === 'LAST_HOUR' ? '5 minutes' : 'hour'}`}>
          <Chart
            ariaLabel="Provider requests over time"
            height={300}
            option={{
              tooltip: { trigger: 'axis', valueFormatter: (value) => `${String(value)} requests` },
              legend: { top: 0 },
              grid: { left: 50, right: 20, top: 45, bottom: 60 },
              xAxis: {
                type: 'category',
                data: times.map((time) => {
                  const date = new Date(time);
                  const clock = date.toLocaleTimeString('en-GB', {
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false,
                  });
                  return period === 'LAST_7_DAYS' || period === 'ALL'
                    ? `${date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${clock}`
                    : clock;
                }),
              },
              yAxis: { type: 'value', minInterval: 1, name: 'Requests' },
              series: providers.map((provider) => ({
                name: providerLabels[provider],
                type: 'bar',
                stack: 'requests',
                itemStyle: { color: PROVIDER_COLORS[provider] },
                data: times.map(
                  (time) =>
                    points.find(
                      (point) => point.bucketStart === time && point.provider === provider,
                    )?.requests ?? 0,
                ),
              })),
            }}
          />
        </Section>
      )}
      <Section title="Calls by provider">
        <ScrollArea>
          <Table miw={1100}>
            <Table.Thead>
              <Table.Tr>
                {[
                  'API',
                  'Call',
                  'Used for',
                  'Requests',
                  'Share',
                  'Failed',
                  'Rate-limited',
                  'Data',
                  'Last call',
                ].map((label) => (
                  <Table.Th key={label}>{label}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groupUsageCalls(report.byCall).map((group) => (
                <Fragment key={group.provider}>
                  <Table.Tr>
                    <Table.Th colSpan={9} scope="colgroup" py="md">
                      <Text size="sm" fw={600}>
                        {providerLabels[group.provider]}
                      </Text>
                      {PROVIDER_NOTES[group.provider] && (
                        <Text size="xs" c="dimmed">
                          {PROVIDER_NOTES[group.provider]}
                        </Text>
                      )}
                    </Table.Th>
                  </Table.Tr>
                  {group.calls.map((call) => (
                    <Table.Tr key={`${call.provider}/${call.operation}/${call.purpose}`}>
                      <Table.Td>{providerLabels[call.provider]}</Table.Td>
                      <Table.Td>
                        <Text size="xs" ff="monospace">
                          {call.operation}
                        </Text>
                      </Table.Td>
                      <Table.Td miw={290}>{purposeLabels[call.purpose]}</Table.Td>
                      <Table.Td>{call.totals.requests.toLocaleString()}</Table.Td>
                      <Table.Td>
                        <Stack gap={4}>
                          <Text size="xs">{call.sharePercent}%</Text>
                          <ShareBar
                            percent={call.sharePercent}
                            color={PROVIDER_COLORS[call.provider]}
                          />
                        </Stack>
                      </Table.Td>
                      <Table.Td>{call.totals.failedRequests.toLocaleString()}</Table.Td>
                      <Table.Td>{call.totals.rateLimitedResponses.toLocaleString()}</Table.Td>
                      <Table.Td>{dataSize(call.totals.responseBytes)}</Table.Td>
                      <Table.Td>
                        <Text
                          size="xs"
                          ff="monospace"
                          title={
                            call.lastCallAt ? new Date(call.lastCallAt).toLocaleString() : undefined
                          }
                        >
                          {call.lastCallAt
                            ? new Date(call.lastCallAt).toLocaleTimeString('en-GB', {
                                hour12: false,
                              })
                            : '—'}
                        </Text>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Fragment>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Section>
      <Section title="By task">
        <Stack gap="md">
          {sortUsageTasks(report.byTask).map((task) => (
            <Stack gap={5} key={task.task}>
              <Group justify="space-between" gap="xs">
                <Text size="sm">{taskLabels[task.task]}</Text>
                <Text size="sm" ff="monospace">
                  {task.totals.requests.toLocaleString()} req
                </Text>
              </Group>
              <ShareBar
                percent={taskTotal ? String((task.totals.requests * 100) / taskTotal) : '0'}
              />
            </Stack>
          ))}
        </Stack>
      </Section>
      <Section title="Limits and units">
        <Stack gap="xs">
          {report.pacing.map((item) => (
            <Text key={item.provider} size="sm">
              {providerLabels[item.provider]} pace set in this backend:{' '}
              {item.minRequestIntervalMillis > 0
                ? (1000 / item.minRequestIntervalMillis).toLocaleString(undefined, {
                    maximumFractionDigits: 3,
                  })
                : 'unlimited'}{' '}
              requests per second, {item.maxConcurrentRequests} at a time.
            </Text>
          ))}
          <Text size="sm">
            Requests are not billing credits. Some providers bill calls differently; check your
            provider's dashboard for credits used.
          </Text>
          <Text size="sm">
            1 request = one HTTP call sent. Retries count separately. Cache hits send no request. 1
            KB = 1,000 bytes and 1 MB = 1,000,000 bytes.
          </Text>
          {!report.byCall.some((call) => call.provider === 'TELEGRAM') && (
            <Text size="sm">Telegram: not used yet.</Text>
          )}
          <Text size="sm">
            Gradle CLI captures are not counted. A hard process stop can lose the last 15 seconds of
            counts.
          </Text>
        </Stack>
      </Section>
    </Stack>
  );
}
export function ApiUsagePage() {
  const [params, setParams] = useSearchParams();
  const selected = params.get('period');
  const period: UsagePeriod =
    selected === 'LAST_24_HOURS' || selected === 'LAST_7_DAYS' || selected === 'ALL'
      ? selected
      : 'LAST_HOUR';
  const captureId = params.get('captureId');
  const captures = useQuery({
    queryKey: ['captures', 'usage-options'],
    queryFn: ({ signal }) => allCaptureChoices(signal),
  });
  const usage = useQuery({
    queryKey: ['usage', period, captureId],
    queryFn: ({ signal }) => setupApi.usage(period, captureId, signal),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
  const options =
    captures.data?.map((item) => ({ value: item.id, label: item.displayName ?? item.name })) ?? [];
  if (captureId && !options.some((item) => item.value === captureId))
    options.push({ value: captureId, label: captureId });
  function change(key: string, value: string | null) {
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (value) next.set(key, value);
      else next.delete(key);
      return next;
    });
  }
  return (
    <Stack gap="lg">
      <PageHeader
        title="API usage"
        description="See the requests, data and cache savings recorded by this application."
        actions={
          <Button variant="default" onClick={() => void usage.refetch()} loading={usage.isFetching}>
            Refresh usage
          </Button>
        }
      />
      <Group justify="space-between" align="flex-end">
        <Group aria-label="Usage period" gap="xs">
          {(Object.keys(PERIOD_LABELS) as UsagePeriod[]).map((value) => (
            <Button
              key={value}
              variant={period === value ? 'light' : 'default'}
              size="xs"
              aria-pressed={period === value}
              onClick={() => change('period', value)}
            >
              {PERIOD_LABELS[value]}
            </Button>
          ))}
        </Group>
        <Select
          label="For"
          searchable
          clearable
          placeholder="Everything"
          value={captureId}
          onChange={(value) => change('captureId', value)}
          data={options}
        />
      </Group>
      {captures.isError && (
        <Alert color="yellow">Capture choices could not be loaded. Usage is still available.</Alert>
      )}
      {usage.isPending ? (
        <LoadingState label="Loading API usage…" />
      ) : usage.isError ? (
        <ErrorState error={usage.error} onRetry={() => void usage.refetch()} />
      ) : (
        <UsageContent
          report={usage.data}
          period={period}
          updatedAt={usage.dataUpdatedAt}
          onShowDay={() => change('period', 'LAST_24_HOURS')}
        />
      )}
    </Stack>
  );
}

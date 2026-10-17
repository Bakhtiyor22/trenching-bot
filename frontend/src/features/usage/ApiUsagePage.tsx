import {
  Alert,
  Button,
  Group,
  ScrollArea,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { setupApi, type UsagePeriod, type UsageReport } from '../../api/setup';
import { Chart } from '../../components/Chart';
import { Timestamp } from '../../components/DisplayValues';
import { Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { purposeLabels, taskLabels } from '../../lib/codes';
import { useCaptures } from '../captures/captureQueries';
import { aggregateUsage, megabytes } from './usageData';

function UsageContent({ report, period }: { report: UsageReport; period: UsagePeriod }) {
  const points = aggregateUsage(report.series, period === 'LAST_HOUR' ? 300 : 3600);
  const times = [...new Set(points.map((point) => point.bucketStart))];
  const providers = [...new Set(points.map((point) => point.provider))];
  return (
    <Stack gap="lg">
      <Text size="sm">
        {new Date(report.from).toLocaleString()} — {new Date(report.to).toLocaleString()}.{' '}
        {report.recordingStartedAt
          ? `Recording started ${new Date(report.recordingStartedAt).toLocaleString()}.`
          : 'Recording has not started yet.'}
      </Text>
      <KpiStrip label="API usage totals">
        <Kpi label="Requests sent">{report.totals.requests.toLocaleString()} requests</Kpi>
        <Kpi label="Response data">{megabytes(report.totals.responseBytes)}</Kpi>
        <Kpi label="Rate limited">
          {report.totals.rateLimitedResponses.toLocaleString()} responses
        </Kpi>
        <Kpi label="Transaction cache hits">
          {report.totals.cacheHits.toLocaleString()} transactions
        </Kpi>
      </KpiStrip>
      {report.totals.requests === 0 && report.totals.cacheHits === 0 ? (
        <EmptyState
          title="No API usage in this period"
          description="Provider calls will appear here after a capture or an explicit activity lookup."
        />
      ) : (
        <Section title="Requests over time">
          <Chart
            ariaLabel="Provider requests over time"
            height={300}
            option={{
              tooltip: { trigger: 'axis', valueFormatter: (value) => `${String(value)} requests` },
              legend: { top: 0 },
              grid: { left: 50, right: 20, top: 45, bottom: 60 },
              xAxis: {
                type: 'category',
                data: times.map((time) => new Date(time).toLocaleString()),
              },
              yAxis: { type: 'value', minInterval: 1, name: 'Requests' },
              series: providers.map((provider) => ({
                name: provider,
                type: 'bar',
                stack: 'requests',
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
          <Table miw={950}>
            <Table.Thead>
              <Table.Tr>
                {[
                  'Provider / call',
                  'Used for',
                  'Requests',
                  'Share',
                  'Failed',
                  'Rate limited',
                  'Data',
                  'Last call',
                ].map((label) => (
                  <Table.Th key={label}>{label}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {report.byCall.map((call) => (
                <Table.Tr key={`${call.provider}/${call.operation}`}>
                  <Table.Td>
                    <Text size="sm" fw={600}>
                      {call.provider}
                    </Text>
                    <CodeText code={call.operation} />
                  </Table.Td>
                  <Table.Td>{purposeLabels[call.purpose]}</Table.Td>
                  <Table.Td>{call.totals.requests} requests</Table.Td>
                  <Table.Td>{call.sharePercent}%</Table.Td>
                  <Table.Td>{call.totals.failedRequests} requests</Table.Td>
                  <Table.Td>{call.totals.rateLimitedResponses} responses</Table.Td>
                  <Table.Td>{megabytes(call.totals.responseBytes)}</Table.Td>
                  <Table.Td>
                    <Timestamp iso={call.lastCallAt} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Section>
      <Section title="By task">
        <Stack gap="xs">
          {report.byTask.map((task) => (
            <Group key={task.task} justify="space-between">
              <Text size="sm">{taskLabels[task.task]}</Text>
              <Text size="sm" ff="monospace">
                {task.totals.requests.toLocaleString()} requests ·{' '}
                {megabytes(task.totals.responseBytes)}
              </Text>
            </Group>
          ))}
        </Stack>
      </Section>
      <Section title="Limits and units">
        <Stack gap="xs">
          <Text size="sm">
            1 request = one HTTP call sent. Retries count separately. Cache hits send no request. 1
            MB = 1,000,000 bytes. These counts are not billing credits.
          </Text>
          <Text size="sm">
            Gradle CLI captures are not counted. A hard process stop can lose the last 15 seconds of
            counts.
          </Text>
          {report.pacing.map((item) => (
            <Text key={item.provider} size="sm">
              {item.provider}: at least {item.minRequestIntervalMillis} ms between requests · up to{' '}
              {item.maxConcurrentRequests} concurrent requests
            </Text>
          ))}
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
  const captures = useCaptures(0);
  const usage = useQuery({
    queryKey: ['usage', period, captureId],
    queryFn: ({ signal }) => setupApi.usage(period, captureId, signal),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
  const options =
    captures.data?.items.map((item) => ({
      value: item.id,
      label: item.displayName ?? item.name,
    })) ?? [];
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
      <Group justify="space-between">
        <SegmentedControl
          aria-label="Usage period"
          value={period}
          onChange={(value) => change('period', value)}
          data={[
            { value: 'LAST_HOUR', label: '1 hour' },
            { value: 'LAST_24_HOURS', label: '24 hours' },
            { value: 'LAST_7_DAYS', label: '7 days' },
            { value: 'ALL', label: 'All time' },
          ]}
        />
        <Select
          label="Usage for"
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
        <UsageContent report={usage.data} period={period} />
      )}
    </Stack>
  );
}

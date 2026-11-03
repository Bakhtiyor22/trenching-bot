import { useMemo } from 'react';
import {
  Alert,
  Box,
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
import { setupApi, type UsagePeriod, type UsageProvider, type UsageReport } from '../../api/setup';
import { useChartTokens } from '../../charts/chartTokens';
import { Chart } from '../../components/Chart';
import { RelativeTime, Timestamp } from '../../components/DisplayValues';
import { IconInfo } from '../../components/Icons';
import { Meter } from '../../components/Meter';
import { Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { providerLabels, purposeLabels, taskLabels } from '../../lib/codes';
import { formatBytes, formatCount } from '../../lib/format';
import { bucketWord, buildUsageChartOption, providerColor, usageBucketSeconds } from './usageChart';
import { allCaptureChoices, groupUsageCalls, sortUsageTasks } from './usageData';

const PERIOD_LABELS: Record<UsagePeriod, string> = {
  LAST_HOUR: 'Last hour',
  LAST_24_HOURS: 'Last 24 hours',
  LAST_7_DAYS: 'Last 7 days',
  ALL: 'All recorded',
};
const PERIOD_OPTIONS = (Object.keys(PERIOD_LABELS) as UsagePeriod[]).map((value) => ({
  value,
  label: PERIOD_LABELS[value],
}));
const DEFAULT_PERIOD: UsagePeriod = 'LAST_HOUR';
const PERIOD_PARAM = 'period';
const CAPTURE_PARAM = 'captureId';
const FIVE_MINUTES = 300;
const HOUR = 3600;
const PROVIDER_NOTES: Partial<Record<UsageProvider, string>> = {
  SOLANA_RPC: 'your configured provider; address hidden because it can contain a key',
  BINANCE: 'public market data, no key',
};
const CALL_COLUMNS: Array<{ label: string; numeric?: boolean }> = [
  { label: 'API' },
  { label: 'Call' },
  { label: 'Used for' },
  { label: 'Requests', numeric: true },
  { label: 'Share', numeric: true },
  { label: 'Failed', numeric: true },
  { label: 'Rate-limited', numeric: true },
  { label: 'Data', numeric: true },
  { label: 'Last call' },
];

function isUsagePeriod(value: string | null): value is UsagePeriod {
  return value != null && Object.hasOwn(PERIOD_LABELS, value);
}

function bucketAdjective(seconds: number): string {
  return seconds === FIVE_MINUTES ? '5-minute' : seconds === HOUR ? 'hourly' : 'daily (UTC days)';
}

function RecordingSentence({ report }: { report: UsageReport }) {
  return report.recordingStartedAt ? (
    <>
      Recording started <Timestamp iso={report.recordingStartedAt} />; nothing before that is
      counted.
    </>
  ) : (
    <>Nothing has been recorded yet.</>
  );
}

function CallsTable({ report }: { report: UsageReport }) {
  const tokens = useChartTokens();
  const groups = groupUsageCalls(report.byCall);
  const notes = groups.filter((group) => PROVIDER_NOTES[group.provider]);
  return (
    <Section
      title="Calls by provider"
      hint={
        notes.length > 0 ? (
          <Stack gap={0} component="span">
            {notes.map((group) => (
              <span key={group.provider}>
                {providerLabels[group.provider]}: <span>{PROVIDER_NOTES[group.provider]}</span>
              </span>
            ))}
          </Stack>
        ) : undefined
      }
    >
      <ScrollArea>
        <Table miw={1100} verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              {CALL_COLUMNS.map((column, index) => (
                <Table.Th
                  key={column.label}
                  ta={column.numeric ? 'right' : undefined}
                  className={index === 0 ? 'console-sticky' : undefined}
                >
                  {column.label}
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {groups
              .flatMap((group) => group.calls)
              .map((call) => (
                <Table.Tr key={`${call.provider}/${call.operation}/${call.purpose}`}>
                  <Table.Td className="console-sticky" fw={500}>
                    {providerLabels[call.provider]}
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {call.operation}
                    </Text>
                  </Table.Td>
                  <Table.Td miw={290}>{purposeLabels[call.purpose]}</Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatCount(call.totals.requests)}
                  </Table.Td>
                  <Table.Td ta="right">
                    <Stack gap={4} align="flex-end">
                      <Text size="xs" ff="monospace">
                        {call.sharePercent}%
                      </Text>
                      <Box w={80}>
                        <Meter
                          label={`${providerLabels[call.provider]} ${call.operation}: share of requests`}
                          value={Number(call.sharePercent)}
                          max={100}
                          color={providerColor(call.provider, tokens)}
                        />
                      </Box>
                    </Stack>
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatCount(call.totals.failedRequests)}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatCount(call.totals.rateLimitedResponses)}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatBytes(call.totals.responseBytes)}
                  </Table.Td>
                  <Table.Td>
                    <Timestamp iso={call.lastCallAt} size="xs" ff="monospace" />
                  </Table.Td>
                </Table.Tr>
              ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </Section>
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
  const tokens = useChartTokens();
  // Built once per report: a new option object on every render would make the chart redraw.
  const chartOption = useMemo(() => buildUsageChartOption(report, period, tokens), [report, period, tokens]);
  const seconds = usageBucketSeconds(report, period);
  const unit = bucketWord(seconds);
  const periodText = PERIOD_LABELS[period].toLowerCase();
  const empty = report.totals.requests === 0 && report.totals.cacheHits === 0;
  const taskTotal = report.byTask.reduce((sum, task) => sum + task.totals.requests, 0);
  return (
    <Stack gap="lg">
      <Text size="sm">
        Showing <Timestamp iso={report.from} fw={600} /> → <Timestamp iso={report.to} fw={600} />{' '}
        (your local time), in {bucketAdjective(seconds)} buckets. Updated{' '}
        <RelativeTime iso={new Date(updatedAt).toISOString()} />.{' '}
        <RecordingSentence report={report} />
      </Text>
      <KpiStrip label="API usage totals">
        <Kpi label="Requests sent" hint={`HTTP calls, all APIs, ${periodText}`}>
          {formatCount(report.totals.requests)}
        </Kpi>
        <Kpi label="Data received" hint={`response bodies, ${periodText}`}>
          {formatBytes(report.totals.responseBytes)}
        </Kpi>
        <Kpi label="Rate-limited replies" hint="HTTP 429 answers, retried automatically">
          {formatCount(report.totals.rateLimitedResponses)}
        </Kpi>
        <Kpi label="Served from cache" hint="transactions, no request sent">
          {formatCount(report.totals.cacheHits)}
        </Kpi>
      </KpiStrip>
      {empty ? (
        <Section title={`Requests sent per ${unit}`}>
          <EmptyState
            title={`No calls in the ${periodText}`}
            description={<RecordingSentence report={report} />}
            action={
              period === 'LAST_HOUR' ? (
                <Button variant="default" onClick={onShowDay}>
                  Show last 24 hours
                </Button>
              ) : undefined
            }
          />
        </Section>
      ) : (
        <Section
          title={`Requests sent per ${unit}`}
          hint={`Every ${unit} of the period is on the axis, so a gap means no requests were sent then. Before recording started a bucket is "not recorded".`}
        >
          <Chart
            ariaLabel="Provider requests over time"
            height={300}
            option={chartOption}
          />
        </Section>
      )}
      <CallsTable report={report} />
      <Section title="By task">
        <Stack gap="md">
          {sortUsageTasks(report.byTask).map((task) => (
            <Stack gap={5} key={task.task}>
              <Group justify="space-between" gap="xs">
                <Text size="sm">{taskLabels[task.task]}</Text>
                <Text size="sm" ff="monospace">
                  {formatCount(task.totals.requests)} req
                </Text>
              </Group>
              <Meter
                label={`${taskLabels[task.task]}: requests of all tasks`}
                value={task.totals.requests}
                max={taskTotal}
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
                ? (1000 / item.minRequestIntervalMillis).toLocaleString('en-US', {
                    maximumFractionDigits: 3,
                  })
                : 'unlimited'}{' '}
              requests per second, {formatCount(item.maxConcurrentRequests)} at a time.
            </Text>
          ))}
          <Text size="sm">
            Requests are not billing credits. Some providers bill calls differently; check your
            provider's dashboard for credits used.
          </Text>
          <Text size="sm">
            1 request = one HTTP call sent. Retries count separately. Cache hits send no request. 1
            KB = 1,024 bytes and 1 MB = 1,024 KB.
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
  const selected = params.get(PERIOD_PARAM);
  const period: UsagePeriod = isUsagePeriod(selected) ? selected : DEFAULT_PERIOD;
  const captureId = params.get(CAPTURE_PARAM);
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
    <>
      <PageHeader
        title="API usage"
        description="See the requests, data and cache savings recorded by this application."
        actions={
          <Button variant="default" onClick={() => void usage.refetch()} loading={usage.isFetching}>
            Refresh usage
          </Button>
        }
      />
      <Stack gap="lg">
        <Group justify="space-between" align="flex-end" gap="sm">
          <ScrollArea type="never" maw="100%">
            <SegmentedControl
              aria-label="Usage period"
              size="xs"
              value={period}
              onChange={(value) => change(PERIOD_PARAM, value)}
              data={PERIOD_OPTIONS}
            />
          </ScrollArea>
          <Select
            label="For"
            searchable
            clearable
            placeholder="Everything"
            value={captureId}
            onChange={(value) => change(CAPTURE_PARAM, value)}
            data={options}
            w={{ base: '100%', sm: 260 }}
          />
        </Group>
        {captures.isError && (
          <Alert color="gray" variant="light" icon={<IconInfo />}>
            Capture choices could not be loaded. Usage is still available.
          </Alert>
        )}
        <QueryView query={usage} loadingLabel="Loading API usage…" errorTitle="Could not load API usage">
          {(report) => (
            <UsageContent
              report={report}
              period={period}
              updatedAt={usage.dataUpdatedAt}
              onShowDay={() => change(PERIOD_PARAM, 'LAST_24_HOURS')}
            />
          )}
        </QueryView>
      </Stack>
    </>
  );
}

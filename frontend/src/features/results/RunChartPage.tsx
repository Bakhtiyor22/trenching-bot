import {
  ActionIcon,
  Alert,
  Box,
  Button,
  Group,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { api, pairKey } from '../../api/endpoints';
import { useConfigDefaults, useRun } from '../../api/queries';
import { setupApi } from '../../api/setup';
import type { ChartResponse, Scenario } from '../../api/types';
import { marketRange, marketValue, shortPrice } from '../../charts/marketSummary';
import { chartExtent } from '../../charts/priceChart';
import { chartSearchParams, useChartView } from '../../charts/chartViewState';
import { exactUnits } from '../../charts/tradeWallets';
import { Section } from '../../components/Panels';
import { SourceLinks } from '../../components/SourceLinks';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { TradeChart } from '../../components/TradeChart';
import { formatDecimal, trimFractionZeros } from '../../lib/decimal';
import { mintLabel } from '../../lib/format';
import { formatPercent, levelPercent } from '../../lib/percent';
import { paths } from '../../paths';
import { changedSettings, SETTING_LABELS } from '../runs/configLabels';
import { formValuesFrom } from '../runs/runForm';
import { scenarioOutcome } from '../runs/scenarioOutcome';
import { clampRange, type TimeRange } from './chartRange';

function localInput(ms: number): string {
  const date = new Date(ms);
  return new Date(ms - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
}
function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
}
function exact(value: string): string {
  return formatDecimal(trimFractionZeros(value)).replace(/^-/, '−');
}
function RangeInputs({
  range,
  extent,
  onChange,
}: {
  range: TimeRange;
  extent: TimeRange;
  onChange: (range: TimeRange) => void;
}) {
  const [from, setFrom] = useState(localInput(range.from));
  const [to, setTo] = useState(localInput(range.to));
  const [error, setError] = useState('');
  function apply() {
    const next = { from: Date.parse(from), to: Date.parse(to) };
    if (
      !Number.isFinite(next.from) ||
      !Number.isFinite(next.to) ||
      next.from >= next.to ||
      next.from < extent.from ||
      next.to > extent.to
    ) {
      setError('Choose a valid range inside the recorded window.');
      return;
    }
    setError('');
    if (next.from !== range.from || next.to !== range.to) onChange(next);
  }
  return (
    <Group align="flex-start" gap="xs">
      <TextInput
        type="datetime-local"
        label="From (local time)"
        step="1"
        value={from}
        size="xs"
        w={205}
        onChange={(event) => setFrom(event.currentTarget.value)}
        onBlur={apply}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            apply();
          }
        }}
        error={error || undefined}
      />
      <TextInput
        type="datetime-local"
        label="To (local time)"
        step="1"
        value={to}
        size="xs"
        w={205}
        onChange={(event) => setTo(event.currentTarget.value)}
        onBlur={apply}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            apply();
          }
        }}
      />
    </Group>
  );
}
function scenarioEvents(
  scenario: Scenario | undefined,
  chart: ChartResponse | undefined,
  fullChart: ChartResponse | undefined,
) {
  if (!scenario?.timeline) return [];
  const buy = [...(chart?.markers.items ?? []), ...(fullChart?.markers.items ?? [])].find(
    (item) => item.isTrackedWallet && item.slot === scenario.sourceSlot && item.side === 'BUY',
  );
  const outcome = scenarioOutcome(scenario);
  const details = {
    firstBuyAt: [
      buy ? `${exact(exactUnits(buy.solLamports, 9))} SOL` : null,
      buy?.mcap ? `market cap ${marketValue(buy.mcap, 'mcap')}` : null,
      `dip level ${shortPrice(scenario.dipPrice)}`,
    ]
      .filter(Boolean)
      .join(' · '),
    dipAt: `Price reached ${shortPrice(scenario.dipPrice)}${scenario.trigger ? ` · market cap ${marketValue(scenario.trigger.marketCapUsd, 'mcap')}` : ''}`,
    entryAt: `${exact(scenario.notional)} SOL${scenario.fill ? ` · fees ${levelPercent(scenario.fill.feePercent, scenario.fill.feeBps)} · impact ${levelPercent(scenario.fill.priceImpactPercent, scenario.fill.priceImpactBps)}` : ''}`,
    exitTriggerAt:
      scenario.position?.exitTrigger?.reason === 'TAKE_PROFIT'
        ? 'Price crossed the take-profit level.'
        : 'Price crossed the stop-loss level.',
    exitAt:
      outcome.pnl !== null
        ? `Net result ${exact(outcome.pnl)} SOL`
        : 'No realized result recorded.',
  };
  // The short labels above cut tiny prices; the exact values stay one hover away.
  const exactDetails: Partial<Record<keyof typeof details, string>> = {
    firstBuyAt: `Exact dip level ${exact(scenario.dipPrice)} SOL`,
    dipAt: `Exact price ${exact(scenario.dipPrice)} SOL${scenario.trigger ? ` · market cap $${exact(scenario.trigger.marketCapUsd)}` : ''}`,
  };
  const labels = {
    firstBuyAt: 'Decu bought',
    dipAt: 'Dip reached',
    entryAt: 'Paper buy filled',
    exitTriggerAt:
      scenario.position?.exitTrigger?.reason === 'TAKE_PROFIT'
        ? 'Take profit crossed'
        : 'Stop loss crossed',
    exitAt: 'Paper sell filled',
  };
  return (Object.entries(scenario.timeline) as Array<[keyof typeof labels, string | null]>)
    .filter((entry): entry is [keyof typeof labels, string] => entry[1] !== null)
    .map(([name, at]) => ({
      name,
      at,
      label: labels[name],
      detail: details[name],
      exactDetail: exactDetails[name],
    }));
}

export function RunChartPage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const run = useRun(id);
  const preset = useQuery({
    queryKey: ['run-preset'],
    queryFn: ({ signal }) => setupApi.preset(signal),
    retry: false,
    staleTime: Infinity,
  });
  const legacyDefaults = useConfigDefaults();
  const pairs = [
    ...new Map(
      (run.data?.scenarios ?? []).map((scenario) => [pairKey(scenario.pair), scenario.pair]),
    ).values(),
  ];
  const pair = pairs.find((item) => pairKey(item) === params.get('pair')) ?? pairs[0];
  const key = pair ? pairKey(pair) : '';
  const full = useQuery({
    queryKey: ['run', id, 'chart-extent', key],
    queryFn: ({ signal }) =>
      api.runChart(id, { pair: key, maxBuckets: 2000, tickLimit: 5000 }, signal),
    enabled: !!key,
    staleTime: Infinity,
  });
  const scenario = run.data?.scenarios.find((item) => pairKey(item.pair) === key);
  const extent: TimeRange | null = full.data ? chartExtent(full.data) : null;
  const cycleRange = full.data ? marketRange(full.data, 'cycle', scenario?.sourceSlot) : null;
  const initialRange = cycleRange
    ? { from: Date.parse(cycleRange.from), to: Date.parse(cycleRange.to) }
    : extent;
  const chartView = useChartView({ kind: 'run', extent, resetRange: initialRange });
  const range = chartView.window;
  const [latestSnapshot, setLatestSnapshot] = useState<{ pair: string; data: ChartResponse }>();
  const latestChart = latestSnapshot?.pair === key ? latestSnapshot.data : undefined;
  const onChartData = useCallback(
    (data: ChartResponse, chartPair: string) => setLatestSnapshot({ pair: chartPair, data }),
    [],
  );
  function chooseToken(index: number) {
    const nextPair = pairs[index];
    if (!nextPair) return;
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.set('pair', pairKey(nextPair));
      next.delete('from');
      next.delete('to');
      next.delete('action');
      next.delete('fill');
      return next;
    });
  }
  if (run.isPending) return <LoadingState label="Loading run chart…" />;
  if (run.isError) return <ErrorState error={run.error} onRetry={() => void run.refetch()} />;
  const changes = preset.data
    ? changedSettings(formValuesFrom(run.data.config, null), preset.data.config)
    : [];
  const runValues = formValuesFrom(run.data.config, null);
  const oldDefaults =
    legacyDefaults.data !== undefined &&
    changedSettings(runValues, legacyDefaults.data.defaults).length === 0;
  const settings = oldDefaults
    ? `Old defaults (${formatPercent(runValues.dipPercent)} dip, +${formatPercent(runValues.takeProfitPercent)} / −${formatPercent(runValues.stopLossPercent)})`
    : preset.data
      ? changes.length === 0
        ? 'Agreed strategy'
        : `Agreed strategy, ${changes.length} changes (${changes.map((field) => SETTING_LABELS[field]).join(', ')})`
      : 'Custom settings';
  const outcome = scenario ? scenarioOutcome(scenario) : null;
  const tokenIndex = pairs.findIndex((item) => pairKey(item) === key);
  const events = scenarioEvents(scenario, latestChart, full.data);
  return (
    <Stack gap="lg">
      <SourceLinks source={run.data.source} runId={id} chart />
      <Section title="Results chart">
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
          <Stack gap={4}>
            <Text size="xs" c="dimmed">
              Token
            </Text>
            <Text size="sm">{pair ? `${mintLabel(pair.base)} / SOL` : 'No token'}</Text>
          </Stack>
          <Stack gap={4}>
            <Text size="xs" c="dimmed">
              Result
            </Text>
            <Text size="sm">
              {outcome?.label ?? 'No result'}
              {outcome?.pnl != null && ` · ${exact(outcome.pnl)} SOL`}
            </Text>
          </Stack>
          <Stack gap={4}>
            <Text size="xs" c="dimmed">
              Settings
            </Text>
            <Text size="sm">{settings}</Text>
          </Stack>
          <Stack gap={4}>
            <Text size="xs" c="dimmed">
              Simulator
            </Text>
            <Text size="sm">
              {run.data.config.strategyName} v{run.data.config.strategyVersion} · run{' '}
              {new Date(run.data.run.createdAt).toLocaleString('en-GB', {
                day: 'numeric',
                month: 'short',
                hour: '2-digit',
                minute: '2-digit',
                hour12: false,
              })}
            </Text>
          </Stack>
        </SimpleGrid>
        <Group justify="space-between" mt="md" gap="xs">
          <Group gap="xs" aria-label="Token stepper">
            <ActionIcon
              variant="default"
              aria-label="Previous token"
              disabled={tokenIndex <= 0}
              onClick={() => chooseToken(tokenIndex - 1)}
            >
              ‹
            </ActionIcon>
            <Text size="sm">
              Token {pairs.length ? tokenIndex + 1 : 0} of {pairs.length}
            </Text>
            <ActionIcon
              variant="default"
              aria-label="Next token"
              disabled={tokenIndex >= pairs.length - 1}
              onClick={() => chooseToken(tokenIndex + 1)}
            >
              ›
            </ActionIcon>
          </Group>
          <Group gap="xs">
            <Button
              component={Link}
              to={`${paths.run(id)}${chartSearchParams(params, key)}`}
              variant="default"
            >
              ← Back to run
            </Button>
            {run.data.source?.captureId && (
              <Button
                component={Link}
                to={paths.capture(run.data.source.captureId)}
                variant="subtle"
              >
                Open capture
              </Button>
            )}
          </Group>
        </Group>
      </Section>
      {!key ? (
        <Alert>No tokens in this run.</Alert>
      ) : full.isPending ? (
        <LoadingState label="Loading recorded window…" />
      ) : full.isError ? (
        <ErrorState error={full.error} onRetry={() => void full.refetch()} />
      ) : (
        range &&
        extent &&
        initialRange && (
          <>
            <Stack gap="sm">
              <RangeInputs
                key={`${range.from}/${range.to}`}
                range={range}
                extent={extent}
                onChange={chartView.setWindow}
              />
              <TradeChart
                kind="run"
                id={id}
                pair={key}
                trackedWallet={full.data?.trackedWallet ?? null}
                initialChart={full.data}
                scenarios={run.data.scenarios}
                cycle={scenario}
                onData={onChartData}
                emptyActions={
                  <Group>
                    <Button variant="default" onClick={chartView.reset}>
                      Reset
                    </Button>
                    <Button onClick={() => chartView.zoom(2)}>Zoom out</Button>
                  </Group>
                }
                height="max(480px, 62vh)"
                expanded
              />
              <Text size="xs" c="dimmed">
                Wheel to zoom · drag to pan · double-click to fit · focus the chart and press + / −,
                ← / →, 0 to reset, or F to fit.
              </Text>
            </Stack>
            <Section title="What happened · click to jump">
              <Stack component="ol" gap="xs" m={0} pl="lg">
                {events.map((event) => (
                  <Box component="li" key={event.name}>
                    <UnstyledButton
                      w="100%"
                      py="xs"
                      onClick={() =>
                        chartView.setWindow(
                          clampRange(
                            {
                              from: Date.parse(event.at) - 15_000,
                              to: Date.parse(event.at) + 15_000,
                            },
                            extent,
                          ),
                        )
                      }
                    >
                      <Group gap="sm">
                        <Text size="xs" ff="monospace">
                          {clock(event.at)}
                        </Text>
                        <Text size="sm" fw={600}>
                          {event.label}
                        </Text>
                      </Group>
                      <Text size="xs" c="dimmed" mt={4} title={event.exactDetail}>
                        {event.detail}
                      </Text>
                    </UnstyledButton>
                  </Box>
                ))}
              </Stack>
              {!events.length && (
                <Text size="sm">No event times were recorded for this cycle.</Text>
              )}
            </Section>
            <Section title="How to read this">
              <Stack gap="xs">
                <Text size="sm">
                  B: Decu's own buy (observed on chain). Gold diamonds: our simulated paper trades,
                  never real.
                </Text>
                <Text size="sm">
                  Market cap = price × total supply × SOL/USDT at that minute. USDT stands in for
                  USD.
                </Text>
                <Text size="sm">
                  Candles group trades into {latestChart?.bucketSeconds ?? full.data?.bucketSeconds}
                  -second buckets at this zoom; zooming in shows finer buckets.
                </Text>
              </Stack>
            </Section>
          </>
        )
      )}
    </Stack>
  );
}

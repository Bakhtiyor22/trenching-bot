import { ActionIcon, Box, Button, Group, Stack, Text, TextInput, UnstyledButton } from '@mantine/core';
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
import { Pnl, Timestamp } from '../../components/DisplayValues';
import { IconChevronLeft, IconChevronRight } from '../../components/Icons';
import { Kpi, KpiStrip, PageHeader, Section } from '../../components/Panels';
import { RefreshNotice } from '../../components/QueryView';
import { sourceCrumbs } from '../runs/sourceCrumbs';
import { StatusChip } from '../../components/StatusChip';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { TradeChart } from '../../components/TradeChart';
import { formatDecimal, formatSignedDecimal, trimFractionZeros } from '../../lib/decimal';
import { mintLabel, shortId } from '../../lib/format';
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
        w={{ base: '100%', xs: 205 }}
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
        w={{ base: '100%', xs: 205 }}
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
/** How each exit trigger reads in the event list; anything but take profit used to read as stop loss. */
const EXIT_TRIGGER_TEXT: Record<string, { label: string; detail: string }> = {
  TAKE_PROFIT: { label: 'Take profit crossed', detail: 'Price crossed the take-profit level.' },
  STOP_LOSS: { label: 'Stop loss crossed', detail: 'Price crossed the stop-loss level.' },
  TIME_EXIT: {
    label: 'Time exit started',
    detail: 'The exit clock ran out: sold against the curve standing then.',
  },
  WALLET_SOLD: { label: 'Decu sold', detail: 'Decu sold the token: our whole position is sold.' },
};

/** The block time of a recorded trade, from the chart's trades or wallet markers; undefined when not loaded. */
function observationTime(observationId: string, charts: Array<ChartResponse | undefined>) {
  for (const chart of charts) {
    const found =
      chart?.markers.items.find((item) => item.observationId === observationId) ??
      chart?.ticks.items.find((item) => item.observationId === observationId);
    if (found) return found.time;
  }
  return undefined;
}

function scenarioEvents(
  scenario: Scenario | undefined,
  chart: ChartResponse | undefined,
  fullChart: ChartResponse | undefined,
) {
  if (!scenario?.timeline) return [];
  const charts = [chart, fullChart];
  const buy = [...(chart?.markers.items ?? []), ...(fullChart?.markers.items ?? [])].find(
    (item) => item.isTrackedWallet && item.slot === scenario.sourceSlot && item.side === 'BUY',
  );
  const outcome = scenarioOutcome(scenario);
  const position = scenario.position;
  const triggerReason = position?.exitTrigger?.reason;
  const standing = position?.close?.priceAssumption === 'STANDING_CURVE';
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
    exitTriggerAt: EXIT_TRIGGER_TEXT[triggerReason ?? '']?.detail ?? 'The exit was triggered.',
    exitAt: `${
      outcome.pnl !== null
        ? `Net result ${formatSignedDecimal(outcome.pnl, { trim: true })} SOL`
        : 'No realized result recorded.'
    }${standing ? ' · sold against the standing curve' : ''}`,
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
    exitTriggerAt: EXIT_TRIGGER_TEXT[triggerReason ?? '']?.label ?? 'Exit triggered',
    exitAt: 'Paper sell filled',
  };
  // A Decu-sold exit is placed at his sell: its trigger slot and time are our fill's when he sold before our buy landed.
  const times: Record<keyof typeof labels, string | null> = {
    ...scenario.timeline,
    exitTriggerAt:
      triggerReason === 'WALLET_SOLD' && position?.exitTrigger
        ? (observationTime(position.exitTrigger.observationId, charts) ??
          scenario.timeline.exitTriggerAt)
        : scenario.timeline.exitTriggerAt,
  };
  const events: Array<{
    name: string;
    at: string;
    label: string;
    detail: string;
    exactDetail?: string;
  }> = (Object.entries(times) as Array<[keyof typeof labels, string | null]>)
    .filter((entry): entry is [keyof typeof labels, string] => entry[1] !== null)
    .map(([name, at]) => ({
      name,
      at,
      label: labels[name],
      detail: details[name],
      exactDetail: exactDetails[name],
    }));
  const sellAt = scenario.walletSell && observationTime(scenario.walletSell.eventId, charts);
  if (sellAt)
    events.push({
      name: 'walletSellAt',
      at: sellAt,
      label: 'Decu sold before the dip',
      detail: 'No entry: Decu sold the token before its price reached the dip level.',
    });
  // In time order: Decu's sell can come before our fill landed.
  return events.sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
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
  // A failed refresh keeps the chart; the full error only shows when the run never loaded.
  if (!run.data) {
    return run.isError ? (
      <ErrorState error={run.error} onRetry={() => void run.refetch()} />
    ) : (
      <LoadingState label="Loading run chart…" />
    );
  }
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
  const tokenTitle = pair ? `${mintLabel(pair.base)} / SOL` : 'No token';
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[
          { label: 'Runs', to: paths.runs },
          ...sourceCrumbs(run.data.source),
          { label: `Run ${shortId(id)}`, to: paths.run(id) },
        ]}
        title={`Chart · ${tokenTitle}`}
        status={outcome && <StatusChip tone={outcome.tone} glyph={outcome.glyph}>{outcome.label}</StatusChip>}
        description="Market cap and trades of one token in this run, with Decu's buys and our paper fills."
        actions={
          <>
            <Group gap={6} wrap="nowrap" role="group" aria-label="Token stepper">
              <ActionIcon
                variant="default"
                size="lg"
                aria-label="Previous token"
                disabled={tokenIndex <= 0}
                onClick={() => chooseToken(tokenIndex - 1)}
              >
                <IconChevronLeft size={16} />
              </ActionIcon>
              <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
                Token {pairs.length ? tokenIndex + 1 : 0} of {pairs.length}
              </Text>
              <ActionIcon
                variant="default"
                size="lg"
                aria-label="Next token"
                disabled={tokenIndex >= pairs.length - 1}
                onClick={() => chooseToken(tokenIndex + 1)}
              >
                <IconChevronRight size={16} />
              </ActionIcon>
            </Group>
            <Button
              component={Link}
              to={`${paths.run(id)}${chartSearchParams(params, key)}`}
              variant="default"
              leftSection={<IconChevronLeft size={16} />}
            >
              Back to run
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
          </>
        }
      />
      {run.isError && (
        <RefreshNotice error={run.error} updatedAt={run.dataUpdatedAt} onRetry={() => void run.refetch()} />
      )}
      <KpiStrip label="This token">
        <Kpi label="Token" size="sm">
          {tokenTitle}
        </Kpi>
        <Kpi label="Result" size="sm" hint={outcome?.label ?? 'No result'}>
          {outcome?.pnl != null ? (
            <Pnl value={outcome.pnl} trim unit="SOL" fz="inherit" fw={500} />
          ) : (
            <Text span c="dimmed" inherit>
              —
            </Text>
          )}
        </Kpi>
        <Kpi label="Settings" size="sm" text>
          <Text span inherit style={{ fontFamily: 'var(--mantine-font-family)' }}>
            {settings}
          </Text>
        </Kpi>
        <Kpi
          label="Simulator"
          size="sm"
          hint={
            <>
              Run created <Timestamp iso={run.data.run.createdAt} size="xs" c="dimmed" />
            </>
          }
        >
          {run.data.config.strategyName} v{run.data.config.strategyVersion}
        </Kpi>
      </KpiStrip>
      {!key ? (
        <Section title="Chart">
          <EmptyState
            title="No tokens in this run"
            description="The dataset had no confirmed buys against the quote mint, so there is nothing to chart."
            action={
              <Button component={Link} to={paths.run(id)} variant="default">
                Back to run
              </Button>
            }
          />
        </Section>
      ) : full.data === undefined ? (
        full.isError ? (
          <ErrorState error={full.error} onRetry={() => void full.refetch()} />
        ) : (
          <LoadingState label="Loading recorded window…" />
        )
      ) : (
        range &&
        extent &&
        initialRange && (
          <>
            <Stack gap="sm">
              {full.isError && (
                <RefreshNotice error={full.error} updatedAt={full.dataUpdatedAt} onRetry={() => void full.refetch()} />
              )}
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
            <Section title="What happened" hint="Click an event to jump the chart to it.">
              <Stack component="ol" gap={2} m={0} p={0} style={{ listStyle: 'none' }}>
                {events.map((event) => (
                  <Box component="li" key={event.name}>
                    <UnstyledButton
                      className="console-row-button"
                      px="sm"
                      py="xs"
                      style={{ borderRadius: 6 }}
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
                        <Timestamp iso={event.at} size="xs" ff="monospace" c="dimmed" />
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
                <EmptyState
                  title="No event times"
                  description="No event times were recorded for this cycle."
                />
              )}
            </Section>
            <Section title="How to read this">
              <Stack gap="xs">
                <Text size="sm">
                  Amber B and S pins: Decu's own buys and sells (observed on chain). Blue diamonds:
                  our simulated paper trades, never real.
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

import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Group,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { api, pairKey } from '../../api/endpoints';
import { useConfigDefaults, useRun } from '../../api/queries';
import { setupApi } from '../../api/setup';
import type { ChartResponse, Scenario } from '../../api/types';
import { marketValue, shortPrice } from '../../charts/marketSummary';
import { exactUnits } from '../../charts/tradeWallets';
import { Section } from '../../components/Panels';
import { SourceLinks } from '../../components/SourceLinks';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { TradeChart } from '../../components/TradeChart';
import { formatDecimal, trimFractionZeros } from '../../lib/decimal';
import { mintLabel } from '../../lib/format';
import { formatPercent, levelPercent } from '../../lib/percent';
import { paths } from '../../paths';
import { changedSettings, NOMINAL_SLOT_MS, SETTING_LABELS } from '../runs/configLabels';
import { formValuesFrom } from '../runs/runForm';
import { scenarioOutcome } from '../runs/scenarioOutcome';
import { clampRange, panRange, tradeRange, zoomRange, type TimeRange } from './chartRange';

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
    queryFn: ({ signal }) => api.runChart(id, { pair: key, maxBuckets: 1, markerLimit: 1 }, signal),
    enabled: !!key,
    staleTime: Infinity,
  });
  const scenario = run.data?.scenarios.find((item) => pairKey(item.pair) === key);
  const extentValue = full.data?.extent;
  const extent: TimeRange | null = full.data
    ? {
        from: Date.parse(extentValue?.firstTime ?? full.data.range.from),
        to: Date.parse(extentValue?.lastTime ?? full.data.range.to),
      }
    : null;
  const firstBuy = Date.parse(scenario?.timeline?.firstBuyAt ?? full.data?.range.from ?? '');
  const end =
    Date.parse(scenario?.timeline?.exitAt ?? '') ||
    (extent && scenario
      ? Math.min(
          extent.to,
          firstBuy +
            Math.max(0, scenario.horizonEndSlot - scenario.sourceSlot) *
              (run.data?.millisPerSlot ?? NOMINAL_SLOT_MS),
        )
      : (extent?.to ?? 0));
  const initialRange =
    extent && Number.isFinite(firstBuy) ? tradeRange(firstBuy, end, extent) : extent;
  const urlFrom = Date.parse(params.get('from') ?? '');
  const urlTo = Date.parse(params.get('to') ?? '');
  const range =
    extent && Number.isFinite(urlFrom) && Number.isFinite(urlTo) && urlFrom < urlTo
      ? clampRange({ from: urlFrom, to: urlTo }, extent)
      : initialRange;
  const [debouncedFrom] = useDebouncedValue(range?.from, 300);
  const [debouncedTo] = useDebouncedValue(range?.to, 300);
  const debouncedRange =
    debouncedFrom !== undefined && debouncedTo !== undefined
      ? { from: debouncedFrom, to: debouncedTo }
      : null;
  const view = params.get('view') === 'price' ? 'price' : 'mcap';
  const host = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [latestSnapshot, setLatestSnapshot] = useState<{ pair: string; data: ChartResponse }>();
  const latestChart = latestSnapshot?.pair === key ? latestSnapshot.data : undefined;
  const onChartData = useCallback(
    (data: ChartResponse, chartPair: string) => setLatestSnapshot({ pair: chartPair, data }),
    [],
  );
  useEffect(() => {
    const changed = () => {
      if (!document.fullscreenElement) setFullscreen(false);
    };
    document.addEventListener('fullscreenchange', changed);
    return () => document.removeEventListener('fullscreenchange', changed);
  }, []);
  function writeRange(next: TimeRange) {
    setParams(
      (current) => {
        const nextParams = new URLSearchParams(current);
        nextParams.set('from', new Date(next.from).toISOString());
        nextParams.set('to', new Date(next.to).toISOString());
        if (key) nextParams.set('pair', key);
        return nextParams;
      },
      { replace: true },
    );
  }
  function move(kind: 'in' | 'out' | 'left' | 'right' | 'reset') {
    if (!range || !extent || !initialRange) return;
    writeRange(
      kind === 'reset'
        ? initialRange
        : kind === 'left' || kind === 'right'
          ? panRange(range, kind === 'left' ? -0.25 : 0.25, extent)
          : zoomRange(range, kind === 'in' ? 0.5 : 2, extent),
    );
  }
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
  async function toggleFullscreen() {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
      setFullscreen(false);
    } else if (host.current?.requestFullscreen) {
      try {
        await host.current.requestFullscreen();
        setFullscreen(true);
      } catch {
        setFullscreen((value) => !value);
      }
    } else setFullscreen((value) => !value);
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
  const presets =
    extent && initialRange
      ? [
          { label: 'This trade', range: initialRange },
          ...[1, 5, 15].map((minutes) => ({
            label: `${minutes}m`,
            range: clampRange({ from: firstBuy, to: firstBuy + minutes * 60_000 }, extent),
          })),
          { label: 'Whole window', range: extent },
        ]
      : [];
  const active =
    range?.from === extent?.from && range?.to === extent?.to
      ? 'Whole window'
      : presets.find((item) => item.range.from === range?.from && item.range.to === range?.to)
          ?.label;
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
            <Button component={Link} to={paths.run(id)} variant="default">
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
            <Stack
              ref={host}
              gap="sm"
              p={fullscreen ? 'md' : 0}
              style={
                fullscreen
                  ? {
                      position: 'fixed',
                      inset: 0,
                      zIndex: 300,
                      background: 'var(--mantine-color-body)',
                      overflow: 'auto',
                    }
                  : { background: 'var(--mantine-color-body)' }
              }
              onKeyDown={(event) => {
                if (event.target instanceof HTMLInputElement) return;
                const mapping: Record<string, 'in' | 'out' | 'left' | 'right' | 'reset'> = {
                  '+': 'in',
                  '=': 'in',
                  '-': 'out',
                  ArrowLeft: 'left',
                  ArrowRight: 'right',
                  '0': 'reset',
                };
                const action = mapping[event.key];
                if (action) {
                  event.preventDefault();
                  move(action);
                }
                if (event.key === 'Escape') setFullscreen(false);
              }}
              tabIndex={0}
              aria-label="Interactive results chart"
            >
              <Group gap="xs" align="flex-end" wrap="wrap">
                <Group gap={4} aria-label="Chart range presets">
                  {presets.map((item) => (
                    <Button
                      key={item.label}
                      variant={active === item.label ? 'light' : 'default'}
                      size="xs"
                      aria-pressed={active === item.label}
                      onClick={() => writeRange(item.range)}
                    >
                      {item.label}
                    </Button>
                  ))}
                  {!active && <Badge variant="outline">Custom</Badge>}
                </Group>
                <RangeInputs
                  key={`${range.from}/${range.to}`}
                  range={range}
                  extent={extent}
                  onChange={writeRange}
                />
                <Group gap={4}>
                  <ActionIcon variant="default" aria-label="Zoom in" onClick={() => move('in')}>
                    +
                  </ActionIcon>
                  <ActionIcon variant="default" aria-label="Zoom out" onClick={() => move('out')}>
                    −
                  </ActionIcon>
                  <ActionIcon variant="default" aria-label="Pan left" onClick={() => move('left')}>
                    ←
                  </ActionIcon>
                  <ActionIcon
                    variant="default"
                    aria-label="Pan right"
                    onClick={() => move('right')}
                  >
                    →
                  </ActionIcon>
                  <Button variant="light" size="xs" onClick={() => move('reset')}>
                    Reset view
                  </Button>
                </Group>
              </Group>
              <TradeChart
                kind="run"
                id={id}
                pair={key}
                trackedWallet={full.data?.trackedWallet ?? null}
                scenarios={run.data.scenarios}
                cycle={scenario}
                range={
                  debouncedRange
                    ? {
                        from: new Date(debouncedRange.from).toISOString(),
                        to: new Date(debouncedRange.to).toISOString(),
                      }
                    : undefined
                }
                view={view}
                onData={onChartData}
                fullscreen={fullscreen}
                onToggleFullscreen={() => void toggleFullscreen()}
                onViewChange={(value) =>
                  setParams(
                    (current) => {
                      const next = new URLSearchParams(current);
                      next.set('view', value);
                      return next;
                    },
                    { replace: true },
                  )
                }
                onRangeChange={(next) =>
                  writeRange(
                    clampRange({ from: Date.parse(next.from), to: Date.parse(next.to) }, extent),
                  )
                }
                emptyActions={
                  <Group>
                    <Button variant="default" onClick={() => move('reset')}>
                      Reset view
                    </Button>
                    <Button onClick={() => move('out')}>Zoom out</Button>
                  </Group>
                }
                height="max(480px, 62vh)"
                expanded
              />
              <Text size="xs" c="dimmed">
                Wheel to zoom · drag to pan · use the navigator below · focus the chart and press +
                / −, ← / →, or 0 to reset.
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
                        writeRange(
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

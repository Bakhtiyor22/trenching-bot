import { Alert, Button, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { pairKey } from '../../api/endpoints';
import { useRun, useRunChart } from '../../api/queries';
import { PageHeader, Section } from '../../components/Panels';
import { SourceLinks } from '../../components/SourceLinks';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { TradeChart } from '../../components/TradeChart';
import { mintLabel } from '../../lib/format';
import { paths } from '../../paths';
import { clampRange, panRange, tradeRange, zoomRange, type TimeRange } from './chartRange';

function localInput(ms: number): string {
  const date = new Date(ms);
  return new Date(ms - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
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
    onChange(next);
  }
  return (
    <Group align="flex-start">
      <TextInput
        type="datetime-local"
        label="From (local time)"
        value={from}
        onChange={(event) => setFrom(event.currentTarget.value)}
        error={error || undefined}
      />
      <TextInput
        type="datetime-local"
        label="To (local time)"
        step="1"
        value={to}
        onChange={(event) => setTo(event.currentTarget.value)}
      />
      <Button mt={24} variant="default" onClick={apply}>
        Apply range
      </Button>
    </Group>
  );
}
export function RunChartPage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const run = useRun(id);
  const pairs = [
    ...new Map(
      (run.data?.scenarios ?? []).map((scenario) => [pairKey(scenario.pair), scenario.pair]),
    ).values(),
  ];
  const pair = pairs.find((item) => pairKey(item) === params.get('pair')) ?? pairs[0];
  const key = pair ? pairKey(pair) : '';
  const full = useRunChart(id, { pair: key }, !!key);
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
          firstBuy + Math.max(0, scenario.horizonEndSlot - scenario.sourceSlot) * 400,
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
  const rangeFrom = range?.from;
  const rangeTo = range?.to;
  const [debouncedFrom] = useDebouncedValue(rangeFrom, 300);
  const [debouncedTo] = useDebouncedValue(rangeTo, 300);
  const debouncedRange =
    debouncedFrom !== undefined && debouncedTo !== undefined
      ? { from: debouncedFrom, to: debouncedTo }
      : null;
  const view = params.get('view') === 'price' ? 'price' : 'mcap';
  const host = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
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
  return (
    <Stack gap="lg">
      <SourceLinks source={run.data.source} runId={id} chart />
      <PageHeader
        title="Results chart"
        description="Explore recorded trades and the simulated entry and exit."
        actions={
          <Button component={Link} to={paths.run(id)} variant="default">
            Back to run
          </Button>
        }
      />
      <Select
        label="Token"
        value={key || null}
        onChange={(value) =>
          setParams((current) => {
            const next = new URLSearchParams(current);
            if (value) next.set('pair', value);
            next.delete('from');
            next.delete('to');
            return next;
          })
        }
        data={pairs.map((item) => ({
          value: pairKey(item),
          label: `${mintLabel(item.base)} / ${mintLabel(item.quote)}`,
        }))}
      />
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
              <Group gap="xs">
                {['This trade', '1m', '5m', '15m', 'Whole window'].map((label) => (
                  <Button
                    key={label}
                    variant="default"
                    size="xs"
                    onClick={() =>
                      writeRange(
                        label === 'This trade'
                          ? initialRange
                          : label === 'Whole window'
                            ? extent
                            : clampRange(
                                { from: firstBuy, to: firstBuy + parseInt(label) * 60_000 },
                                extent,
                              ),
                      )
                    }
                  >
                    {label}
                  </Button>
                ))}
                <Button variant="light" size="xs" onClick={() => move('in')} aria-label="Zoom in">
                  Zoom +
                </Button>
                <Button variant="light" size="xs" onClick={() => move('out')} aria-label="Zoom out">
                  Zoom −
                </Button>
                <Button
                  variant="light"
                  size="xs"
                  onClick={() => move('left')}
                  aria-label="Pan left"
                >
                  ←
                </Button>
                <Button
                  variant="light"
                  size="xs"
                  onClick={() => move('right')}
                  aria-label="Pan right"
                >
                  →
                </Button>
                <Button variant="light" size="xs" onClick={() => move('reset')}>
                  Reset view
                </Button>
                <Button variant="subtle" size="xs" onClick={() => void toggleFullscreen()}>
                  {fullscreen ? 'Exit full screen' : 'Full screen'}
                </Button>
              </Group>
              <RangeInputs
                key={`${range.from}/${range.to}`}
                range={range}
                extent={extent}
                onChange={writeRange}
              />
              <TradeChart
                kind="run"
                id={id}
                pair={key}
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
                height="max(480px, 62vh)"
                expanded
              />
              <Text size="xs" c="dimmed">
                Wheel to zoom · drag to pan · use the navigator below · focus the chart and press +
                / −, ← / →, or 0 to reset.
              </Text>
            </Stack>
            <Section title="What happened · click to jump">
              <Group>
                {Object.entries(scenario?.timeline ?? {}).map(
                  ([name, at]) =>
                    at && (
                      <Button
                        key={name}
                        variant="subtle"
                        onClick={() =>
                          writeRange(
                            clampRange(
                              { from: Date.parse(at) - 15_000, to: Date.parse(at) + 15_000 },
                              extent,
                            ),
                          )
                        }
                      >
                        {
                          (
                            {
                              firstBuyAt: 'Reference buy',
                              dipAt: 'Dip triggered',
                              entryAt: 'Paper entry',
                              exitTriggerAt: 'Exit triggered',
                              exitAt: 'Paper exit',
                            } as Record<string, string>
                          )[name]
                        }{' '}
                        · {new Date(at).toLocaleTimeString()}
                      </Button>
                    ),
                )}
              </Group>
              {!scenario?.timeline && (
                <Text size="sm">No event times were recorded for this cycle.</Text>
              )}
            </Section>
            <Section title="How to read this">
              <Text size="sm">
                B and S identify the tracked wallet’s recorded trades. Diamonds are simulated fills.
                Price levels come from this run’s configuration; missing observations stay gaps.
              </Text>
            </Section>
          </>
        )
      )}
    </Stack>
  );
}

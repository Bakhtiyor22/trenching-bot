import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ChartResponse } from '../api/types';
import { server } from '../mocks/server';
import { renderPage } from '../test/render';
import { TradeChart } from './TradeChart';
import type { ChartProps } from './Chart';
import { terminal } from '../theme';

const renderedChart = vi.hoisted(() => ({ current: null as ChartProps | null }));
vi.mock('./Chart', async () => {
  const { useEffect } = await import('react');
  return {
    Chart: (props: ChartProps) => {
      renderedChart.current = props;
      const { onReady } = props;
      useEffect(() => {
        const noop = () => {};
        onReady?.({
          convertToPixel: (_finder: unknown, values: number[]) => [
            (values[0]! - Date.parse(time)) / 25 + 40,
            values[1]! >= 1 ? 400 - values[1]! / 100 : 200,
          ],
          convertFromPixel: (_finder: unknown, values: number[]) => [
            Date.parse(time) + (values[0]! - 40) * 25,
            (400 - values[1]!) * 100,
          ],
          getModel: () => ({
            getComponent: () => ({
              coordinateSystem: { getRect: () => ({ x: 12, y: 34, width: 560, height: 320 }) },
            }),
          }),
          containPixel: () => true,
          on: noop,
          off: noop,
          isDisposed: () => false,
          getDom: () => document.createElement('div'),
          getZr: () => ({ on: noop, off: noop }),
        } as unknown as Parameters<NonNullable<ChartProps['onReady']>>[0]);
      }, [onReady]);
      return <div role="img" aria-label={props.ariaLabel} />;
    },
  };
});

const wallet = '1'.repeat(32);
const other = 'So11111111111111111111111111111111111111112';
const time = '2026-10-15T00:00:00Z';
const chart: ChartResponse = {
  millisPerSlot: null,
  coverage: [],
  ticks: { included: false, total: 0, items: [] },
  bucketSeconds: 1,
  range: { from: time, to: '2026-10-15T00:00:10Z' },
  trackedWallet: wallet,
  valuationBasis: {
    label: 'Recorded supply',
    supply: '1000000000',
    supplySource: 'Captured',
    quoteCurrency: 'USDT',
    rateSource: 'Recorded rates',
    rateGranularity: '1m',
    available: true,
    unavailableReason: null,
  },
  candles: [
    {
      bucketStart: time,
      observationCount: 1,
      price: { open: '0.01', high: '0.01', low: '0.01', close: '0.01' },
      mcap: { open: '10000', high: '10000', low: '10000', close: '10000' },
      mcapMissingPoints: 2,
      mcapUnsupportedPoints: 1,
    },
  ],
  markers: {
    available: true,
    unavailableReason: null,
    mode: 'ITEMS',
    total: 1,
    nextCursor: null,
    clusters: [],
    items: [
      {
        kind: 'OBSERVED',
        observationId: 'one',
        trader: wallet,
        isTrackedWallet: true,
        side: 'BUY',
        solLamports: '18446744073709551615',
        tokenRaw: '123456789123456789',
        price: '0.01',
        mcap: '10000',
        slot: 1,
        time,
        estimatedTime: time,
        signature: null,
        source: 'fixture',
      },
    ],
  },
  paperFills: [],
};
const marker = /^Decu buy, 18446744073\.709551615 SOL/;
const tickChart: ChartResponse = {
  ...chart,
  millisPerSlot: 216.2,
  ticks: {
    included: true,
    total: 3,
    items: [0, 1, 2].map((index) => ({
      observationId: `tick-${index}`,
      slot: 459490744 + index,
      indexInSlot: index === 0 ? 1 : 0,
      slotOrderProven: index !== 2,
      time,
      estimatedTime: new Date(Date.parse(time) + 500 + index * 1000).toISOString(),
      price: '0.01',
      mcap: String(10000 + index * 1000),
      curvePrice: '0.011',
      curveMcap: '11000',
      side: 'BUY',
      trader: wallet,
      isTrackedWallet: false,
      solLamports: '1000000000',
      tokenRaw: '1000000',
    })),
  },
};
beforeAll(() => {
  if (!window.PointerEvent)
    Object.defineProperty(window, 'PointerEvent', { value: MouseEvent, configurable: true });
});

afterEach(() => {
  localStorage.clear();
  vi.useRealTimers();
  renderedChart.current = null;
});

describe('TradeChart', () => {
  it('keeps tick zoom and pan local, preserves the view on refetch, and fits the extent', async () => {
    let requests = 0;
    server.use(
      http.get('/api/datasets/local-ticks/chart', () => {
        requests++;
        return HttpResponse.json(tickChart);
      }),
    );
    const { user, router, queryClient } = renderPage(
      <TradeChart kind="dataset" id="local-ticks" pair="base/quote" trackedWallet={wallet} />,
    );
    await screen.findByRole('button', { name: 'Fit' });
    await waitFor(() =>
      expect(new URLSearchParams(router.state.location.search).has('from')).toBe(true),
    );
    const start = Date.parse(time) + 1000;
    act(() =>
      renderedChart.current?.onEvents?.datazoom?.({ startValue: start, endValue: start + 3000 }),
    );
    expect(new URLSearchParams(router.state.location.search).get('from')).toBe(
      new Date(start).toISOString(),
    );
    act(() =>
      renderedChart.current?.onEvents?.datazoom?.({
        batch: [{ startValue: start + 1000, endValue: start + 4000 }],
      }),
    );
    expect(requests).toBe(1);
    const zoom = renderedChart.current?.option.dataZoom as {
      startValue: number;
      endValue: number;
    }[];
    expect(zoom[0]).toMatchObject({ startValue: start + 1000, endValue: start + 4000 });
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['dataset', 'local-ticks', 'chart'] });
    });
    expect(requests).toBe(2);
    expect((renderedChart.current?.option.dataZoom as object[])[0]).toMatchObject(zoom[0] ?? {});
    await user.click(screen.getByRole('button', { name: 'Fit' }));
    expect(new URLSearchParams(router.state.location.search).get('from')).toBe(
      new Date(Date.parse(time)).toISOString(),
    );
    expect(renderedChart.current?.notMerge).toBe(false);
    // Every preset clamps to this short recording; the whole of it is All, not 1H.
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '1H' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('over this range')).toBeInTheDocument();
  });

  it('shows tick hover metadata and resolution quality inside a minute', async () => {
    server.use(http.get('/api/datasets/tick-hover/chart', () => HttpResponse.json(tickChart)));
    renderPage(
      <TradeChart kind="dataset" id="tick-hover" pair="base/quote" trackedWallet={wallet} />,
    );
    await screen.findByRole('button', { name: 'Fit' });
    act(() =>
      renderedChart.current?.onEvents?.updateAxisPointer?.({
        axesInfo: [{ axisDim: 'x', value: Date.parse(time) + 500 }],
      }),
    );
    expect(
      screen.getAllByText(/\+0\.5s · 00:00:00\.5 UTC · slot 459,490,744 · #2 in slot/).length,
    ).toBeGreaterThan(0);
    expect(screen.getByText(/recorded 216.2 ms per slot/)).toHaveTextContent(
      '1 slots have no proven trade order.',
    );
  });

  it('expands the same chart, draws and zooms, then exits with state and focus preserved', async () => {
    server.use(http.get('/api/datasets/expand/chart', () => HttpResponse.json(tickChart)));
    const { user, router } = renderPage(
      <TradeChart kind="dataset" id="expand" pair="base/quote" trackedWallet={wallet} />,
    );
    const expand = await screen.findByRole('button', { name: 'Open full-page chart' });
    await user.click(screen.getByRole('button', { name: 'Next Decu action' }));
    await user.click(expand);
    expect(document.body.style.overflow).toBe('hidden');
    expect(screen.getByRole('dialog', { name: 'Trade details' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Trend line' }));
    const surface = screen.getByLabelText('Chart drawing tools');
    fireEvent.pointerDown(surface, { button: 0, clientX: 100, clientY: 260 });
    fireEvent.pointerMove(surface, { clientX: 200, clientY: 210, altKey: true });
    fireEvent.pointerUp(surface, { clientX: 200, clientY: 210, altKey: true });
    await user.click(screen.getByRole('button', { name: 'Pointer' }));
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    const search = router.state.location.search;
    const drawing = localStorage.getItem('trenching.chart.drawings.v1:dataset:expand:base/quote');
    expect(JSON.parse(drawing ?? '[]')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Exit full page · Esc' }));
    expect(router.state.location.search).toBe(search);
    expect(localStorage.getItem('trenching.chart.drawings.v1:dataset:expand:base/quote')).toBe(
      drawing,
    );
    expect(document.body.style.overflow).toBe('');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Open full-page chart' })).toHaveFocus(),
    );
    await user.click(screen.getByRole('button', { name: 'Open full-page chart' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('button', { name: 'Exit full page · Esc' })).not.toBeInTheDocument();
  });

  it('uses gold for simulated paper trade diamonds and their legend', async () => {
    server.use(
      http.get('/api/runs/test/chart', () =>
        HttpResponse.json({
          ...chart,
          paperFills: [
            {
              kind: 'PAPER',
              phase: 'ENTRY',
              side: 'BUY',
              intentId: 'paper-entry',
              slot: 1,
              time,
              estimatedTime: time,
              price: '0.01',
              mcap: '10000',
              quantity: '10',
              reason: null,
            },
          ],
        }),
      ),
    );
    renderPage(<TradeChart kind="run" id="test" pair="base/quote" trackedWallet={wallet} />);
    expect(await screen.findByTestId('market-pin-paper-entry:ENTRY')).toHaveStyle({
      borderColor: terminal.accent,
    });
    expect(screen.getByText('◇')).toHaveStyle({ color: terminal.accent });
  });
  it('reports the latest bucket size and offers range recovery when no trades are visible', async () => {
    const onData = vi.fn();
    const reset = vi.fn();
    const zoom = vi.fn();
    server.use(
      http.get('/api/runs/test/chart', () =>
        HttpResponse.json({
          ...chart,
          bucketSeconds: 5,
          candles: [],
          markers: { ...chart.markers, items: [] },
        }),
      ),
    );
    const { user } = renderPage(
      <TradeChart
        kind="run"
        id="test"
        pair="base/quote"
        trackedWallet={wallet}
        onData={onData}
        emptyActions={
          <>
            <button onClick={reset}>Reset view</button>
            <button onClick={zoom}>Zoom out</button>
          </>
        }
      />,
    );
    expect(await screen.findByText(/No trades between/)).toBeInTheDocument();
    expect(screen.getByText('Nobody traded this token in the selected range.')).toBeInTheDocument();
    expect(onData).toHaveBeenCalledWith(
      expect.objectContaining({ bucketSeconds: 5 }),
      'base/quote',
    );
    await user.click(screen.getByRole('button', { name: 'Reset view' }));
    await user.click(screen.getByText('Zoom out', { selector: 'button' }));
    expect(reset).toHaveBeenCalledOnce();
    expect(zoom).toHaveBeenCalledOnce();
  });
  it.each(['run', 'dataset'] as const)(
    'discovers the %s tracked wallet from only the first page, then completes the pinned query',
    async (kind) => {
      const requests: URL[] = [];
      server.use(
        http.get(`/api/${kind === 'run' ? 'runs' : 'datasets'}/test/chart`, ({ request }) => {
          const url = new URL(request.url);
          requests.push(url);
          const discovery = url.searchParams.get('markerLimit') === '1';
          const page = url.searchParams.has('cursor');
          return HttpResponse.json({
            ...chart,
            markers: {
              ...chart.markers,
              nextCursor: discovery ? 'unused-discovery-page' : page ? null : 'pinned-page',
              items: page
                ? [
                    {
                      ...chart.markers.items[0]!,
                      observationId: 'two',
                      slot: 2,
                      time: '2026-10-15T00:00:02Z',
                      estimatedTime: '2026-10-15T00:00:02Z',
                    },
                  ]
                : chart.markers.items,
            },
          });
        }),
      );
      renderPage(<TradeChart kind={kind} id="test" pair="base/quote" />);
      expect(await screen.findByText('– / 2')).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /^Decu buy/ })).toHaveLength(2);
      expect(requests[0]?.searchParams.get('markerLimit')).toBe('1');
      expect(requests.map((url) => url.searchParams.get('cursor'))).not.toContain(
        'unused-discovery-page',
      );
      expect(requests.map((url) => url.searchParams.get('cursor'))).toContain('pinned-page');
      expect(requests.slice(1).map((url) => url.searchParams.getAll('wallet'))).toEqual(
        requests.slice(1).map(() => [wallet]),
      );
    },
  );

  it('discovers the run snapshot wallet without consulting a deleted dataset', async () => {
    let datasetRequests = 0;
    server.use(
      http.get('/api/runs/test/chart', () => HttpResponse.json(chart)),
      http.get('/api/datasets/:id', () => {
        datasetRequests++;
        return new HttpResponse(null, { status: 404 });
      }),
    );
    const { user } = renderPage(<TradeChart kind="run" id="test" pair="base/quote" />);
    await user.click(await screen.findByRole('button', { name: 'Valuation information' }));
    expect(await screen.findByText('1111…1111 · pinned')).toBeInTheDocument();
    expect(datasetRequests).toBe(0);
  });

  it('applies zoom immediately, debounces coarse data requests and offers Reset', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/datasets/test/chart', ({ request }) => {
        const url = new URL(request.url);
        requests.push(url);
        return HttpResponse.json(chart);
      }),
    );
    const { user } = renderPage(
      <TradeChart kind="dataset" id="test" pair="base/quote" trackedWallet={wallet} />,
    );
    await user.click(await screen.findByRole('button', { name: marker }));
    expect(screen.getByRole('dialog', { name: 'Trade details' })).toHaveTextContent(
      '18446744073.709551615 SOL',
    );
    await waitFor(() => expect(requests.some((url) => url.searchParams.has('from'))).toBe(true));
    const axis = renderedChart.current?.option.xAxis as { min: number; max: number };
    const settled = requests.length;
    vi.useFakeTimers();
    act(() => {
      renderedChart.current?.onEvents?.datazoom?.({ start: 20, end: 80 });
      renderedChart.current?.onEvents?.datazoom?.({ batch: [{ start: 30, end: 70 }] });
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(249);
    });
    expect(requests).toHaveLength(settled);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    vi.useRealTimers();
    await waitFor(() => expect(requests.length).toBeGreaterThan(settled));
    expect(requests.at(-1)?.searchParams.get('from')).toBe(
      new Date(axis.min + (axis.max - axis.min) * 0.3).toISOString(),
    );
    expect(requests.at(-1)?.searchParams.get('to')).toBe(
      new Date(axis.min + (axis.max - axis.min) * 0.7).toISOString(),
    );
    await user.click(await screen.findByRole('button', { name: 'Reset' }));
    expect(screen.getByRole('button', { name: 'Cycle' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('pins the tracked wallet, sends watchlist filters and persists edits', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/datasets/test/chart', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json(chart);
      }),
    );
    const { user } = renderPage(
      <TradeChart kind="dataset" id="test" pair="base/quote" trackedWallet={wallet} />,
    );
    await user.click(await screen.findByRole('button', { name: 'Valuation information' }));
    expect(await screen.findByText('Recorded supply')).toBeInTheDocument();
    expect(screen.getByText(/2 points without a rate · 1 unsupported/)).toBeInTheDocument();
    expect(requests[0]?.searchParams.getAll('wallet')).toEqual([wallet]);
    await user.click(screen.getByRole('button', { name: 'Layers' }));
    await user.type(await screen.findByRole('textbox', { name: 'Watchlist wallet' }), other);
    await user.click(screen.getByRole('button', { name: 'Add wallet' }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.getAll('wallet')).toEqual([wallet, other]),
    );
    expect(localStorage.getItem('trenching.chart.watchlist')).toContain(other);
    await user.click(screen.getByRole('checkbox', { name: 'All captured traders' }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.has('wallet')).toBe(false));
  });

  it('offers exact marker details to keyboard users without inventing transaction links', async () => {
    server.use(http.get('/api/datasets/test/chart', () => HttpResponse.json(chart)));
    const { user } = renderPage(
      <TradeChart kind="dataset" id="test" pair="base/quote" trackedWallet={wallet} />,
    );
    const button = await screen.findByRole('button', { name: marker });
    button.focus();
    await user.keyboard('{Enter}');
    const card = screen.getByRole('dialog', { name: 'Trade details' });
    expect(card).toHaveTextContent('Decu bought');
    expect(card).toHaveTextContent('18446744073.709551615 SOL');
    expect(card).toHaveTextContent('123456789123.456789');
    expect(within(card).queryByRole('link', { name: /Transaction/ })).not.toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: /Open action/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Price (SOL)' }));
    expect(screen.getByRole('dialog', { name: 'Trade details' })).toHaveTextContent('1111…1111');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Trade details' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: marker })).toHaveFocus();
  });

  it('explains unavailable valuation and missing trader data, and falls back to price', async () => {
    server.use(
      http.get('/api/datasets/test/chart', () =>
        HttpResponse.json({
          ...chart,
          valuationBasis: {
            ...chart.valuationBasis,
            available: false,
            unavailableReason: 'RATES_UNAVAILABLE',
          },
          markers: { ...chart.markers, available: false, items: [] },
        }),
      ),
    );
    renderPage(<TradeChart kind="dataset" id="test" pair="base/quote" trackedWallet={wallet} />);
    expect(await screen.findByText(/RATES_UNAVAILABLE/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Price (SOL)' })).toBeChecked();
    expect(
      screen.getByText(
        "Decu's actions can't be placed: this dataset was recorded before trader data (v3).",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('— / —')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next Decu action' })).toBeDisabled();
  });
});

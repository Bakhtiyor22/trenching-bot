import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
            ((values[0]! / 1000) % 60) * 40 + 40,
            200,
          ],
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
        signature: null,
        source: 'fixture',
      },
    ],
  },
  paperFills: [],
};
const marker = /^Decu buy, 18446744073\.709551615 SOL/;

afterEach(() => {
  localStorage.clear();
  vi.useRealTimers();
  renderedChart.current = null;
});

describe('TradeChart', () => {
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
    expect(onData).toHaveBeenCalledWith(expect.objectContaining({ bucketSeconds: 5 }), 'base/quote');
    await user.click(screen.getByRole('button', { name: 'Reset view' }));
    await user.click(screen.getByRole('button', { name: 'Zoom out' }));
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

  it('debounces zoom against the plotted axis and offers a reset', async () => {
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
      await vi.advanceTimersByTimeAsync(299);
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
    await user.click(await screen.findByRole('button', { name: 'Reset zoom' }));
    expect(screen.queryByRole('button', { name: 'Reset zoom' })).not.toBeInTheDocument();
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

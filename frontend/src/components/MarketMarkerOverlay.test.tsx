import { act, screen } from '@testing-library/react';
import type { ECharts } from 'echarts';
import { describe, expect, it, vi } from 'vitest';
import type { ObservedChartMarker, PaperChartFill } from '../api/types';
import { renderPage } from '../test/render';
import { MarketMarkerOverlay } from './MarketMarkerOverlay';
import { firstSlotGapChart } from '../mocks/fixtures';

const buy: ObservedChartMarker = {
  kind: 'OBSERVED',
  observationId: 'buy',
  trader: '1'.repeat(32),
  isTrackedWallet: true,
  side: 'BUY',
  solLamports: '2400000000',
  tokenRaw: '123456789',
  price: '2',
  mcap: '100',
  slot: 42,
  time: '2026-10-15T15:23:24Z',
  estimatedTime: '2026-10-15T15:23:24Z',
  signature: null,
  source: 'fixture',
};
function chartInstance() {
  const handlers = new Map<string, () => void>();
  const convertToPixel = vi.fn((_finder, values: number[]) => [
    values[0] === Date.parse(buy.time) ? 100 : 300,
    values[1]!,
  ]);
  const chart = {
    convertToPixel,
    containPixel: vi.fn(() => true),
    on: vi.fn((name: string, fn: () => void) => handlers.set(name, fn)),
    off: vi.fn(),
    isDisposed: () => false,
    getDom: () => document.createElement('div'),
  } as unknown as ECharts;
  return { chart, handlers, convertToPixel };
}

describe('MarketMarkerOverlay', () => {
  it('keeps the reference buy at its execution Mcap and joins the recorded window with a dashed connector', () => {
    const { chart, convertToPixel } = chartInstance();
    const action = firstSlotGapChart.markers.items[0]!;
    renderPage(
      <MarketMarkerOverlay
        actions={[action]}
        response={firstSlotGapChart}
        view="mcap"
        chart={chart}
        selectedKey={null}
        onSelect={vi.fn()}
        onStack={vi.fn()}
      />,
    );
    expect(convertToPixel).toHaveBeenCalledWith({ xAxisIndex: 0, yAxisIndex: 0 }, [
      Date.parse('2026-10-21T04:10:18.284Z'),
      9429,
    ]);
    expect(convertToPixel).toHaveBeenCalledWith({ xAxisIndex: 0, yAxisIndex: 0 }, [
      Date.parse('2026-10-21T04:10:18.500Z'),
      13195,
    ]);
    expect(screen.getByTestId('unrecorded-connector')).toHaveAttribute('stroke-dasharray', '3 3');
    expect(screen.getByText('unrecorded')).toBeInTheDocument();
  });
  it('does not invent an unrecorded stretch inside coverage', () => {
    const { chart } = chartInstance();
    const action = { ...firstSlotGapChart.markers.items[0]!, slot: 459490744 };
    renderPage(
      <MarketMarkerOverlay
        actions={[action]}
        response={firstSlotGapChart}
        view="mcap"
        chart={chart}
        selectedKey={null}
        onSelect={vi.fn()}
        onStack={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('unrecorded-connector')).not.toBeInTheDocument();
  });
  it('places real buy/sell buttons using each own value and decorates selected pins', async () => {
    const { chart, convertToPixel } = chartInstance();
    const select = vi.fn();
    const sell = {
      ...buy,
      observationId: 'sell',
      side: 'SELL' as const,
      time: '2026-10-15T15:23:25Z',
      estimatedTime: '2026-10-15T15:23:25Z',
      mcap: '140',
    };
    const { user } = renderPage(
      <MarketMarkerOverlay
        actions={[sell, buy]}
        view="mcap"
        chart={chart}
        selectedKey="buy"
        onSelect={select}
        onStack={vi.fn()}
      />,
    );
    const buttons = screen.getAllByRole('button');
    expect(buttons[0]).toHaveAccessibleName('Decu buy, 2.4 SOL at 15:23:24 UTC');
    expect(buttons[0]).toHaveStyle({ left: '78px', top: '52px', width: '44px', height: '44px' });
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'true');
    expect(buttons[1]).toHaveStyle({ top: '144px' });
    expect(convertToPixel).toHaveBeenCalledWith({ xAxisIndex: 0, yAxisIndex: 0 }, [
      Date.parse(sell.time),
      140,
    ]);
    await user.click(buttons[0]!);
    expect(select).toHaveBeenCalledWith(buy, { x: 100, y: 74 });
    expect(screen.getByTestId('market-pin-buy')).toHaveStyle({ background: '#F2B43C' });
    expect(screen.getByTestId('market-pin-sell')).toHaveStyle({ border: '2px solid #F2B43C' });
  });
  it('opens a stack list and updates positions on chart completion and zoom', async () => {
    const { chart, handlers, convertToPixel } = chartInstance();
    const stack = vi.fn();
    const next = { ...buy, observationId: 'second' };
    const { user, unmount } = renderPage(
      <MarketMarkerOverlay
        actions={[buy, next]}
        view="price"
        chart={chart}
        selectedKey={null}
        onSelect={vi.fn()}
        onStack={stack}
      />,
    );
    await user.click(screen.getByRole('button', { name: /2 Decu actions/ }));
    expect(stack).toHaveBeenCalledWith([buy, next], { x: 100, y: -24 });
    const count = convertToPixel.mock.calls.length;
    act(() => handlers.get('finished')?.());
    act(() => handlers.get('datazoom')?.());
    expect(convertToPixel.mock.calls.length).toBeGreaterThan(count);
    unmount();
    expect(chart.off).toHaveBeenCalledWith('finished', expect.any(Function));
  });
  it('keeps paper diamonds independent and omits missing metric values and other wallets', () => {
    const { chart } = chartInstance();
    const paper: PaperChartFill = {
      kind: 'PAPER',
      intentId: 'intent',
      phase: 'ENTRY',
      side: 'BUY',
      slot: 44,
      time: buy.time,
      estimatedTime: buy.time,
      price: buy.price,
      mcap: buy.mcap,
      quantity: '123.45',
      reason: null,
    };
    renderPage(
      <MarketMarkerOverlay
        actions={[
          buy,
          paper,
          { ...buy, observationId: 'missing', mcap: null },
          { ...buy, observationId: 'other', isTrackedWallet: false },
        ]}
        view="mcap"
        chart={chart}
        selectedKey={null}
        onSelect={vi.fn()}
        onStack={vi.fn()}
      />,
    );
    expect(screen.getAllByRole('button')).toHaveLength(2);
    expect(screen.getByTestId('market-pin-intent:ENTRY')).toHaveStyle({
      width: '11px',
      height: '11px',
      transform: 'rotate(45deg)',
    });
  });
});

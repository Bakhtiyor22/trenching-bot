import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderPage } from '../test/render';
import type { ObservedChartMarker, PaperChartFill } from '../api/types';
import { MarketActionCard } from './MarketActionCard';
import { firstSlotGapChart } from '../mocks/fixtures';

const buy: ObservedChartMarker = {
  kind: 'OBSERVED',
  observationId: 'buy',
  trader: '1'.repeat(32),
  isTrackedWallet: true,
  side: 'BUY',
  solLamports: '18446744073709551615',
  tokenRaw: '123456789123456789',
  price: '0.0000000775',
  mcap: '15500.000',
  slot: 42,
  time: '2026-10-15T15:23:24Z',
  estimatedTime: '2026-10-15T15:23:24Z',
  signature: null,
  source: 'fixture',
};
const props = {
  action: buy,
  index: 2,
  total: 4,
  anchor: { x: 20, y: 100 },
  width: 700,
  view: 'mcap' as const,
  onClose: vi.fn(),
  onOpenDetails: vi.fn(),
};

describe('MarketActionCard', () => {
  it('explains the unrecorded remainder of the first buy slot without moving the execution value', () => {
    renderPage(
      <MarketActionCard
        {...props}
        action={firstSlotGapChart.markers.items[0]!}
        response={firstSlotGapChart}
      />,
    );
    const card = screen.getByRole('dialog');
    expect(card).toHaveTextContent('Why the line starts higher');
    expect(card).toHaveTextContent('$9.4k');
    expect(card).toHaveTextContent('$13.2k');
    expect(card).toHaveTextContent('1.955555555 SOL for 21979703.232124 tokens');
    expect(card).toHaveTextContent('slot 459,490,744');
    expect(card).toHaveTextContent('Block time 04:10:18 UTC · slot 459,490,743');
    expect(card).toHaveTextContent('216.2 ms per slot');
  });
  it('shows average execution and post-trade curve values for a covered tracked trade', () => {
    const tick = firstSlotGapChart.ticks.items[0]!;
    const action = {
      ...firstSlotGapChart.markers.items[0]!,
      ...tick,
      trader: '1'.repeat(32),
      side: 'BUY' as const,
      solLamports: '1955555555',
      tokenRaw: '21979703232124',
    };
    renderPage(<MarketActionCard {...props} action={action} response={firstSlotGapChart} />);
    expect(screen.queryByText('Why the line starts higher')).not.toBeInTheDocument();
    expect(
      screen.getByText('Execution price (average over this trade): $13.2k'),
    ).toBeInTheDocument();
    expect(screen.getByText('Curve price right after it: $13.2k')).toBeInTheDocument();
  });
  it('shows exact buy amounts, cycle note, null-signature omission and clamped placement', () => {
    renderPage(<MarketActionCard {...props} firstBuy />);
    const card = screen.getByRole('dialog', { name: 'Trade details' });
    expect(card).toHaveTextContent('Decu bought');
    expect(card).toHaveTextContent('18446744073.709551615 SOL');
    expect(card).toHaveTextContent('123456789123.456789');
    expect(card).toHaveTextContent('15:23:24 UTC · slot 42');
    expect(card).toHaveTextContent('2 of 4');
    expect(card).toHaveTextContent('First buy · started this cycle');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(card).toHaveStyle({ left: '8px', top: '126px', width: '300px' });
  });
  it('prefixes sells and provides details and close controls', async () => {
    const { user } = renderPage(
      <MarketActionCard
        {...props}
        action={{ ...buy, side: 'SELL' }}
        positionClosed
        anchor={{ x: 690, y: 300 }}
      />,
    );
    expect(screen.getByText('Decu sold')).toBeInTheDocument();
    expect(screen.getByText('+18446744073.709551615 SOL')).toBeInTheDocument();
    expect(screen.getByText('Position closed')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Open action →' }));
    expect(props.onOpenDetails).toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Close action details' }));
    expect(props.onClose).toHaveBeenCalled();
  });
  it('identifies paper fills as simulated and displays their reason', () => {
    const fill: PaperChartFill = {
      kind: 'PAPER',
      intentId: 'intent',
      phase: 'ENTRY',
      side: 'BUY',
      slot: 44,
      time: buy.time,
      estimatedTime: buy.time,
      price: buy.price,
      mcap: null,
      quantity: '123.450000',
      reason: 'DIP_ENTRY',
    };
    renderPage(<MarketActionCard {...props} action={fill} />);
    expect(screen.getByText('Bot paper entry')).toBeInTheDocument();
    expect(screen.getByText('Simulated fill · not a real trade')).toBeInTheDocument();
    expect(screen.getByText('123.45 tokens')).toBeInTheDocument();
    expect(screen.getByText('DIP_ENTRY')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open fill →' })).toBeInTheDocument();
  });
  it('links a valid recorded signature through the transaction helper', () => {
    renderPage(<MarketActionCard {...props} action={{ ...buy, signature: '1'.repeat(64) }} />);
    expect(screen.getByRole('link', { name: 'Transaction ↗' })).toHaveAttribute(
      'href',
      `https://solscan.io/tx/${'1'.repeat(64)}`,
    );
  });
});

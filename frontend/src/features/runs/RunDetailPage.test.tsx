import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  COST_BASIS,
  EDGE_RUN_ID,
  ENTRY_FEE,
  REAL_BUY_SIGNATURE,
  REAL_EXIT_SIGNATURES,
  REAL_FILL_SIGNATURES,
  REAL_MINT,
  REAL_RUN_ID,
  SAMPLE_PNL,
  SAMPLE_RUN_ID,
  SAMPLE_TOTAL_PNL,
  STOP_LOSS_EXIT_PRICE,
  STOP_LOSS_LEVEL,
  STOP_LOSS_PNL,
  TAKE_PROFIT_ENTRY_PRICE,
  TAKE_PROFIT_EXIT_PRICE,
  TAKE_PROFIT_LEVEL,
  TAKE_PROFIT_NET_PROCEEDS,
  TAKE_PROFIT_QUANTITY,
  TRANSACTION_COST,
  USDT_COST_BASIS,
  USDT_ENTRY_PRICE,
  USDT_NET_PROCEEDS,
  USDT_PNL,
  USDT_TOTAL_PNL,
  USDT_TRANSACTION_COST,
} from '../../mocks/fixtures';
import { formatDecimal } from '../../lib/decimal';
import { renderPage } from '../../test/render';
import { RunDetailPage } from './RunDetailPage';

interface PlottedSeries {
  name?: string;
  data?: Array<{ value: [number, number]; tip?: string; note?: string }>;
}

interface PlottedOption {
  series: PlottedSeries[];
  yAxis: { name?: string };
}

async function plotted(): Promise<PlottedOption> {
  const chart = await screen.findByTestId('echarts');
  return JSON.parse(chart.getAttribute('data-option') ?? '{}') as PlottedOption;
}

/** As the page shows a decimal: thousands grouped, every digit kept. */
const grouped = (value: string) => formatDecimal(value);

function hrefs(name: string): string[] {
  return screen.queryAllByRole('link', { name }).map((link) => link.getAttribute('href') ?? '');
}

function renderRun(id: string, state?: unknown) {
  return renderPage(<RunDetailPage />, {
    path: '/runs/:id',
    url: state === undefined ? `/runs/${id}` : { pathname: `/runs/${id}`, state },
  });
}

describe('RunDetailPage', () => {
  it('shows one cycle per token: take profit, stop loss and no dip, each with its exact values', async () => {
    renderRun(SAMPLE_RUN_ID);
    const cards = await screen.findAllByRole('article');
    expect(cards).toHaveLength(3);
    const [takeProfit, stopLoss, noDip] = cards;

    expect(within(takeProfit!).getByText('Take profit')).toBeInTheDocument();
    expect(within(takeProfit!).getByText(SAMPLE_PNL)).toBeInTheDocument();
    expect(within(takeProfit!).getByText('0.0000001')).toBeInTheDocument();
    expect(within(takeProfit!).getByText('0.000000085')).toBeInTheDocument();
    expect(within(takeProfit!).getByText('$16,800.000000000000')).toBeInTheDocument();
    expect(within(takeProfit!).getByText(TAKE_PROFIT_ENTRY_PRICE)).toBeInTheDocument();
    expect(within(takeProfit!).getByText(grouped(TAKE_PROFIT_QUANTITY))).toBeInTheDocument();
    expect(within(takeProfit!).getByText(TAKE_PROFIT_LEVEL)).toBeInTheDocument();
    expect(within(takeProfit!).getByText(TAKE_PROFIT_NET_PROCEEDS)).toBeInTheDocument();

    expect(within(stopLoss!).getByText('Stop loss')).toBeInTheDocument();
    expect(within(stopLoss!).getByText(STOP_LOSS_PNL)).toBeInTheDocument();
    expect(within(stopLoss!).getByText(STOP_LOSS_EXIT_PRICE)).toBeInTheDocument();

    expect(within(noDip!).getAllByText('No entry').length).toBeGreaterThan(0);
    expect(within(noDip!).getByText('NO_DIP')).toBeInTheDocument();
    expect(screen.getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
  });

  it('shows the fees, price impact and transaction cost of each fill and close', async () => {
    renderRun(SAMPLE_RUN_ID);
    const [takeProfit] = await screen.findAllByRole('article');
    const card = within(takeProfit!);

    expect(card.getByText('Fees (125 bps)')).toBeInTheDocument();
    expect(card.getByText(ENTRY_FEE)).toBeInTheDocument();
    expect(card.getByText('+19 bps')).toBeInTheDocument();
    expect(card.getByText(COST_BASIS)).toBeInTheDocument();
    expect(card.getByText('Exit fees (125 bps)')).toBeInTheDocument();
    expect(card.getByText('0.001664690000')).toBeInTheDocument();
    expect(card.getByText('-22 bps')).toBeInTheDocument();
    expect(card.getByText('0.133175124000')).toBeInTheDocument();
    // Charged once on entry and once on exit.
    expect(card.getAllByText(TRANSACTION_COST)).toHaveLength(2);
    expect(card.queryByText(/slippage/i)).not.toBeInTheDocument();
  });

  it('plots the first token with its entry and take-profit exit', async () => {
    renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    const option = await plotted();
    const series = (name: string) => option.series.find((s) => s.name === name)?.data ?? [];
    expect(series('Entry fills').map((d) => d.value)).toEqual([[1003, Number(TAKE_PROFIT_ENTRY_PRICE)]]);
    expect(series('Exits').map((d) => d.value)).toEqual([[1006, Number(TAKE_PROFIT_EXIT_PRICE)]]);
    expect(series('Entry fills')[0]?.tip).toContain(`cost ${COST_BASIS}`);
    expect(series('Entry fills')[0]?.tip).toContain('Fees 125 bps · impact +19 bps');
    expect(series('Exits')[0]?.tip).toContain(`Net proceeds ${TAKE_PROFIT_NET_PROCEEDS}`);
    expect(series('Exits')[0]?.tip).toContain('Fees 125 bps · impact -22 bps');
    expect(series('Exits')[0]?.tip).toContain('Take profit');
    expect(series('Entry fills')[0]?.tip).not.toContain('USDT per SOL');
  });

  it('follows the picked cycle on the chart and its event timeline, and steps with J and K', async () => {
    const { user } = renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    const cycles = screen.getByRole('navigation', { name: 'Cycles' });
    await user.click(within(cycles).getByRole('button', { name: /Stop loss/ }));
    const exits = async () => (await plotted()).series.find((s) => s.name === 'Exits')?.data?.map((d) => d.value);
    expect(await exits()).toEqual([[2005, Number(STOP_LOSS_EXIT_PRICE)]]);
    expect(screen.getByText('Stop loss crossed')).toBeInTheDocument();
    expect(screen.getByRole('article', { current: true })).toHaveAccessibleName(/Cycle/);

    await user.keyboard('k');
    expect(await exits()).toEqual([[1006, Number(TAKE_PROFIT_EXIT_PRICE)]]);
    await user.keyboard('k');
    const story = screen.getByRole('heading', { name: 'Event timeline' }).closest('section')!;
    expect(within(story).getByText('No entry')).toBeInTheDocument();
    expect(within(story).getByText('NO_DIP')).toBeInTheDocument();
  });

  it('shows cycles without an entry with their reason and an open position with why it never exited', async () => {
    renderRun(EDGE_RUN_ID);
    const cards = await screen.findAllByRole('article');
    const [open, lowCap, migrated] = cards;

    expect(within(lowCap!).getByText(/Dip below min market cap/)).toBeInTheDocument();
    expect(within(lowCap!).getByText('DIP_BELOW_MIN_MARKET_CAP')).toBeInTheDocument();
    expect(within(migrated!).getByText('MIGRATED_BEFORE_ENTRY')).toBeInTheDocument();

    expect(within(open!).getByText('Open')).toBeInTheDocument();
    expect(within(open!).getByText('Still open')).toBeInTheDocument();
    expect(within(open!).getByText(/Horizon reached/)).toBeInTheDocument();
    expect(within(open!).getByText('HORIZON_REACHED')).toBeInTheDocument();
    // Tiny prices are displayed exactly, never in exponent form.
    expect(within(open!).getByText('0.0000000000124017')).toBeInTheDocument();
    // Open positions are counted by why they never exited, so migrated tokens stay apart.
    expect(screen.getByText('Open: 1 horizon reached')).toBeInTheDocument();
  });

  it('tells the user when the started run already existed', async () => {
    renderRun(SAMPLE_RUN_ID, { created: false });
    expect(
      await screen.findByText(
        'This exact run already existed — identical dataset, config and engine produce the same run id.',
      ),
    ).toBeInTheDocument();
  });

  it('offers the report as a download link', async () => {
    renderRun(SAMPLE_RUN_ID);
    const link = await screen.findByRole('link', { name: /Download report/ });
    expect(link).toHaveAttribute('href', `/api/runs/${SAMPLE_RUN_ID}/report`);
    expect(link).toHaveAttribute('download');
  });

  it('shows ignored wallet events with their reason, including later buys of a token', async () => {
    renderRun(SAMPLE_RUN_ID);
    expect(await screen.findByText('LATER_BUY_SAME_MINT')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('shows the dip strategy configuration the run used', async () => {
    renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    expect(screen.getAllByText('1500 bps (15%)')).toHaveLength(2);
    expect(screen.getByText('3000 bps (30%)')).toBeInTheDocument();
    expect(screen.getByText('$10,000')).toBeInTheDocument();
    expect(screen.getByText('4,500 slots (about 30 min)')).toBeInTheDocument();
    expect(screen.getByText('1 slot after each trigger')).toBeInTheDocument();
    expect(screen.getByText(`${TRANSACTION_COST} SOL per transaction`)).toBeInTheDocument();
    expect(screen.queryByText('Slippage')).not.toBeInTheDocument();
  });

  it('renders exact values in every stored-record tab', async () => {
    const { user } = renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    await user.click(screen.getByRole('tab', { name: 'Observations' }));
    expect(await within(screen.getByRole('tabpanel')).findAllByText('0.000000095')).toHaveLength(2);
    await user.click(screen.getByRole('tab', { name: 'Orders & fills' }));
    const orders = screen.getByRole('tabpanel');
    expect(await within(orders).findAllByText(grouped(TAKE_PROFIT_QUANTITY))).toHaveLength(1);
    expect(within(orders).getAllByText(ENTRY_FEE)).toHaveLength(2);
    expect(within(orders).getAllByText('fees 125 bps')).toHaveLength(2);
    expect(within(orders).getByText(/impact \+19 bps/)).toBeInTheDocument();
    expect(within(orders).getAllByText(TRANSACTION_COST)).toHaveLength(2);
    expect(within(orders).getAllByText(COST_BASIS)).toHaveLength(2);
    expect(within(orders).getByText('NO_DIP')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Positions' }));
    const positions = screen.getByRole('tabpanel');
    expect(await within(positions).findAllByText(SAMPLE_PNL)).toHaveLength(1);
    expect(within(positions).getByText(TAKE_PROFIT_NET_PROCEEDS)).toBeInTheDocument();
    expect(within(positions).getByText(TAKE_PROFIT_LEVEL)).toBeInTheDocument();
    expect(within(positions).getByText(STOP_LOSS_LEVEL)).toBeInTheDocument();
    expect(within(positions).getByText('0.001664690000')).toBeInTheDocument();
    expect(within(positions).getByText(/impact -22 bps/)).toBeInTheDocument();
    // Entry and exit transaction costs for both closed positions.
    expect(within(positions).getAllByText(TRANSACTION_COST)).toHaveLength(4);
  });

  it('shows the missing exit reason in the positions table too', async () => {
    const { user } = renderRun(EDGE_RUN_ID);
    await screen.findAllByRole('article');
    await user.click(screen.getByRole('tab', { name: 'Positions' }));
    const positions = screen.getByRole('tabpanel');
    expect(await within(positions).findByText('HORIZON_REACHED')).toBeInTheDocument();
    expect(within(positions).getByText('0.0000000000124017')).toBeInTheDocument();
  });

  it('shows an error for an unknown run', async () => {
    renderRun('does-not-exist');
    expect(await screen.findByText('Run does-not-exist not found')).toBeInTheDocument();
  });

  describe('in USDT and with market links', () => {
    it('switches the totals, cycle cards and chart to USDT and keeps the choice in the URL', async () => {
      const { user, router } = renderRun(REAL_RUN_ID);
      await screen.findAllByRole('article');
      expect(screen.getAllByText(SAMPLE_PNL).length).toBeGreaterThan(0);

      await user.click(screen.getByRole('radio', { name: 'USDT' }));

      expect((await screen.findAllByText(grouped(USDT_TOTAL_PNL))).length).toBeGreaterThan(0);
      expect(screen.getByText('Realized P&L (USDT)')).toBeInTheDocument();
      const [first] = screen.getAllByRole('article');
      expect(within(first!).getByText(grouped(USDT_PNL))).toBeInTheDocument();
      expect(within(first!).getByText(USDT_COST_BASIS)).toBeInTheDocument();
      expect(within(first!).getByText(USDT_ENTRY_PRICE)).toBeInTheDocument();
      // The transaction cost converts on entry and on exit.
      expect(within(first!).getAllByText(USDT_TRANSACTION_COST)).toHaveLength(2);
      // P0 and the levels stay in SOL, where the strategy compares them.
      expect(within(first!).getByText('0.0000001')).toBeInTheDocument();
      expect(router.state.location.search).toBe('?quote=usdt');
      const option = await plotted();
      expect(option.yAxis.name).toBe('Price (USDT)');
      expect(option.series.find((s) => s.name === 'Entry fills')?.data?.map((d) => d.value)).toEqual([[1003, Number(USDT_ENTRY_PRICE)]]);
      const entry = option.series.find((s) => s.name === 'Entry fills')?.data?.[0];
      expect(entry?.tip).toContain(`cost ${USDT_COST_BASIS} USDT`);
      expect(entry?.tip).toContain('At 200 USDT per SOL, Binance 1-minute open of 2026-10-01 00:00 UTC');
      expect(option.series.find((s) => s.name === 'Exits')?.data?.[0]?.tip).toContain(`Net proceeds ${USDT_NET_PROCEEDS} USDT`);
      const observation = option.series.find((s) => s.name === 'Last trade')?.data?.[0];
      expect(observation?.note).toBe('At 200 USDT per SOL, Binance 1-minute open of 2026-10-01 00:00 UTC');
      expect(screen.getByText(/Moves within a minute are not resolved/)).toBeInTheDocument();
    });

    it('shows each observation price in USDT with the rate and minute it used', async () => {
      const { user } = renderRun(`${REAL_RUN_ID}?quote=usdt`);
      expect(await screen.findByRole('radio', { name: 'USDT' })).toBeChecked();
      await screen.findAllByText(grouped(USDT_TOTAL_PNL));
      await user.click(screen.getByRole('tab', { name: 'Observations' }));
      const panel = screen.getByRole('tabpanel');
      expect(await within(panel).findByText('Price (USDT)')).toBeInTheDocument();
      const [price] = within(panel).getAllByText('0.00001680000000000000000000000000000000');
      expect(price?.closest('[title]')).toHaveAttribute('title', 'At 200 USDT per SOL, Binance 1-minute open of 2026-10-01 00:00 UTC');
    });

    it('opens in USDT from the URL and keeps SOL values with the reason when rates are unavailable', async () => {
      renderRun(`${SAMPLE_RUN_ID}?quote=usdt`);
      expect(await screen.findByRole('radio', { name: 'USDT' })).toBeChecked();
      expect(await screen.findByText(/NOT_CONFIGURED/)).toBeInTheDocument();
      expect(screen.getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
      expect((await plotted()).yAxis.name).toBe('Price (SOL)');
    });

    it('shows the current SOL price', async () => {
      renderRun(REAL_RUN_ID);
      expect(await screen.findByText('1 SOL = 212.34000000 USDT')).toBeInTheDocument();
    });

    it('links the recorded buy, the dip, entry, exit-trigger and exit trades and the token, opening each in a new tab', async () => {
      renderRun(REAL_RUN_ID);
      await screen.findAllByRole('article');
      await screen.findAllByRole('link', { name: 'pump.fun' });

      const solscan = hrefs('Solscan');
      for (const signature of [REAL_BUY_SIGNATURE, ...REAL_FILL_SIGNATURES, ...REAL_EXIT_SIGNATURES]) {
        expect(solscan).toContain(`https://solscan.io/tx/${signature}`);
        expect(hrefs('Solana Explorer')).toContain(`https://explorer.solana.com/tx/${signature}`);
      }
      expect(hrefs('pump.fun')).toContain(`https://pump.fun/coin/${REAL_MINT}`);
      expect(hrefs('DexScreener')).toContain(`https://dexscreener.com/solana/${REAL_MINT}`);
      for (const link of screen.getAllByRole('link', { name: 'Solscan' })) {
        expect(link).toHaveAttribute('target', '_blank');
        expect(link).toHaveAttribute('rel', 'noopener noreferrer');
      }
    });

    it('gives the synthetic sample no market links', async () => {
      renderRun(SAMPLE_RUN_ID);
      await screen.findAllByRole('article');
      await screen.findByText('LATER_BUY_SAME_MINT');
      expect(hrefs('Solscan')).toEqual([]);
      expect(hrefs('pump.fun')).toEqual([]);
    });
  });
});

import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
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
  TAKE_PROFIT_LEVEL,
  TAKE_PROFIT_NET_PROCEEDS,
  TAKE_PROFIT_QUANTITY,
  TRANSACTION_COST,
  USDT_COST_BASIS,
  USDT_ENTRY_PRICE,
  USDT_PNL,
  USDT_TOTAL_PNL,
  USDT_TRANSACTION_COST,
  WALLET,
  WATCHED_WALLET,
  v4Chart,
  v3Chart,
  v4Observations,
  realRunDetail,
} from '../../mocks/fixtures';
import { formatDecimal } from '../../lib/decimal';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { RunDetailPage } from './RunDetailPage';

interface PlottedSeries {
  name?: string;
  type?: string;
  data?: Array<{ value: [number, number]; tip?: string; note?: string }>;
}

interface PlottedOption {
  series: PlottedSeries[];
  yAxis: { name?: string };
  xAxis: { type?: string };
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
  beforeEach(() => localStorage.clear());

  it('keeps the chart window, value choices and selected action in Open chart', async () => {
    renderPage(<RunDetailPage />, {
      path: '/runs/:id',
      url: `/runs/${REAL_RUN_ID}?from=2026-10-15T00%3A00%3A00Z&to=2026-10-15T00%3A01%3A00Z&view=price&style=candles&layers=decu,paper&action=buy-1`,
    });
    const link = await screen.findByRole('link', { name: 'Open chart' });
    const target = new URL(link.getAttribute('href') ?? '', 'http://localhost');
    expect(target.searchParams.get('pair')).toBeTruthy();
    expect(target.searchParams.get('from')).toBe('2026-10-15T00:00:00Z');
    expect(target.searchParams.get('view')).toBe('price');
    expect(target.searchParams.get('style')).toBe('candles');
    expect(target.searchParams.get('layers')).toBe('decu,paper');
    expect(target.searchParams.get('action')).toBe('buy-1');
  });

  it('shows the valuation basis and requests the pinned wallet plus the watchlist', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/runs/:id/chart', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json(v4Chart);
      }),
    );
    const { user } = renderRun(REAL_RUN_ID);
    await user.click(await screen.findByRole('button', { name: 'Valuation information' }));
    expect(await screen.findByText(v4Chart.valuationBasis.label)).toBeInTheDocument();
    expect(screen.getByText(/pumpfun-standard-supply/)).toBeInTheDocument();
    expect(screen.getByText(/recorded:SOLUSDT:1m:open/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Valuation information' }));
    await user.click(screen.getByRole('button', { name: 'Layers' }));
    await user.type(screen.getByRole('textbox', { name: 'Watchlist wallet' }), WATCHED_WALLET);
    await user.click(screen.getByRole('button', { name: 'Add wallet' }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.getAll('wallet')).toEqual([WALLET, WATCHED_WALLET]),
    );
    // One-second buckets for any range up to the backend's cap of 2000 buckets.
    expect(requests.at(-1)?.searchParams.get('maxBuckets')).toBe('2000');
  });

  it('explains v3 trader data and shows chart failures without hiding the run', async () => {
    const { unmount } = renderRun(SAMPLE_RUN_ID);
    expect(
      await screen.findByText(
        "Decu's actions can't be placed: this dataset was recorded before trader data (v3).",
      ),
    ).toBeInTheDocument();
    unmount();
    server.use(
      http.get('/api/runs/:id/chart', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Run chart unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    renderRun(SAMPLE_RUN_ID);
    expect(await screen.findByText('Run chart unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(3);
  });

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

    expect(card.getByText('Fees (1.25%)')).toBeInTheDocument();
    expect(card.getByText(ENTRY_FEE)).toBeInTheDocument();
    expect(card.getByText('+0.19%')).toBeInTheDocument();
    expect(card.getByText(COST_BASIS)).toBeInTheDocument();
    expect(card.getByText('Exit fees (1.25%)')).toBeInTheDocument();
    expect(card.getByText('0.001664690000')).toBeInTheDocument();
    expect(card.getByText('−0.22%')).toBeInTheDocument();
    expect(card.getByText('0.133175124000')).toBeInTheDocument();
    // Charged once on entry and once on exit.
    expect(card.getAllByText(TRANSACTION_COST)).toHaveLength(2);
    expect(card.queryByText(/slippage/i)).not.toBeInTheDocument();
  });

  it('plots market cap and paper fills over time, with strategy levels in the SOL price view', async () => {
    const { user } = renderRun(REAL_RUN_ID);
    await screen.findAllByRole('article');
    const option = await plotted();
    expect(option.xAxis.type).toBe('time');
    expect(option.yAxis.name).toBe('Mcap (USDT)');
    expect(option.series.some((series) => series.type === 'line')).toBe(true);
    expect(await screen.findByRole('button', { name: /Bot paper entry/ })).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Price (SOL)' }));
    expect((await plotted()).yAxis.name).toBe('Price (SOL)');
    expect((await plotted()).series.some((series) => series.name === 'Levels')).toBe(true);
  });

  it('follows the picked cycle on the chart and its event timeline, and steps with J and K', async () => {
    const pairs: string[] = [];
    server.use(
      http.get('/api/runs/:id/chart', ({ request }) => {
        pairs.push(new URL(request.url).searchParams.get('pair') ?? '');
        return HttpResponse.json(v3Chart);
      }),
    );
    const { user } = renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    await screen.findByRole('img', { name: 'Market cap and trades over time' });
    const firstPair = pairs.at(-1);
    const cycles = screen.getByRole('navigation', { name: 'Cycles' });
    await user.click(within(cycles).getByRole('button', { name: /Stop loss/ }));
    await waitFor(() => expect(pairs.at(-1)).not.toBe(firstPair));
    expect(screen.getByText('Stop loss crossed')).toBeInTheDocument();
    expect(screen.getByRole('article', { current: true })).toHaveAccessibleName(/Cycle/);

    await user.keyboard('k');
    await screen.findByRole('img', { name: 'Market cap and trades over time' });
    expect(screen.getByText('Take profit crossed')).toBeInTheDocument();
    await user.keyboard('k');
    const story = screen.getByRole('heading', { name: 'Event timeline' }).closest('section')!;
    expect(within(story).getByText('No entry')).toBeInTheDocument();
    expect(within(story).getByText('NO_DIP')).toBeInTheDocument();
  });

  it('shows cycles without an entry with their reason and an open position with why it never exited', async () => {
    renderRun(EDGE_RUN_ID);
    const cards = await screen.findAllByRole('article');
    const [open, lowCap, migrated] = cards;

    expect(
      within(lowCap!).getByText(/The dip happened below the minimum market cap/),
    ).toBeInTheDocument();
    expect(within(lowCap!).getByText('DIP_BELOW_MIN_MARKET_CAP')).toBeInTheDocument();
    expect(within(migrated!).getByText('MIGRATED_BEFORE_ENTRY')).toBeInTheDocument();

    expect(within(open!).getByText('Open')).toBeInTheDocument();
    expect(within(open!).getByText('Still open')).toBeInTheDocument();
    expect(
      within(open!).getByText(/The window ended with the position still open/),
    ).toBeInTheDocument();
    expect(within(open!).getByText('HORIZON_REACHED')).toBeInTheDocument();
    // Tiny prices are displayed exactly, never in exponent form.
    expect(within(open!).getByText('0.0000000000124017')).toBeInTheDocument();
    // Open positions are counted by why they never exited, so migrated tokens stay apart.
    expect(screen.getByText('Open: 1 horizon reached')).toBeInTheDocument();
  });

  it('opens the cycle first buy at its own cycle details card', async () => {
    server.use(http.get('/api/runs/:id/chart', () => HttpResponse.json(v4Chart)));
    const { user } = renderRun(REAL_RUN_ID);
    await user.click(await screen.findByRole('button', { name: 'Next Decu action' }));
    await user.click(await screen.findByRole('button', { name: /Open action/ }));
    const card = screen.getByRole('article', { current: true });
    expect(card.closest('[id="cycle-intent-real"]')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Events' })).toHaveAttribute('aria-selected', 'true');
  });

  it('opens another Decu action in the Observations tab and highlights the exact row', async () => {
    server.use(
      http.get('/api/runs/:id/chart', () => HttpResponse.json(v4Chart)),
      http.get('/api/runs/:id/observations', () => HttpResponse.json(v4Observations)),
    );
    const { user } = renderRun(REAL_RUN_ID);
    const next = await screen.findByRole('button', { name: 'Next Decu action' });
    await user.click(next);
    await user.click(next);
    await user.click(await screen.findByRole('button', { name: /Open action/ }));
    expect(screen.getByRole('tab', { name: 'Observations' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    const row = (await screen.findByText('v4-observation-2')).closest('tr');
    expect(row).toHaveAttribute('id', 'observation-v4-observation-2');
    expect(row).toHaveAttribute('data-highlighted', 'true');
  });

  it.each(['ENTRY', 'EXIT'] as const)(
    'opens a paper %s fill at its stored record',
    async (phase) => {
      const fill = {
        ...v4Chart.paperFills.find((item) => item.phase === phase)!,
        intentId: realRunDetail.scenarios[0]!.intentId,
      };
      server.use(
        http.get('/api/runs/:id/chart', () =>
          HttpResponse.json({ ...v4Chart, paperFills: [fill] }),
        ),
      );
      const { user } = renderRun(REAL_RUN_ID);
      await user.click(
        await screen.findByRole('button', { name: new RegExp(`Bot paper ${phase.toLowerCase()}`) }),
      );
      await user.click(await screen.findByRole('button', { name: /Open fill/ }));
      const tab = phase === 'ENTRY' ? 'Orders & fills' : 'Positions';
      expect(screen.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
      await waitFor(() =>
        expect(document.getElementById(`fill-intent-real-${phase.toLowerCase()}`)).toHaveAttribute(
          'data-highlighted',
          'true',
        ),
      );
    },
  );

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
    expect(screen.getAllByText('15%')).toHaveLength(2);
    expect(screen.getByText('30%')).toBeInTheDocument();
    expect(screen.getByText('$10,000')).toBeInTheDocument();
    expect(
      screen.getByText('4,500 slots (about 30 min at the nominal 400 ms per slot)'),
    ).toBeInTheDocument();
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
    expect(within(orders).getAllByText('fees 1.25%')).toHaveLength(2);
    expect(within(orders).getByText(/impact \+0.19%/)).toBeInTheDocument();
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
    expect(within(positions).getByText(/impact −0.22%/)).toBeInTheDocument();
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
    it('switches totals and cycle cards to USDT independently of the chart metric, keeping the choice in the URL', async () => {
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
      expect(option.yAxis.name).toBe('Mcap (USDT)');
      await user.click(screen.getByRole('radio', { name: 'Price (SOL)' }));
      expect((await plotted()).yAxis.name).toBe('Price (SOL)');
      expect(screen.getByText('Realized P&L (USDT)')).toBeInTheDocument();
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
      expect(price?.closest('[title]')).toHaveAttribute(
        'title',
        'At 200 USDT per SOL, Binance 1-minute open of 2026-10-01 00:00 UTC',
      );
    });

    it('opens in USDT from the URL and keeps SOL values with the reason when rates are unavailable', async () => {
      renderRun(`${SAMPLE_RUN_ID}?quote=usdt`);
      expect(await screen.findByRole('radio', { name: 'USDT' })).toBeChecked();
      expect(await screen.findByText(/NOT_CONFIGURED/)).toBeInTheDocument();
      expect(screen.getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
      expect((await plotted()).yAxis.name).toBe('Mcap (USDT)');
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
      for (const signature of [
        REAL_BUY_SIGNATURE,
        ...REAL_FILL_SIGNATURES,
        ...REAL_EXIT_SIGNATURES,
      ]) {
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
  it('says when the dataset of the run was deleted, since the run keeps its own copy', async () => {
    server.use(
      http.get('/api/datasets/:id', () =>
        HttpResponse.json(
          { code: 'DATASET_NOT_FOUND', message: 'not found', fields: {} },
          { status: 404 },
        ),
      ),
    );
    renderRun(SAMPLE_RUN_ID);

    expect(
      await screen.findByText(/Dataset deleted — this run keeps its own copy/),
    ).toBeInTheDocument();
  });
});

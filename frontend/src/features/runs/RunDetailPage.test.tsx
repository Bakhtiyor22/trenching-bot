import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EDGE_RUN_ID, SAMPLE_PNL, SAMPLE_RUN_ID, SAMPLE_TOTAL_PNL } from '../../mocks/fixtures';
import { renderPage } from '../../test/render';
import { RunDetailPage } from './RunDetailPage';

interface PlottedSeries {
  name?: string;
  data?: Array<{ value: [number, number] }>;
}

function renderRun(id: string, state?: unknown) {
  return renderPage(<RunDetailPage />, {
    path: '/runs/:id',
    url: state === undefined ? `/runs/${id}` : { pathname: `/runs/${id}`, state },
  });
}

describe('RunDetailPage', () => {
  it('shows three filled and closed scenarios, each with the exact sample P&L', async () => {
    renderRun(SAMPLE_RUN_ID);
    const cards = await screen.findAllByRole('article');
    expect(cards).toHaveLength(3);
    for (const [index, card] of cards.entries()) {
      expect(within(card).getByText(`+${index + 1}`)).toBeInTheDocument();
      expect(within(card).getByText('FILLED')).toBeInTheDocument();
      expect(within(card).getByText(SAMPLE_PNL)).toBeInTheDocument();
      expect(within(card).getByText('10.05')).toBeInTheDocument();
      expect(within(card).getByText('9.950248756219')).toBeInTheDocument();
      expect(within(card).getByText('108.796567164180')).toBeInTheDocument();
    }
    expect(screen.getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
  });

  it('plots entry markers at slots 101/102/103 and exits at 106/107/108', async () => {
    renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    const chart = await screen.findByTestId('echarts');
    const option = JSON.parse(chart.getAttribute('data-option') ?? '{}') as {
      series: PlottedSeries[];
    };
    const series = (name: string) => option.series.find((s) => s.name === name)?.data ?? [];
    expect(series('Entry fills').map((d) => d.value)).toEqual([
      [101, 10.05],
      [102, 10.05],
      [103, 10.05],
    ]);
    expect(series('Exits').map((d) => d.value)).toEqual([
      [106, 10.945],
      [107, 10.945],
      [108, 10.945],
    ]);
  });

  it('shows a rejected scenario with its reason and an open position as exit unavailable', async () => {
    renderRun(EDGE_RUN_ID);
    const cards = await screen.findAllByRole('article');
    const [open, stale, invalid] = cards;

    expect(within(stale!).getByText('REJECTED')).toBeInTheDocument();
    expect(within(stale!).getByText(/Stale observation/)).toBeInTheDocument();
    expect(within(stale!).getByText('STALE_OBSERVATION')).toBeInTheDocument();
    expect(within(invalid!).getByText('INVALID_QUALITY')).toBeInTheDocument();

    expect(within(open!).getByText('OPEN')).toBeInTheDocument();
    expect(within(open!).getByText('Exit unavailable')).toBeInTheDocument();
    expect(within(open!).getByText(/Missing observation/)).toBeInTheDocument();
    expect(within(open!).getByText('MISSING_OBSERVATION')).toBeInTheDocument();
    // Tiny prices are displayed exactly, never in exponent form.
    expect(within(open!).getByText('0.0000000000124017')).toBeInTheDocument();
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

  it('shows ignored wallet events with their reason', async () => {
    renderRun(SAMPLE_RUN_ID);
    expect(await screen.findByText('UNSUPPORTED_ROUTE')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('renders exact values in every stored-record tab', async () => {
    const { user } = renderRun(SAMPLE_RUN_ID);
    await screen.findAllByRole('article');
    await user.click(screen.getByRole('tab', { name: 'Observations' }));
    expect(await within(screen.getByRole('tabpanel')).findAllByText('10')).toHaveLength(3);
    await user.click(screen.getByRole('tab', { name: 'Orders & fills' }));
    const orders = screen.getByRole('tabpanel');
    expect(await within(orders).findAllByText('9.950248756219')).toHaveLength(3);
    expect(within(orders).getAllByText('0.100000000000')).toHaveLength(3);
    expect(within(orders).getAllByText('100.100000000000')).toHaveLength(3);
    await user.click(screen.getByRole('tab', { name: 'Positions' }));
    const positions = screen.getByRole('tabpanel');
    expect(await within(positions).findAllByText(SAMPLE_PNL)).toHaveLength(3);
    expect(within(positions).getAllByText('108.796567164180')).toHaveLength(3);
  });

  it('shows the missing exit reason in the positions table too', async () => {
    const { user } = renderRun(EDGE_RUN_ID);
    await screen.findAllByRole('article');
    await user.click(screen.getByRole('tab', { name: 'Positions' }));
    const positions = screen.getByRole('tabpanel');
    expect(await within(positions).findByText('MISSING_OBSERVATION')).toBeInTheDocument();
    expect(within(positions).getByText('0.0000000000124017')).toBeInTheDocument();
  });

  it('shows an error for an unknown run', async () => {
    renderRun('does-not-exist');
    expect(await screen.findByText('Run does-not-exist not found')).toBeInTheDocument();
  });
});

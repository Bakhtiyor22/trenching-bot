import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  EDGE_DATASET_ID,
  SAMPLE_DATASET_ID,
  V4_DATASET_ID,
  WALLET,
  WATCHED_WALLET,
  sampleDataset,
  sampleObservations,
  v4Chart,
} from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { DatasetDetailPage } from './DatasetDetailPage';

function renderDataset(id: string) {
  return renderPage(<DatasetDetailPage />, { path: '/datasets/:id', url: `/datasets/${id}` });
}

describe('DatasetDetailPage', () => {
  beforeEach(() => localStorage.clear());

  it('shows the summary, chart and exact tiny decimal prices in its table', async () => {
    renderDataset(EDGE_DATASET_ID);
    expect(await screen.findByRole('heading', { name: 'edge-cases' })).toBeInTheDocument();
    await screen.findByText('0.00000000001234');
    const tables = screen.getAllByRole('table');
    const observations = tables.find((table) =>
      within(table).queryByRole('columnheader', { name: 'Price' }),
    );
    expect(observations).toBeDefined();
    expect(within(observations!).getByText('0.00000000001234')).toBeInTheDocument();
    expect(within(observations!).getByText('0.00000000001301')).toBeInTheDocument();
    expect(
      await screen.findByRole('img', { name: 'Market cap and trades over time' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start run with this dataset' })).toHaveAttribute(
      'href',
      `/runs/new?datasetId=${EDGE_DATASET_ID}`,
    );
  });

  it('explains when trader data was not recorded in a v3 dataset', async () => {
    renderDataset(SAMPLE_DATASET_ID);
    expect(
      await screen.findByText(
        "Decu's actions can't be placed: this dataset was recorded before trader data (v3).",
      ),
    ).toBeInTheDocument();
  });

  it('shows the valuation basis and persists watchlist wallet request parameters', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/datasets/:id/chart', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({ ...v4Chart, paperFills: [] });
      }),
    );
    const { user, unmount } = renderDataset(V4_DATASET_ID);
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
    expect(requests.at(-1)?.searchParams.get('pair')).toBeTruthy();
    unmount();
    renderDataset(V4_DATASET_ID);
    await screen.findByRole('button', { name: 'Valuation information' });
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.getAll('wallet')).toEqual([WALLET, WATCHED_WALLET]),
    );
  });

  it('shows a chart failure with a retry while keeping the observation table', async () => {
    server.use(
      http.get('/api/datasets/:id/chart', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Chart unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    renderDataset(SAMPLE_DATASET_ID);
    expect(await screen.findByText('Chart unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(await screen.findByRole('columnheader', { name: 'Price' })).toBeInTheDocument();
  });

  it('opens a chart action in the stored observations with a highlighted stable row', async () => {
    const { user } = renderDataset(V4_DATASET_ID);
    await user.click(await screen.findByRole('button', { name: 'Next Decu action' }));
    await user.click(await screen.findByRole('button', { name: /Open action/ }));
    const row = (await screen.findByText('v4-observation-0')).closest('tr');
    expect(row).toHaveAttribute('id', 'observation-v4-observation-0');
    expect(row).toHaveAttribute('data-highlighted', 'true');
    expect(screen.queryByText('◇ Bot paper fill')).not.toBeInTheDocument();
  });

  it('shows the optional trader column only for observations carrying a recorded trade', async () => {
    server.use(
      http.get('/api/datasets/:id/observations', () =>
        HttpResponse.json([
          {
            ...sampleObservations[0],
            trade: {
              trader: WATCHED_WALLET,
              side: 'BUY',
              solLamports: '1000000000',
              tokenRaw: '1000000',
            },
          },
          { ...sampleObservations[1], trade: null },
        ]),
      ),
    );
    renderDataset(SAMPLE_DATASET_ID);
    const trader = await screen.findByRole('columnheader', { name: 'Trader' });
    const table = trader.closest('table')!;
    expect(
      within(table).getByRole('button', { name: `Copy full value: ${WATCHED_WALLET}` }),
    ).toBeInTheDocument();
    expect(within(table).getByText('BUY')).toBeInTheDocument();
  });

  it('explains a dataset with no pairs without requesting observations', async () => {
    let observationsCalled = false;
    let chartCalled = false;
    server.use(
      http.get('/api/datasets/:id', () =>
        HttpResponse.json({
          ...sampleDataset,
          pairs: [],
          transactionCount: 0,
          observationCount: 0,
          firstSlot: null,
          lastSlot: null,
        }),
      ),
      http.get('/api/datasets/:id/observations', () => {
        observationsCalled = true;
        return HttpResponse.json([]);
      }),
      http.get('/api/datasets/:id/chart', () => {
        chartCalled = true;
        return HttpResponse.json(v4Chart);
      }),
    );
    renderDataset(SAMPLE_DATASET_ID);
    expect(await screen.findByText('This dataset has no pairs')).toBeInTheDocument();
    expect(observationsCalled).toBe(false);
    expect(chartCalled).toBe(false);
  });

  it('shows a loader failure and offers a retry', async () => {
    server.use(
      http.get('/api/datasets/:id/observations', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Observations unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    renderDataset(SAMPLE_DATASET_ID);
    expect(await screen.findByText('Observations unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('shows an unknown dataset', async () => {
    renderDataset('missing');
    expect(await screen.findByText('Dataset missing not found')).toBeInTheDocument();
  });
});

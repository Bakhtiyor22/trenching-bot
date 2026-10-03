import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { EDGE_DATASET_ID, SAMPLE_DATASET_ID, sampleDataset } from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { DatasetDetailPage } from './DatasetDetailPage';

function renderDataset(id: string) {
  return renderPage(<DatasetDetailPage />, { path: '/datasets/:id', url: `/datasets/${id}` });
}

describe('DatasetDetailPage', () => {
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
      await screen.findByRole('img', { name: 'Observed price by slot for the selected pair' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start run with this dataset' })).toHaveAttribute(
      'href',
      `/runs/new?datasetId=${EDGE_DATASET_ID}`,
    );
  });

  it('explains a dataset with no pairs without requesting observations', async () => {
    let observationsCalled = false;
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
    );
    renderDataset(SAMPLE_DATASET_ID);
    expect(await screen.findByText('This dataset has no pairs')).toBeInTheDocument();
    expect(observationsCalled).toBe(false);
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

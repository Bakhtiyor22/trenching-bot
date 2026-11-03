import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { pairKey } from '../../api/endpoints';
import { SAMPLE_DATASET_ID, sampleDataset } from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { DatasetDetailPage } from './DatasetDetailPage';
import { DatasetsPage } from './DatasetsPage';
import { pageFromParam, pairFromParam } from './datasetParams';

describe('dataset URL state', () => {
  it('reads only whole pages from 0 and only pairs the dataset has', () => {
    expect(pageFromParam('2')).toBe(2);
    expect(pageFromParam('abc')).toBe(0);
    expect(pageFromParam('-1')).toBe(0);
    expect(pageFromParam('1.5')).toBe(0);
    expect(pairFromParam('b/q', ['a/q', 'b/q'])).toBe('b/q');
    expect(pairFromParam('x/q', ['a/q', 'b/q'])).toBe('a/q');
    expect(pairFromParam(null, [])).toBeNull();
  });

  it('requests the list page named in ?page', async () => {
    const pages: string[] = [];
    server.use(
      http.get('/api/datasets', ({ request }) => {
        pages.push(new URL(request.url).searchParams.get('page') ?? '');
        return HttpResponse.json({ items: [sampleDataset], page: 1, size: 20, total: 21 });
      }),
    );
    renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets?page=1' });
    expect(await screen.findByRole('link', { name: sampleDataset.name })).toBeInTheDocument();
    expect(pages.at(-1)).toBe('1');
  });

  it('keeps the dataset list on screen when a refresh fails', async () => {
    const { queryClient } = renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    const link = await screen.findByRole('link', { name: sampleDataset.name });
    server.use(
      http.get('/api/datasets', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Down', fields: {} }, { status: 503 }),
      ),
    );
    await queryClient.refetchQueries({ queryKey: ['datasets'] });
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(link).toBeInTheDocument();
  });

  it('opens the pair named in ?pair and requests its observations', async () => {
    const pair = pairKey(sampleDataset.pairs[1]!);
    const requested: string[] = [];
    server.use(
      http.get('/api/datasets/:id/observations', ({ request }) => {
        requested.push(new URL(request.url).searchParams.get('pair') ?? '');
        return HttpResponse.json([]);
      }),
    );
    renderPage(<DatasetDetailPage />, {
      path: '/datasets/:id',
      url: `/datasets/${SAMPLE_DATASET_ID}?pair=${encodeURIComponent(pair)}`,
    });
    expect(await screen.findByRole('region', { name: 'Dataset contents' })).toBeInTheDocument();
    await waitFor(() => expect(requested).toContain(pair));
  });
});

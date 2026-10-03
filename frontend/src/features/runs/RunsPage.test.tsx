import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  EDGE_DATASET_ID,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  SAMPLE_TOTAL_PNL,
  sampleRun,
} from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { RunsPage } from './RunsPage';

function renderRuns(url = '/runs') {
  return renderPage(<RunsPage />, {
    path: '/runs',
    url,
    extraRoutes: [{ path: '/runs/:id', element: <div>Run detail</div> }],
  });
}

describe('RunsPage', () => {
  it('shows exact summaries and opens a run from its link', async () => {
    const { user, router } = renderRuns();
    const table = await screen.findByRole('table');
    expect(within(table).getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
    expect(within(table).getByText('0.000000000000')).toBeInTheDocument();
    await user.click(within(table).getByRole('link', { name: '4f0c…c5d6' }));
    await screen.findByText('Run detail');
    expect(router.state.location.pathname).toBe(`/runs/${SAMPLE_RUN_ID}`);
  });

  it('filters by the dataset in the URL and keeps it in the new run link', async () => {
    renderRuns(`/runs?datasetId=${EDGE_DATASET_ID}`);
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getByText('0.000000000000')).toBeInTheDocument();
    expect(within(table).queryByText(SAMPLE_TOTAL_PNL)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New run' })).toHaveAttribute(
      'href',
      `/runs/new?datasetId=${EDGE_DATASET_ID}`,
    );
  });

  it('requests the next page and resets the page when the filter changes', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/runs', ({ request }) => {
        const url = new URL(request.url);
        requests.push(url);
        const page = Number(url.searchParams.get('page'));
        return HttpResponse.json({ items: [sampleRun], page, size: 20, total: 21 });
      }),
    );
    const { user, router } = renderRuns();
    await screen.findByRole('table');
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'));
    const filter = screen.getByRole('combobox', { name: 'Filter by dataset' });
    await user.type(filter, 'weekly');
    await user.click(await screen.findByRole('option', { name: /weekly-buy-v1/ }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.get('datasetId')).toBe(SAMPLE_DATASET_ID),
    );
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
    expect(router.state.location.search).not.toContain('page=');
  });

  it.each(['1.5', 'Infinity', '-1', '9007199254740992'])(
    'uses the first page for invalid page %s',
    async (page) => {
      let requestedPage: string | null = null;
      server.use(
        http.get('/api/runs', ({ request }) => {
          requestedPage = new URL(request.url).searchParams.get('page');
          return HttpResponse.json({ items: [], page: 0, size: 20, total: 0 });
        }),
      );
      renderRuns(`/runs?page=${page}`);
      await screen.findByText('No runs yet');
      expect(requestedPage).toBe('0');
    },
  );

  it('shows a failed list request with the backend message', async () => {
    server.use(
      http.get('/api/runs', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Runs unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    renderRuns();
    expect(await screen.findByText('Runs unavailable')).toBeInTheDocument();
  });
});

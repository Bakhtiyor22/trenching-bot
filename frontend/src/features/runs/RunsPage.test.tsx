import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import type { RunPage, RunSummary } from '../../api/types';
import { formatSignedDecimal } from '../../lib/decimal';
import { shortId } from '../../lib/format';
import {
  EDGE_DATASET_ID,
  EDGE_RUN_ID,
  REAL_MINT,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  SAMPLE_TOTAL_PNL,
  sampleRun,
} from '../../mocks/fixtures';
import { listedRuns } from '../../mocks/runListFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { RunsPage } from './RunsPage';

const SIGNED_TOTAL_PNL = formatSignedDecimal(SAMPLE_TOTAL_PNL);
const SAMPLE_LINK = '4f0c…c5d6';
const EDGE_LINK = '9a8b…1e0f';
const SNAPSHOT = '2026-11-16T09:00:00.000Z';
const NO_COUNTS = { PROFIT: 0, LOSS: 0, FLAT: 0, NO_ENTRY: 0, OPEN: 0 };

function renderRuns(url = '/runs') {
  return renderPage(<RunsPage />, {
    path: '/runs',
    url,
    extraRoutes: [
      { path: '/runs/:id', element: <div>Run detail</div> },
      { path: '/compare', element: <div>Compare</div> },
      { path: '/automation/days/:day', element: <div>Day page</div> },
    ],
  });
}

function runPage(items: RunSummary[], page: number, total: number, asOf = SNAPSHOT): RunPage {
  return { items, page, size: 20, total, asOf, statusCounts: { ...NO_COUNTS, LOSS: total } };
}

/** Records every `GET /api/runs` the page sends, answering from the default mock. */
function recordRequests(): URL[] {
  const requests: URL[] = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    // The page's own list requests; the size-1 ones only count the runs that arrived since the snapshot.
    if (url.pathname === '/api/runs' && url.searchParams.get('size') !== '1') requests.push(url);
  });
  return requests;
}

describe('RunsPage', () => {
  afterEach(() => server.events.removeAllListeners());

  it('finds a loss that is only on page 2: the status filter runs on the server, not on the loaded page', async () => {
    const requests = recordRequests();
    const { user } = renderRuns();
    const table = await screen.findByRole('table');
    // Page 1 holds the 20 newest runs; the sample run, the only loss, is on page 2.
    expect(within(table).getAllByRole('row')).toHaveLength(21);
    expect(within(table).queryByRole('link', { name: SAMPLE_LINK })).not.toBeInTheDocument();
    expect(screen.getByText(/24 runs/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /^Loss/ }));
    expect(await within(table).findByRole('link', { name: SAMPLE_LINK })).toBeInTheDocument();
    expect(within(table).getByText(SIGNED_TOTAL_PNL)).toBeInTheDocument();
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(requests.at(-1)?.searchParams.get('status')).toBe('LOSS');
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
  });

  it('shows each status with its count from the server', async () => {
    renderRuns();
    await screen.findByRole('table');
    expect(screen.getByRole('radio', { name: 'All 24' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Loss 1' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'No entry 4' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Open 5' })).toBeInTheDocument();
  });

  it('shows the dataset name from the server and links a sweep run to the day of its buy', async () => {
    const { user, router } = renderRuns();
    const table = await screen.findByRole('table');
    const newest = listedRuns[0]!;
    expect(within(table).getAllByText(newest.datasetName!)[0]).toBeInTheDocument();
    const fromBuy = await within(table).findAllByRole('link', { name: /^From buy: trading day/ });
    await user.click(fromBuy[0]!);
    expect(router.state.location.pathname).toMatch(/^\/automation\/days\/2026-\d\d-\d\d$/);
  });

  it('searches all runs by text after a pause and opens a run from its link', async () => {
    const requests = recordRequests();
    const { user, router } = renderRuns();
    await screen.findByRole('table');
    await user.type(screen.getByRole('textbox', { name: 'Search all runs' }), '9a8b');
    // A slow test machine can pause between key strokes and search a partial text first; the last request wins.
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('q')).toBe('9a8b'));
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2));
    expect(within(table).getByRole('link', { name: EDGE_LINK })).toBeInTheDocument();
    // Typing sends a request after a pause, not one per key stroke.
    expect(requests.filter((r) => r.searchParams.has('q')).length).toBeLessThan(4);
    expect(new URLSearchParams(router.state.location.search).get('q')).toBe('9a8b');
    await user.click(within(table).getByRole('link', { name: EDGE_LINK }));
    expect(router.state.location.pathname).toBe(`/runs/${EDGE_RUN_ID}`);
  });

  it('does not send a one-letter search or an invalid token, and says why', async () => {
    const requests = recordRequests();
    const { user } = renderRuns();
    await screen.findByRole('table');
    await user.type(screen.getByRole('textbox', { name: 'Search all runs' }), 'd');
    await user.type(screen.getByRole('textbox', { name: 'Filter by token mint' }), 'not-a-mint');
    expect(await screen.findByText('Type at least 2 characters.')).toBeInTheDocument();
    expect(screen.getByText('Not a Solana token address.')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(requests.some((r) => r.searchParams.has('q') || r.searchParams.has('mint'))).toBe(false);
  });

  it('filters by token and names the filters when nothing matches, then clears them', async () => {
    const { user, router } = renderRuns(`/runs?status=LOSS&mint=${REAL_MINT}`);
    expect(await screen.findByText('No runs match')).toBeInTheDocument();
    expect(screen.getByText(`No run is Loss · token ${shortId(REAL_MINT)}.`)).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Clear filters' }).at(-1)!);
    expect(await screen.findByRole('table')).toBeInTheDocument();
    const search = new URLSearchParams(router.state.location.search);
    expect(search.has('status')).toBe(false);
    expect(search.has('mint')).toBe(false);
  });

  it('cuts a trading day on the automation clock', async () => {
    const requests = recordRequests();
    renderRuns('/runs?day=2026-11-01');
    await waitFor(() => expect(requests.some((r) => r.searchParams.has('anchorFrom'))).toBe(true));
    const sent = requests.find((r) => r.searchParams.has('anchorFrom'))!;
    expect(sent.searchParams.get('anchorFrom')).toBe('2026-11-01T07:00:00.000Z');
    expect(sent.searchParams.get('anchorTo')).toBe('2026-11-02T07:00:00.000Z');
  });

  it('compares the ticked runs', async () => {
    const { user, router } = renderRuns('/runs?status=LOSS');
    const table = await screen.findByRole('table');
    await within(table).findByRole('link', { name: SAMPLE_LINK });
    await user.click(
      screen.getByRole('checkbox', { name: `Select run ${SAMPLE_LINK} to compare` }),
    );
    await user.click(screen.getByRole('radio', { name: /^Open/ }));
    await user.click(
      await screen.findByRole('checkbox', { name: `Select run ${EDGE_LINK} to compare` }),
    );
    await user.click(screen.getByRole('link', { name: /Compare 2/ }));
    expect(router.state.location.pathname).toBe('/compare');
    expect(router.state.location.search).toBe(`?runs=${SAMPLE_RUN_ID},${EDGE_RUN_ID}`);
  });

  it('filters by the dataset in the URL and keeps it in the new run link', async () => {
    renderRuns(`/runs?datasetId=${EDGE_DATASET_ID}`);
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getByText('0.000000000000')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New run' })).toHaveAttribute(
      'href',
      `/runs/new?datasetId=${EDGE_DATASET_ID}`,
    );
    expect(await screen.findByText('Runs on edge-cases')).toBeInTheDocument();
  });

  it('searches datasets on the server and resets the page and snapshot when the dataset changes', async () => {
    const requests = recordRequests();
    const datasetSearches: string[] = [];
    server.events.on('request:start', ({ request }) => {
      const url = new URL(request.url);
      if (url.pathname === '/api/datasets' && url.searchParams.has('q'))
        datasetSearches.push(url.searchParams.get('q')!);
    });
    const { user, router } = renderRuns();
    await screen.findByRole('table');
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'));
    const filter = screen.getByRole('combobox', { name: 'Filter by dataset' });
    await user.type(filter, 'dip');
    await waitFor(() => expect(datasetSearches).toContain('dip'));
    await user.click(await screen.findByRole('option', { name: /dip-sample-v3/ }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.get('datasetId')).toBe(SAMPLE_DATASET_ID),
    );
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
    expect(requests.at(-1)?.searchParams.has('asOf')).toBe(false);
    expect(router.state.location.search).not.toContain('page=');
  });

  it('pins the first page snapshot in the URL and pages from it', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/runs', ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get('size') !== '1') requests.push(url);
        const page = Number(url.searchParams.get('page'));
        return HttpResponse.json(runPage([sampleRun], page, 21));
      }),
    );
    const { user, router } = renderRuns();
    await screen.findByRole('table');
    await waitFor(() =>
      expect(new URLSearchParams(router.state.location.search).get('asOf')).toBe(SNAPSHOT),
    );
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'));
    expect(requests.at(-1)?.searchParams.get('asOf')).toBe(SNAPSHOT);
    // Pinning the snapshot reuses the first answer instead of asking again.
    expect(requests.filter((r) => r.searchParams.get('page') === '0')).toHaveLength(1);
  });

  it('offers the runs created since the snapshot and shows them on request', async () => {
    const LATER = '2026-11-16T09:05:00.000Z';
    let now = SNAPSHOT;
    server.use(
      http.get('/api/runs', ({ request }) => {
        const asOf = new URL(request.url).searchParams.get('asOf');
        // Three runs arrive after the snapshot: the live total grows, the snapshot's does not.
        const total = asOf === SNAPSHOT ? 21 : 24;
        return HttpResponse.json(runPage([sampleRun], 0, total, asOf ?? now));
      }),
    );
    const { user, router } = renderRuns(`/runs?asOf=${SNAPSHOT}`);
    expect(await screen.findByText(/new runs since this list was loaded/)).toHaveTextContent(
      '3 new runs',
    );
    now = LATER;
    await user.click(screen.getByRole('button', { name: 'Show' }));
    await waitFor(() =>
      expect(new URLSearchParams(router.state.location.search).get('asOf')).toBe(LATER),
    );
  });

  it.each(['1.5', 'Infinity', '-1', '9007199254740992'])(
    'uses the first page for invalid page %s',
    async (page) => {
      let requestedPage: string | null = null;
      server.use(
        http.get('/api/runs', ({ request }) => {
          requestedPage = new URL(request.url).searchParams.get('page');
          return HttpResponse.json(runPage([], 0, 0));
        }),
      );
      renderRuns(`/runs?page=${page}`);
      await screen.findByText('No runs yet');
      expect(requestedPage).toBe('0');
    },
  );

  it('keeps the search, status and sort in the URL and restores them from it', async () => {
    const { user, router } = renderRuns();
    await screen.findByRole('table');
    await user.type(screen.getByRole('textbox', { name: 'Search all runs' }), '4f0c');
    await waitFor(() =>
      expect(new URLSearchParams(router.state.location.search).get('q')).toBe('4f0c'),
    );
    await user.click(screen.getByRole('radio', { name: /^Loss/ }));
    const table = await screen.findByRole('table');
    await within(table).findByRole('link', { name: SAMPLE_LINK });
    await user.click(within(table).getByRole('button', { name: /Realized P&L/ }));
    const search = new URLSearchParams(router.state.location.search);
    expect(search.get('q')).toBe('4f0c');
    expect(search.get('status')).toBe('LOSS');
    expect(search.get('sort')).toBe('pnl');
  });

  it('opens with the filters from the URL', async () => {
    renderRuns('/runs?q=9a8b&status=OPEN');
    const table = await screen.findByRole('table');
    expect(screen.getByRole('textbox', { name: 'Search all runs' })).toHaveValue('9a8b');
    expect(screen.getByRole('radio', { name: /^Open/ })).toBeChecked();
    expect(within(table).getByRole('link', { name: EDGE_LINK })).toBeInTheDocument();
    expect(within(table).queryByText(SIGNED_TOTAL_PNL)).not.toBeInTheDocument();
  });

  it('keeps the table when a refresh fails', async () => {
    const { queryClient } = renderRuns('/runs?status=LOSS');
    const table = await screen.findByRole('table');
    await within(table).findByText(SIGNED_TOTAL_PNL);
    server.use(
      http.get('/api/runs', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Runs unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    await queryClient.refetchQueries({ queryKey: ['runs'] });
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(within(table).getByText(SIGNED_TOTAL_PNL)).toBeInTheDocument();
  });

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

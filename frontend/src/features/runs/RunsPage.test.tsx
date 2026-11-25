import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import type { RunPage, RunSummary } from '../../api/types';
import {
  addDecimals,
  formatSignedDecimal,
  multiplyDecimals,
  truncateDecimal,
} from '../../lib/decimal';
import { WRAPPED_SOL_MINT } from '../../lib/format';
import { shortId } from '../../lib/format';
import {
  EDGE_DATASET_ID,
  EDGE_RUN_ID,
  REAL_MINT,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  oldShapeRun,
  SAMPLE_TOTAL_PNL,
  sampleRun,
} from '../../mocks/fixtures';
import { listedRuns, SHARED_LABEL, USDC_MINT } from '../../mocks/runListFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { RunsPage } from './RunsPage';

/** As the list shows P&L: signed, trailing zeros trimmed, the unit after it. */
const SIGNED_TOTAL_PNL = formatSignedDecimal(SAMPLE_TOTAL_PNL, { trim: true });
/** The live rate the USDT tests pin, and its time. */
const RATE = { rate: '69.08', asOf: '2026-11-16T15:51:30Z', source: 'binance:SOLUSDT:ticker' };
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
    // The page's own list requests; the size-0 ones only count the runs that arrived since the snapshot.
    if (url.pathname === '/api/runs' && url.searchParams.get('size') !== '0') requests.push(url);
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
    expect(screen.getByText(/25 runs/)).toBeInTheDocument();

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
    expect(screen.getByRole('radio', { name: 'All 25' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Loss 1' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Not bought 4' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Still held 5' })).toBeInTheDocument();
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
    expect(
      screen.getByText(`No run is at a loss · token ${shortId(REAL_MINT)}.`),
    ).toBeInTheDocument();
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
    await user.click(screen.getByRole('radio', { name: /^Still held/ }));
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
    // Its three positions: one still held, two never bought; nothing closed, so no realized result.
    expect(
      within(table).getByLabelText('Realized P&L: none. Still held, nothing sold yet'),
    ).toBeInTheDocument();
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
        if (url.searchParams.get('size') !== '0') requests.push(url);
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
    expect(screen.getByRole('radio', { name: /^Still held/ })).toBeChecked();
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

  it('works against a backend from before server-side search, and says its filters are not applied', async () => {
    const requests: URL[] = [];
    const oldShape = oldShapeRun(sampleRun);
    server.use(
      http.get('/api/runs', ({ request }) => {
        requests.push(new URL(request.url));
        // The old RunPageResponse: no asOf, no statusCounts, and every filter but datasetId ignored.
        return HttpResponse.json({ items: [oldShape], page: 0, size: 20, total: 1 });
      }),
    );
    renderRuns('/runs?status=PROFIT');
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('link', { name: SAMPLE_LINK })).toBeInTheDocument();
    expect(
      screen.getByText(
        /This backend does not support server-side filters yet; filters apply after it is updated/,
      ),
    ).toBeInTheDocument();
    // The unfiltered list is not presented as a filtered one, and no count is made up.
    expect(screen.getByRole('heading', { name: 'All runs' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Loss' })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /^Loss \d/ })).toBeNull();
    expect(requests[0]?.searchParams.get('status')).toBe('PROFIT');
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

describe('RunsPage columns', () => {
  afterEach(() => server.events.removeAllListeners());

  const useRate = (body: Record<string, string | null>) =>
    server.use(
      http.get('/api/market/sol-usdt', () =>
        HttpResponse.json({
          rate: null,
          asOf: null,
          source: null,
          unavailableReason: null,
          ...body,
        }),
      ),
    );
  const rowOf = (table: HTMLElement, runId: string) =>
    within(table)
      .getByRole('link', { name: shortId(runId) })
      .closest('tr')!;

  it('shows one Trade column instead of the count columns, and no quote mint column', async () => {
    renderRuns();
    const table = await screen.findByRole('table');
    const headers = within(table)
      .getAllByRole('columnheader')
      .map((h) => h.textContent?.replace(/[↓↑]/g, '').trim());
    expect(headers).toEqual([
      'Compare',
      'Run',
      'Variation',
      'Created',
      'Dataset',
      'Token',
      'Buy',
      'Trade',
      'Realized P&L',
    ]);
    const newest = rowOf(table, listedRuns[0]!.id);
    expect(within(newest).getByText('Sold')).toBeInTheDocument();
    const noEntry = listedRuns.find((r) => r.status === 'NO_ENTRY')!;
    expect(within(rowOf(table, noEntry.id)).getByText('Not bought')).toBeInTheDocument();
    const open = listedRuns.find((r) => r.status === 'OPEN')!;
    expect(within(rowOf(table, open.id)).getByText('Still held')).toBeInTheDocument();
  });

  it('counts the tokens of a multi-token run on a second line', async () => {
    renderRuns('/runs?status=OPEN');
    const table = await screen.findByRole('table');
    await waitFor(() =>
      expect(within(table).getByRole('link', { name: EDGE_LINK })).toBeInTheDocument(),
    );
    expect(
      within(rowOf(table, EDGE_RUN_ID)).getByText('1 of 3 tokens bought · 1 still held'),
    ).toBeInTheDocument();
  });

  it('links each variation that swept a run, and shows a dash for a run none did', async () => {
    renderRuns();
    const table = await screen.findByRole('table');
    const newest = listedRuns[0]!;
    expect(
      within(rowOf(table, newest.id)).getByRole('link', {
        name: `Open variation ${newest.variations![0]}`,
      }),
    ).toHaveAttribute('href', `/automation/variations/${newest.variations![0]}`);
    const shared = rowOf(table, listedRuns[1]!.id);
    expect(within(shared).getAllByRole('link', { name: /^Open variation/ })).toHaveLength(2);
    expect(
      within(shared).getByRole('link', { name: `Open variation ${SHARED_LABEL}` }),
    ).toBeInTheDocument();
  });

  it('links a V03 run to the V03 page', async () => {
    renderRuns();
    const table = await screen.findByRole('table');
    const v03 = listedRuns.find((r) => r.variations?.[0] === 'V03')!;
    expect(
      within(rowOf(table, v03.id)).getByRole('link', { name: 'Open variation V03' }),
    ).toHaveAttribute('href', '/automation/variations/V03');
  });

  it('shows a dash with its reason when no variation swept the run', async () => {
    renderRuns('/runs?status=LOSS');
    const table = await screen.findByRole('table');
    await waitFor(() =>
      expect(within(table).getByRole('link', { name: SAMPLE_LINK })).toBeInTheDocument(),
    );
    const row = rowOf(table, SAMPLE_RUN_ID);
    expect(within(row).queryByRole('link', { name: /^Open variation/ })).not.toBeInTheDocument();
    expect(within(row).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('opens the token on pump.fun in a new tab without opening the run', async () => {
    const { user, router } = renderRuns();
    const table = await screen.findByRole('table');
    const newest = listedRuns[0]!;
    const row = rowOf(table, newest.id);
    const link = within(row).getByRole('link', {
      name: `Open token ${newest.mints[0]} on pump.fun`,
    });
    expect(link).toHaveAttribute('href', `https://pump.fun/coin/${newest.mints[0]}`);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    await user.click(link);
    expect(router.state.location.pathname).toBe('/runs');
    // A click elsewhere on the row still opens the run.
    await user.click(within(row).getByText('Sold'));
    expect(router.state.location.pathname).toBe(`/runs/${newest.id}`);
  });

  it('shows the P&L in SOL and its value now in USDT, exact on hover', async () => {
    useRate(RATE);
    const { user } = renderRuns();
    const table = await screen.findByRole('table');
    const row = rowOf(table, listedRuns[0]!.id);
    expect(within(row).getByText('+0.024477624')).toHaveTextContent('+0.024477624SOL');
    const usdt = await within(row).findByText('≈ +1.69 USDT');
    expect(screen.getByText(/USDT at 1 SOL = 69\.08 USDT \(now/)).toBeInTheDocument();
    await user.hover(usdt);
    expect(await screen.findByText('+1.69091426592 USDT')).toBeInTheDocument();
    expect(screen.getByText(/^1 SOL = 69\.08 USDT, Binance, \d\d:\d\d:\d\d$/)).toBeInTheDocument();
    expect(
      screen.getByText("The run's page shows USDT at each trade's own rate."),
    ).toBeInTheDocument();
  });

  it('shows no USDT line without a rate, and the hint says why', async () => {
    useRate({ unavailableReason: 'MARKET_DATA_DISABLED' });
    renderRuns();
    const table = await screen.findByRole('table');
    expect(await screen.findByText(/USDT unavailable: /)).toBeInTheDocument();
    expect(within(table).queryByText(/USDT$/)).not.toBeInTheDocument();
  });

  it('shows a run in another quote with its short mint and no USDT line', async () => {
    useRate(RATE);
    const usdc = listedRuns.find((r) => r.quoteMint === USDC_MINT)!;
    renderRuns(`/runs?q=${usdc.id.slice(0, 8)}`);
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2));
    const row = rowOf(table, usdc.id);
    expect(within(row).getByText('+1.25')).toHaveTextContent(`+1.25${shortId(USDC_MINT)}`);
    expect(within(row).queryByText(/USDT$/)).not.toBeInTheDocument();
    expect(within(row).getByText('Sold')).toBeInTheDocument();
  });

  it('opens newest first from an old link sorted by scenarios', async () => {
    renderRuns('/runs?sort=scenarios');
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')[1]).toBe(rowOf(table, listedRuns[0]!.id));
  });
});

describe('RunsPage P&L of runs that realized nothing', () => {
  afterEach(() => server.events.removeAllListeners());

  const pnlCell = (row: HTMLElement) => within(row).getAllByRole('cell').at(-1)!;
  const rowOf = (table: HTMLElement, runId: string) =>
    within(table)
      .getByRole('link', { name: shortId(runId) })
      .closest('tr')!;
  const withRate = () =>
    server.use(
      http.get('/api/market/sol-usdt', () =>
        HttpResponse.json({ ...RATE, unavailableReason: null }),
      ),
    );

  it('shows a dash for a run never bought, with why, and no unit or USDT', async () => {
    withRate();
    const { user } = renderRuns();
    const table = await screen.findByRole('table');
    await screen.findByText(/USDT at 1 SOL = 69\.08 USDT/);
    const run = listedRuns.find((r) => r.status === 'NO_ENTRY')!;
    const cell = pnlCell(rowOf(table, run.id));
    expect(cell).toHaveTextContent(/^—$/);
    await user.hover(within(cell).getByText('—'));
    expect(await screen.findByText('Not bought, so nothing was realized')).toBeInTheDocument();
  });

  it('shows a dash for a one-token run still held, with why', async () => {
    withRate();
    const { user } = renderRuns();
    const table = await screen.findByRole('table');
    await screen.findByText(/USDT at 1 SOL = 69\.08 USDT/);
    const run = listedRuns.find((r) => r.status === 'OPEN' && r.scenarioCount === 1)!;
    const cell = pnlCell(rowOf(table, run.id));
    expect(cell).toHaveTextContent(/^—$/);
    await user.hover(within(cell).getByText('—'));
    expect(await screen.findByText('Still held, nothing sold yet')).toBeInTheDocument();
  });

  it('keeps 0 SOL for a flat sale, without a USDT line', async () => {
    withRate();
    renderRuns();
    const table = await screen.findByRole('table');
    await screen.findByText(/USDT at 1 SOL = 69\.08 USDT/);
    const run = listedRuns.find((r) => r.status === 'FLAT')!;
    const cell = pnlCell(rowOf(table, run.id));
    expect(cell).toHaveTextContent(/^0SOL$/);
  });

  it('keeps the profit and its USDT line', async () => {
    withRate();
    renderRuns();
    const table = await screen.findByRole('table');
    const cell = pnlCell(rowOf(table, listedRuns[0]!.id));
    expect(await within(cell).findByText('≈ +1.69 USDT')).toBeInTheDocument();
    expect(cell).toHaveTextContent('+0.024477624SOL');
  });
});

describe('RunsPage totals and exit reasons', () => {
  afterEach(() => server.events.removeAllListeners());

  const useRate = () =>
    server.use(
      http.get('/api/market/sol-usdt', () =>
        HttpResponse.json({ ...RATE, unavailableReason: null }),
      ),
    );
  const rowOf = (table: HTMLElement, runId: string) =>
    within(table)
      .getByRole('link', { name: shortId(runId) })
      .closest('tr')!;

  it('totals the realized P&L of every matching run, in SOL and in USDT now', async () => {
    useRate();
    renderRuns('/runs?status=PROFIT');
    const strip = await screen.findByRole('region', { name: 'Totals of matching runs' });
    const profits = [...listedRuns, sampleRun].filter(
      (r) => r.status === 'PROFIT' && r.quoteMint === WRAPPED_SOL_MINT,
    );
    const sum = addDecimals('0.000000000000', ...profits.map((r) => r.realizedPnl));
    const usdt = multiplyDecimals(sum, RATE.rate);
    await waitFor(() =>
      expect(within(strip).getByText(formatSignedDecimal(sum, { trim: true }))).toBeInTheDocument(),
    );
    expect(within(strip).getByText('Runs matching').nextSibling).toHaveTextContent(
      String(profits.length + 1),
    );
    expect(
      within(strip).getByText(`≈ ${formatSignedDecimal(truncateDecimal(usdt, 2))} USDT`),
    ).toBeInTheDocument();
    expect(
      within(strip).getByText('Realized only: still-held positions are not in it.'),
    ).toBeInTheDocument();
  });

  it('shows a total in another quote mint on its own line, never added to SOL', async () => {
    renderRuns('/runs?status=PROFIT');
    expect(await screen.findByText(/Also 1 run quoted in/)).toHaveTextContent(
      `Also 1 run quoted in ${shortId(USDC_MINT)}: +1.25${shortId(USDC_MINT)} realized`,
    );
  });

  it('shows no total from a backend that sends none', async () => {
    server.use(
      http.get('/api/runs', ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get('size') === '0') return HttpResponse.json(runPage([], 0, 1));
        return HttpResponse.json(runPage([sampleRun], 0, 1));
      }),
    );
    renderRuns();
    await screen.findByRole('table');
    expect(
      screen.queryByRole('region', { name: 'Totals of matching runs' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Total realized P&L')).not.toBeInTheDocument();
  });

  it('says why a one-token run sold, did not buy or still holds, explained on hover', async () => {
    const { user } = renderRuns();
    const table = await screen.findByRole('table');
    const newest = rowOf(table, listedRuns[0]!.id);
    expect(within(newest).getByText('Sold')).toBeInTheDocument();
    const reason = within(newest).getByText('Take profit');
    const notBought = listedRuns.find((r) => r.status === 'NO_ENTRY')!;
    expect(within(rowOf(table, notBought.id)).getByText('No dip')).toBeInTheDocument();
    const held = listedRuns.find((r) => r.status === 'OPEN')!;
    expect(within(rowOf(table, held.id)).getByText('Still open at the end')).toBeInTheDocument();
    const decuSold = listedRuns.find((r) => r.outcomes?.[0]?.code === 'WALLET_SOLD')!;
    expect(within(rowOf(table, decuSold.id)).getByText('Decu sold')).toBeInTheDocument();
    await user.hover(reason);
    expect(
      await screen.findByText('Sold when the price reached the take-profit level.'),
    ).toBeInTheDocument();
  });

  it("lists a multi-token run's reasons with their counts on hover", async () => {
    const { user } = renderRuns('/runs?status=LOSS');
    const table = await screen.findByRole('table');
    await waitFor(() =>
      expect(within(table).getByRole('link', { name: SAMPLE_LINK })).toBeInTheDocument(),
    );
    const row = rowOf(table, SAMPLE_RUN_ID);
    expect(within(row).queryByText('Take profit')).not.toBeInTheDocument();
    await user.hover(within(row).getByText('2 of 3 tokens bought'));
    expect(await screen.findByText('1 sold · Stop loss')).toBeInTheDocument();
    expect(screen.getByText('1 sold · Take profit')).toBeInTheDocument();
    expect(screen.getByText('1 not bought · No dip')).toBeInTheDocument();
  });
});

describe('RunsPage review fixes', () => {
  afterEach(() => server.events.removeAllListeners());

  const rowOf = (table: HTMLElement, runId: string) =>
    within(table)
      .getByRole('link', { name: shortId(runId) })
      .closest('tr')!;

  it('fades the previous total while the next tab loads', async () => {
    server.use(
      http.get('/api/runs', async ({ request }) => {
        if (new URL(request.url).searchParams.get('status') === 'LOSS') await delay(400);
        return undefined;
      }),
    );
    const { user } = renderRuns();
    const strip = await screen.findByRole('region', { name: 'Totals of matching runs' });
    const wrapper = strip.parentElement!.parentElement!;
    expect(wrapper).not.toHaveAttribute('data-stale');
    await user.click(screen.getByRole('radio', { name: /^Loss/ }));
    await waitFor(() => expect(wrapper).toHaveAttribute('data-stale', 'true'));
    expect(wrapper).toHaveStyle({ opacity: '0.6' });
    await waitFor(() =>
      expect(
        screen.getByRole('region', { name: 'Totals of matching runs' }).parentElement!
          .parentElement!,
      ).not.toHaveAttribute('data-stale'),
    );
  });

  it("says each run's own page has trade-time USDT under the total, and the run's page under a row", async () => {
    server.use(
      http.get('/api/market/sol-usdt', () =>
        HttpResponse.json({ ...RATE, unavailableReason: null }),
      ),
    );
    const { user } = renderRuns();
    const strip = await screen.findByRole('region', { name: 'Totals of matching runs' });
    await user.hover(await within(strip).findByText(/^≈ .* USDT$/));
    expect(
      await screen.findByText("Each run's own page shows USDT at its trades' own rates."),
    ).toBeInTheDocument();
    await user.unhover(within(strip).getByText(/^≈ .* USDT$/));
    const table = screen.getByRole('table');
    await user.hover(within(rowOf(table, listedRuns[0]!.id)).getByText('≈ +1.69 USDT'));
    expect(
      await screen.findByText("The run's page shows USDT at each trade's own rate."),
    ).toBeInTheDocument();
  });

  it('opens a tooltip from the keyboard: the Trade reason and the dash of a run that sold nothing', async () => {
    renderRuns();
    const table = await screen.findByRole('table');
    const reason = within(rowOf(table, listedRuns[0]!.id)).getByText('Take profit');
    expect(reason).toHaveAttribute('tabindex', '0');
    act(() => reason.focus());
    expect(
      await screen.findByText('Sold when the price reached the take-profit level.'),
    ).toBeInTheDocument();
    const notBought = listedRuns.find((r) => r.status === 'NO_ENTRY')!;
    const dash = within(rowOf(table, notBought.id)).getByLabelText(/^Realized P&L: none/);
    expect(dash).toHaveAttribute('tabindex', '0');
    act(() => dash.focus());
    expect(await screen.findByText('Not bought, so nothing was realized')).toBeInTheDocument();
  });

  it('asks only for the count when it checks for new runs', async () => {
    const counts: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      const url = new URL(request.url);
      if (url.pathname === '/api/runs' && url.searchParams.get('size') === '0') counts.push(url);
    });
    renderRuns();
    await screen.findByRole('table');
    await waitFor(() => expect(counts.length).toBeGreaterThan(0));
    expect(counts.every((u) => !u.searchParams.has('asOf'))).toBe(true);
  });
});

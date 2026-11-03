import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAutomationDb, discoveryOnly, MOCK_ZONE } from '../../mocks/automationFixtures';
import { resetAutomationMocks } from '../../mocks/automationHandlers';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { AutomationItemRedirect } from './AutomationItemRedirect';
import { AutomationNightPage } from './AutomationNightPage';
import { AutomationNightsPage } from './AutomationNightsPage';
import { AutomationOverviewPage } from './AutomationOverviewPage';
import { AutomationSweepRunsPage } from './AutomationSweepRunsPage';
import { nightOf } from './automationModel';

/** The fixtures' tonight: Mon 2 Nov 2026, 22:00 UTC+5. */
const NOW = new Date('2026-11-02T17:00:00Z');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

const db = createAutomationDb();
const closed = db.sessions.filter((s) => s.state === 'CLOSED');
const latest = closed[closed.length - 1]!;
const tonight = db.sessions[db.sessions.length - 1]!;

function renderOverview(url = '/automation') {
  return renderPage(<AutomationOverviewPage />, {
    path: '/automation',
    url,
    extraRoutes: [{ path: '/automation/nights/:id', element: <p>Night page</p> }],
  });
}

describe('AutomationOverviewPage', () => {
  it('shows the status strip, the latest scored night and the scoreboard in the backend order', async () => {
    renderOverview();
    const strip = await screen.findByRole('region', { name: 'Automation status' });
    expect(within(strip).getByText('Capturing')).toBeInTheDocument();
    expect(within(strip).getByText('18:00–05:00')).toBeInTheDocument();
    expect(within(strip).getByText(/UTC\+5 · in window now/)).toBeInTheDocument();
    expect(within(strip).getByRole('link', { name: 'Night of Mon 2 Nov' })).toBeInTheDocument();
    expect(within(strip).getByRole('meter', { name: /Requests used/ })).toHaveAttribute(
      'aria-valuenow',
      '1840',
    );

    expect(screen.getByRole('combobox', { name: 'Scored up to' })).toHaveValue(
      `${nightOf(latest.firstBuyAt, MOCK_ZONE).label} (latest)`,
    );
    const board = screen.getByRole('table', { name: 'Scoreboard' });
    const rows = within(board).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(10);
    const expected = db.evaluations
      .filter((e) => e.sessionId === latest.id)
      .sort((a, b) => {
        const rank = { SUCCESS: 0, NO_SUCCESS: 1, NO_RESULT: 2 };
        return rank[a.status] - rank[b.status] || Number(b.worstCasePnl) - Number(a.worstCasePnl);
      })
      .map((e) => e.label);
    expect(rows.map((row) => within(row).getAllByText(/^V\d\d$/)[0]!.textContent)).toEqual(
      expected,
    );
    expect(within(rows[0]!).getByText(/Success|No success|No result/)).toBeInTheDocument();
  });

  it('filters the scoreboard by status and keeps the filter in the URL', async () => {
    const { user, router } = renderOverview();
    await screen.findByRole('table', { name: 'Scoreboard' });
    await user.click(screen.getByRole('button', { name: '✕ No success' }));
    expect(router.state.location.search).toContain('status=NO_SUCCESS');
    const board = screen.getByRole('table', { name: 'Scoreboard' });
    for (const row of within(board).getAllByRole('row').slice(1))
      expect(within(row).getByText('No success')).toBeInTheDocument();
  });

  it('draws one grid cell per variation and night, linking to that night', async () => {
    renderOverview();
    const grid = await screen.findByRole('table', { name: 'Status by night' });
    const cell = within(grid).getAllByRole('link', { name: /^V03, night of Mon 2 Nov/ })[0]!;
    expect(cell).toHaveAccessibleName('V03, night of Mon 2 Nov: night still open, not scored yet');
    expect(cell).toHaveAttribute('href', `/automation/nights/${tonight.id}`);
    expect(within(grid).getAllByRole('link', { name: /^V00, night of / })).toHaveLength(14);
  });

  it('follows a chosen variation in the trend and keeps it in the URL', async () => {
    const { user, router } = renderOverview();
    await screen.findByRole('table', { name: 'Status by night' });
    await user.click(screen.getAllByRole('button', { name: 'Show V03 over time' })[0]!);
    expect(router.state.location.search).toContain('variation=V03');
    expect(
      screen.getByRole('heading', { name: 'V03 against the default (V00), by night' }),
    ).toBeInTheDocument();
  });

  it('explains how to turn automation on when its endpoints are absent', async () => {
    server.use(http.get('/api/automation/status', () => new HttpResponse(null, { status: 404 })));
    renderOverview();
    expect(await screen.findByText('Automation is off')).toBeInTheDocument();
    expect(screen.getByText('trenching.automation.enabled=true')).toBeInTheDocument();
  });

  it('says discovery only, and that nothing is scored, in phase 0', async () => {
    resetAutomationMocks(discoveryOnly(createAutomationDb()));
    renderOverview();
    expect(
      await screen.findByText('Discovery only', {
        selector: '.mantine-Alert-title, .mantine-Alert-title *',
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Discovery only: buys are counted, but nothing is replayed or scored/),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/found, not captured/).length).toBeGreaterThan(0);
  });
});

describe('AutomationNightsPage', () => {
  it('sends the filters to the backend and keeps them in the URL', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/sessions')) seen.push(new URL(request.url));
    });
    const { user, router } = renderPage(<AutomationNightsPage />, {
      path: '/automation/nights',
      url: '/automation/nights',
    });
    await screen.findByRole('table', { name: 'Nights' });
    await user.click(screen.getByRole('combobox', { name: 'Capture health' }));
    await user.click(await screen.findByRole('option', { name: 'With issues' }));
    await waitFor(() => expect(seen.at(-1)?.searchParams.get('health')).toBe('ISSUES'));
    expect(seen.at(-1)?.searchParams.get('sort')).toBe('status');
    expect(router.state.location.search).toContain('health=ISSUES');
    const rows = within(await screen.findByRole('table', { name: 'Nights' }))
      .getAllByRole('row')
      .slice(1);
    for (const row of rows) expect(within(row).getByText(/with issues/)).toBeInTheDocument();
    server.events.removeAllListeners();
  });
});

describe('AutomationNightPage', () => {
  function renderNight(id: string, query = '') {
    return renderPage(<AutomationNightPage />, {
      path: '/automation/nights/:id',
      url: `/automation/nights/${id}${query}`,
    });
  }

  it("lists the night's buys and the first replayed buy's sweep runs with run links", async () => {
    renderNight(latest.id);
    expect(
      await screen.findByRole('heading', {
        name: `Night of ${nightOf(latest.firstBuyAt, MOCK_ZONE).label}`,
      }),
    ).toBeInTheDocument();
    const buys = screen.getByRole('table', { name: 'Buys this night' });
    expect(within(buys).getAllByRole('row').slice(1)).toHaveLength(
      db.items.filter((i) => i.sessionId === latest.id).length,
    );
    const runs = await screen.findByRole('table', { name: 'Sweep runs of this buy' });
    const rows = within(runs).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(10);
    expect(within(rows[0]!).getByRole('link')).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/runs\/run-/),
    );
    expect(screen.getByRole('button', { name: 'Close night now' })).toBeDisabled();
  });

  it('shows why a buy failed', async () => {
    const failed = db.items.find((i) => i.state === 'FAILED')!;
    renderNight(failed.sessionId, `?item=${failed.id}`);
    expect(
      await screen.findByText(/market step \(attempt 2\): The provider did not answer in time/),
    ).toBeInTheDocument();
    expect(await screen.findByText(/Not replayed yet/)).toBeInTheDocument();
  });

  it('closes an open night only after confirmation, with one request id', async () => {
    const bodies: Array<{ requestId: string }> = [];
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.includes('/close'))
        bodies.push((await request.clone().json()) as { requestId: string });
    });
    const { user } = renderNight(tonight.id);
    await user.click(await screen.findByRole('button', { name: 'Close night now' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/2 unfinished buys become Abandoned/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(bodies).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Close night now' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close night' }),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Close night now' })).toBeDisabled(),
    );
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.requestId).toMatch(/^ui-close-/);
    expect(screen.getAllByText('Abandoned').length).toBeGreaterThanOrEqual(2);
    server.events.removeAllListeners();
  });

  it('says when a night does not exist', async () => {
    renderNight('missing');
    expect(await screen.findByText('Night not found')).toBeInTheDocument();
  });
});

describe('AutomationSweepRunsPage', () => {
  it('filters by variation and outcome on the backend', async () => {
    const { user, router } = renderPage(<AutomationSweepRunsPage />, {
      path: '/automation/sweep-runs',
      url: '/automation/sweep-runs?variation=V04',
    });
    const table = await screen.findByRole('table', { name: 'Sweep runs' });
    for (const row of within(table).getAllByRole('row').slice(1))
      expect(within(row).getByText('V04')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Outcome' }));
    await user.click(await screen.findByRole('option', { name: 'Still open at the end' }));
    expect(router.state.location.search).toContain('outcome=HORIZON_REACHED');
    await waitFor(() => {
      const rows = within(screen.getByRole('table', { name: 'Sweep runs' }))
        .getAllByRole('row')
        .slice(1);
      for (const row of rows)
        expect(within(row).getByText('Still open at the end')).toBeInTheDocument();
    });
  });
});

describe('AutomationItemRedirect', () => {
  it('opens a buy inside its night', async () => {
    const item = db.items[3]!;
    const { router } = renderPage(<AutomationItemRedirect />, {
      path: '/automation/items/:id',
      url: `/automation/items/${item.id}`,
      extraRoutes: [{ path: '/automation/nights/:id', element: <p>Night page</p> }],
    });
    expect(await screen.findByText('Night page')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/automation/nights/${item.sessionId}`);
    expect(router.state.location.search).toBe(`?item=${item.id}`);
  });
});

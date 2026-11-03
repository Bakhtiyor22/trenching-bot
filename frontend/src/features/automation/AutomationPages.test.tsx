import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BackfillCreateRequest } from '../../api/automation';
import { createAutomationDb, dayRows, discoveryOnly } from '../../mocks/automationFixtures';
import { resetAutomationMocks } from '../../mocks/automationHandlers';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { AutomationDayPage } from './AutomationDayPage';
import { AutomationDaysPage } from './AutomationDaysPage';
import { AutomationOverviewPage } from './AutomationOverviewPage';
import { ItemRedirect, NightRedirect } from './AutomationRedirects';
import { AutomationSweepRunsPage } from './AutomationSweepRunsPage';
import { BackfillDetailPage } from './BackfillDetailPage';
import { BackfillsPage } from './BackfillsPage';

/** Inside the fixtures' today: Mon 2 Nov 2026, 22:00 UTC+5. */
const NOW = new Date('2026-11-02T17:00:00Z');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
});

const db = createAutomationDb();
const activeBackfill = db.backfills.find((b) => b.state === 'CAPTURING')!;
const dryRun = db.backfills.find((b) => b.dryRun)!;

function renderOverview(url = '/automation') {
  return renderPage(<AutomationOverviewPage />, {
    path: '/automation',
    url,
    extraRoutes: [{ path: '/automation/days/:date', element: <p>Day page</p> }],
  });
}

function postBodies<T>(fragment: string): T[] {
  const bodies: T[] = [];
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && request.url.includes(fragment))
      bodies.push(
        (await request
          .clone()
          .json()
          .catch(() => ({}))) as T,
      );
  });
  return bodies;
}

describe('AutomationOverviewPage', () => {
  it('shows mode, coverage, today and the running backfill in the status strip', async () => {
    renderOverview();
    const strip = await screen.findByRole('region', { name: 'Automation status' });
    expect(within(strip).getByText('Capturing')).toBeInTheDocument();
    expect(within(strip).getByText(/always on/)).toBeInTheDocument();
    expect(within(strip).getByText('17 Oct, 12:00')).toBeInTheDocument();
    expect(within(strip).getByRole('link', { name: 'Mon 2 Nov' })).toHaveAttribute(
      'href',
      '/automation/days/2026-11-02',
    );
    expect(await within(strip).findByRole('link', { name: '6 Oct → 9 Oct' })).toHaveAttribute(
      'href',
      `/automation/backfills/${activeBackfill.id}`,
    );
    expect(within(strip).getByRole('button', { name: 'Run now' })).toBeEnabled();
  });

  it('scores the newest day with a verdict by default, in the backend order', async () => {
    renderOverview();
    expect(await screen.findByRole('combobox', { name: 'Scored day' })).toHaveValue('Sun 1 Nov');
    const rows = within(screen.getByRole('table', { name: 'Scoreboard' }))
      .getAllByRole('row')
      .slice(1);
    const expected = dayRows(db, '2026-11-01', NOW.getTime()).map((e) => e.label);
    expect(rows.map((row) => within(row).getAllByText(/^V\d\d$/)[0]!.textContent)).toEqual(
      expected,
    );
  });

  it('shows a pending day with its counts so far and the day it waits for', async () => {
    renderOverview('/automation?day=2026-11-02');
    const board = await screen.findByRole('table', { name: 'Scoreboard' });
    const chips = within(board).getAllByLabelText('Pending, waiting for Mon 2 Nov to complete');
    expect(chips).toHaveLength(10);
    expect(
      screen.getByText(/waiting for Mon 2 Nov to complete/, { selector: 'p' }),
    ).toBeInTheDocument();
  });

  it('filters the scoreboard by status and keeps the filter in the URL', async () => {
    const { user, router } = renderOverview();
    await screen.findByRole('table', { name: 'Scoreboard' });
    await user.click(screen.getByRole('button', { name: '✕ No success' }));
    expect(router.state.location.search).toContain('status=NO_SUCCESS');
    for (const row of within(screen.getByRole('table', { name: 'Scoreboard' }))
      .getAllByRole('row')
      .slice(1))
      expect(within(row).getByText('No success')).toBeInTheDocument();
  });

  it('draws one cell per variation and day, pending days included, linking to the day', async () => {
    renderOverview();
    const grid = await screen.findByRole('table', { name: 'Status by day' });
    expect(within(grid).getAllByRole('link', { name: /^V00, / })).toHaveLength(14);
    const today = within(grid).getByRole('link', { name: /^V03, Mon 2 Nov: pending/ });
    expect(today).toHaveAttribute('href', '/automation/days/2026-11-02');
    // 20–22 Oct wait for days before live coverage started.
    expect(
      within(grid).getByRole('link', { name: /^V03, Tue 20 Oct: pending, waiting for Wed 14 Oct/ }),
    ).toBeInTheDocument();
  });

  it('queues a run now and shows it queued', async () => {
    const posts = postBodies('/discovery/run');
    const { user } = renderOverview();
    const strip = await screen.findByRole('region', { name: 'Automation status' });
    await user.click(within(strip).getByRole('button', { name: 'Run now' }));
    expect(await within(strip).findByRole('button', { name: 'Queued…' })).toBeDisabled();
    expect(posts).toHaveLength(1);
  });

  it('explains a run now refused because automation is off', async () => {
    server.use(
      http.post('/api/automation/discovery/run', () =>
        HttpResponse.json(
          { code: 'AUTOMATION_DISABLED', message: 'off', fields: {} },
          { status: 409 },
        ),
      ),
    );
    const { user } = renderOverview();
    const strip = await screen.findByRole('region', { name: 'Automation status' });
    await user.click(within(strip).getByRole('button', { name: 'Run now' }));
    expect(await within(strip).findByText(/Automation is off on the backend/)).toBeInTheDocument();
  });

  it('explains how to turn automation on when its endpoints are absent', async () => {
    server.use(http.get('/api/automation/status', () => new HttpResponse(null, { status: 404 })));
    renderOverview();
    expect(await screen.findByText('Automation is off')).toBeInTheDocument();
    expect(screen.getByText('trenching.automation.enabled=true')).toBeInTheDocument();
  });

  it('says why every day is pending in phase 0', async () => {
    resetAutomationMocks(discoveryOnly(createAutomationDb()));
    renderOverview();
    expect(await screen.findByText('Every day here is pending')).toBeInTheDocument();
    expect(
      screen.getByText(/Discovery only: buys are counted, but none is captured/),
    ).toBeInTheDocument();
  });
});

describe('AutomationDaysPage', () => {
  it('lists 14 days by default and filters pending days on the page', async () => {
    const { user, router } = renderPage(<AutomationDaysPage />, {
      path: '/automation/days',
      url: '/automation/days',
    });
    const table = await screen.findByRole('table', { name: 'Trading days' });
    expect(within(table).getAllByRole('row').slice(1)).toHaveLength(14);
    await user.click(screen.getByRole('combobox', { name: 'Best status' }));
    await user.click(await screen.findByRole('option', { name: '◷ Pending' }));
    expect(router.state.location.search).toContain('status=PENDING');
    const rows = within(screen.getByRole('table', { name: 'Trading days' }))
      .getAllByRole('row')
      .slice(1);
    expect(rows.map((r) => within(r).getAllByRole('link')[0]!.textContent)).toEqual([
      'Mon 2 Nov',
      'Thu 22 Oct',
      'Wed 21 Oct',
      'Tue 20 Oct',
    ]);
  });

  it('sends a custom date range and refuses one longer than 62 days', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/days')) seen.push(new URL(request.url));
    });
    renderPage(<AutomationDaysPage />, {
      path: '/automation/days',
      url: '/automation/days?range=custom&from=2026-10-25&to=2026-10-31',
    });
    const table = await screen.findByRole('table', { name: 'Trading days' });
    expect(within(table).getAllByRole('row').slice(1)).toHaveLength(7);
    expect(seen.at(-1)?.searchParams.get('from')).toBe('2026-10-25');
    expect(seen.at(-1)?.searchParams.get('to')).toBe('2026-10-31');
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-08-01' } });
    expect(
      await screen.findByText('At most 62 days at a time; this range has 92.'),
    ).toBeInTheDocument();
  });
});

describe('AutomationDayPage', () => {
  function renderDay(date: string, query = '') {
    return renderPage(<AutomationDayPage />, {
      path: '/automation/days/:date',
      url: `/automation/days/${date}${query}`,
    });
  }

  it("shows a complete day's coverage, sessions, buys, a failed buy and the scoreboard", async () => {
    renderDay('2026-10-31');
    expect(await screen.findByRole('heading', { name: 'Sat 31 Oct' })).toBeInTheDocument();
    expect(screen.getByText('Fully covered by discovery · complete')).toBeInTheDocument();
    const sessions = screen.getByRole('table', { name: 'Sessions this day' });
    expect(within(sessions).getByText('Live')).toBeInTheDocument();
    expect(within(sessions).queryByRole('button', { name: 'Close session now' })).toBeNull();
    expect(
      screen.getByText(/market step \(attempt 2\): The provider did not answer in time/),
    ).toBeInTheDocument();
    const runs = await screen.findByRole('table', { name: 'Sweep runs of this buy' });
    expect(within(runs).getAllByRole('row').slice(1)).toHaveLength(10);
    expect(
      within(screen.getByRole('table', { name: 'Scoreboard' }))
        .getAllByRole('row')
        .slice(1),
    ).toHaveLength(10);
  });

  it('closes an open session only after confirmation, with one request id', async () => {
    const bodies = postBodies<{ requestId: string }>('/close');
    const { user } = renderDay('2026-11-02');
    expect(
      await screen.findByText(/not complete: 1 session still open, 2 buys still in progress/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close session now' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/2 unfinished buys become Abandoned/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(bodies).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Close session now' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close session' }),
    );
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Close session now' })).toBeNull(),
    );
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.requestId).toMatch(/^ui-close-/);
  });

  it("tags a backfill's session and links to its backfill", async () => {
    renderDay('2026-10-06');
    const sessions = await screen.findByRole('table', { name: 'Sessions this day' });
    expect(within(sessions).getByRole('link', { name: 'Backfill' })).toHaveAttribute(
      'href',
      `/automation/backfills/${activeBackfill.id}`,
    );
    expect(screen.getByText(/Not covered by discovery yet · not complete/)).toBeInTheDocument();
  });
});

describe('BackfillsPage', () => {
  function renderBackfills(url = '/automation/backfills') {
    return renderPage(<BackfillsPage />, {
      path: '/automation/backfills',
      url,
      extraRoutes: [{ path: '/automation/backfills/:id', element: <BackfillDetailPage /> }],
    });
  }

  it('lists every backfill with its kind, state and spend', async () => {
    renderBackfills();
    const table = await screen.findByRole('table', { name: 'Backfills' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(rows[0]!).getByText('Capturing')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('Dry run')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('Capture · filtered (higher minimum)')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Capture · filtered (2 tokens)')).toBeInTheDocument();
  });

  it('explains that another backfill is still running', async () => {
    const { user } = renderBackfills();
    await screen.findByRole('table', { name: 'Backfills' });
    await user.click(screen.getByRole('button', { name: 'Start dry run' }));
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/Another backfill is still running/)).toBeInTheDocument();
    expect(within(alert).getByRole('link', { name: 'open the running backfill' })).toHaveAttribute(
      'href',
      `/automation/backfills/${activeBackfill.id}`,
    );
  });

  it('starts a dry run for the days before live coverage and opens it', async () => {
    resetAutomationMocks(createAutomationDb({ activeBackfill: false }));
    const bodies = postBodies<BackfillCreateRequest>('/api/automation/backfills');
    const { user, router } = renderBackfills();
    await screen.findByRole('table', { name: 'Backfills' });
    expect(screen.getByLabelText('From day')).toHaveValue('2026-10-10');
    expect(screen.getByLabelText('To day')).toHaveValue('2026-10-16');
    await user.click(screen.getByRole('button', { name: 'Start dry run' }));
    await waitFor(() =>
      expect(router.state.location.pathname).toMatch(/^\/automation\/backfills\/bf-/),
    );
    expect(bodies[0]).toMatchObject({
      fromDay: '2026-10-10',
      toDay: '2026-10-16',
      dryRun: true,
      requestBudget: 500,
    });
    expect(bodies[0]!.requestId).toMatch(/^ui-backfill-/);
    expect(await screen.findByText('Scanning')).toBeInTheDocument();
  });

  it('checks the budget and filters before sending, and says when a backfill is filtered', async () => {
    resetAutomationMocks(createAutomationDb({ activeBackfill: false }));
    const bodies = postBodies<BackfillCreateRequest>('/api/automation/backfills');
    const { user } = renderBackfills();
    await screen.findByRole('table', { name: 'Backfills' });
    const budget = screen.getByLabelText('Request budget');
    await user.click(screen.getByRole('checkbox', { name: /Dry run/ }));
    expect(budget).toHaveValue('1222');
    await user.clear(budget);
    await user.type(budget, '500');
    await user.click(screen.getByRole('button', { name: /Filters/ }));
    await user.type(screen.getByLabelText(/Minimum signal size/), '1.2');
    await user.click(screen.getByRole('button', { name: /Start capture backfill/ }));
    expect(screen.getByText('A whole number from 1,222 to 20,000')).toBeInTheDocument();
    expect(screen.getByText('At least 1.5 SOL, the capture filter')).toBeInTheDocument();
    expect(bodies).toEqual([]);
    await user.clear(screen.getByLabelText(/Minimum signal size/));
    await user.type(screen.getByLabelText(/Minimum signal size/), '2');
    expect(screen.getByText(/makes this backfill filtered/)).toBeInTheDocument();
  });

  it('explains a capture of days live discovery already covers', async () => {
    resetAutomationMocks(createAutomationDb({ activeBackfill: false }));
    const { user } = renderBackfills();
    await screen.findByRole('table', { name: 'Backfills' });
    await user.click(screen.getByRole('checkbox', { name: /Dry run/ }));
    fireEvent.change(screen.getByLabelText('From day'), { target: { value: '2026-10-20' } });
    fireEvent.change(screen.getByLabelText('To day'), { target: { value: '2026-10-21' } });
    await user.click(screen.getByRole('button', { name: /Start capture backfill/ }));
    expect(await screen.findByText(/Live discovery already covers these days/)).toBeInTheDocument();
  });

  it("pre-fills a capture from a finished dry run's estimate", async () => {
    renderBackfills(`/automation/backfills?from=2026-10-10&to=2026-10-16&dryRun=false&budget=3150`);
    expect(await screen.findByLabelText('Request budget')).toHaveValue('3150');
    expect(screen.getByRole('checkbox', { name: /Dry run/ })).not.toBeChecked();
    expect(
      screen.getByRole('button', { name: 'Start capture backfill (spends up to 3,150 requests)' }),
    ).toBeInTheDocument();
  });
});

describe('BackfillDetailPage', () => {
  function renderDetail(id: string) {
    return renderPage(<BackfillDetailPage />, {
      path: '/automation/backfills/:id',
      url: `/automation/backfills/${id}`,
    });
  }

  it("shows a dry run's estimate and links a capture of the same days", async () => {
    renderDetail(dryRun.id);
    expect(await screen.findByText('3,150')).toBeInTheDocument();
    expect(
      screen.getByRole('img', {
        name: /Buys passing the filter per trading day, 10 Oct to 16 Oct: 3, 5, 0, 4, 6, 2, 4/,
      }),
    ).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'Capture this range…' });
    expect(link.getAttribute('href')).toContain('dryRun=false');
    expect(link.getAttribute('href')).toContain('budget=3150');
    expect(screen.queryByRole('button', { name: 'Cancel backfill' })).toBeNull();
  });

  it('shows a capture in progress and cancels it after confirmation', async () => {
    const bodies = postBodies<{ requestId: string }>('/cancel');
    const { user } = renderDetail(activeBackfill.id);
    expect(await screen.findByText(/Read back to/)).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'Requests spent of the budget' })).toHaveAttribute(
      'aria-valuemax',
      '4000',
    );
    await user.click(screen.getByRole('button', { name: 'Cancel backfill' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/unfinished buys become Abandoned/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel backfill' }));
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
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

describe('old links', () => {
  it('opens a buy inside its day', async () => {
    const item = db.items[3]!;
    const session = db.sessions.find((s) => s.id === item.sessionId)!;
    const { router } = renderPage(<ItemRedirect />, {
      path: '/automation/items/:id',
      url: `/automation/items/${item.id}`,
      extraRoutes: [{ path: '/automation/days/:date', element: <p>Day page</p> }],
    });
    expect(await screen.findByText('Day page')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/automation/days/${session.tradingDay}`);
    expect(router.state.location.search).toBe(`?item=${item.id}`);
  });

  it('opens an old night link on its day', async () => {
    const session = db.sessions[5]!;
    const { router } = renderPage(<NightRedirect />, {
      path: '/automation/nights/:id',
      url: `/automation/nights/${session.id}`,
      extraRoutes: [{ path: '/automation/days/:date', element: <p>Day page</p> }],
    });
    expect(await screen.findByText('Day page')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/automation/days/${session.tradingDay}`);
  });
});

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { automationKeys, type BackfillCreateRequest } from '../../api/automation';
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
import { VariationDetailPage } from './VariationDetailPage';
import { VariationsPage } from './VariationsPage';

/** Inside the fixtures' today: Mon 2 Nov 2026, 22:00 UTC+5. */
const NOW = new Date('2026-11-02T17:00:00Z');

// Only `Date` is faked, and it never advances. That also stops the mocks' progress clock: a backfill created here stays
// Scanning and a queued run stays queued, however slow the runner. Tests that rely on those stages need this freeze.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
  // The variation page remembers shown history entries here, and every test's first entry has the same key.
  sessionStorage.clear();
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

/** A failing answer for a background refresh. */
const unavailable = () =>
  HttpResponse.json(
    { code: 'UNAVAILABLE', message: 'Backend unreachable', fields: {} },
    { status: 503 },
  );

function header() {
  return screen.getByRole('banner');
}

describe('AutomationOverviewPage', () => {
  it('shows mode, coverage, today and the running backfill in the status strip', async () => {
    renderOverview();
    const strip = await screen.findByRole('region', { name: 'Automation status' });
    expect(within(header()).getByText('Capturing')).toBeInTheDocument();
    expect(within(header()).getByText('Times in UTC+5')).toBeInTheDocument();
    expect(within(strip).getByText('Always on')).toBeInTheDocument();
    expect(within(strip).getByText(/plan decu-sweep-1/)).toBeInTheDocument();
    expect(within(strip).getByText('17 Oct, 12:00')).toBeInTheDocument();
    expect(within(strip).getByRole('link', { name: 'Mon 2 Nov' })).toHaveAttribute(
      'href',
      '/automation/days/2026-11-02',
    );
    expect(await within(strip).findByRole('link', { name: '6 Oct → 9 Oct' })).toHaveAttribute(
      'href',
      `/automation/backfills/${activeBackfill.id}`,
    );
    expect(within(header()).getByRole('button', { name: 'Run discovery now' })).toBeEnabled();
  });

  it('scores the newest day with a verdict by default, in the backend order, and pins it in the URL', async () => {
    const { router } = renderOverview();
    expect(await screen.findByRole('combobox', { name: 'Scored day' })).toHaveValue('Sun 1 Nov');
    await waitFor(() => expect(router.state.location.search).toContain('day=2026-11-01'));
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
    await user.click(screen.getByRole('radio', { name: '✕ No success' }));
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

  it('ignores a status filter in the URL that is not a status', async () => {
    renderOverview('/automation?status=BOGUS');
    const board = await screen.findByRole('table', { name: 'Scoreboard' });
    expect(within(board).getAllByRole('row').slice(1)).toHaveLength(10);
    expect(screen.getByRole('radio', { name: 'All' })).toBeChecked();
  });

  it('queues a run now and shows it queued', async () => {
    const posts = postBodies('/discovery/run');
    const { user } = renderOverview();
    await screen.findByRole('region', { name: 'Automation status' });
    await user.click(within(header()).getByRole('button', { name: 'Run discovery now' }));
    expect(await within(header()).findByRole('button', { name: 'Queued…' })).toBeDisabled();
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
    await screen.findByRole('region', { name: 'Automation status' });
    await user.click(within(header()).getByRole('button', { name: 'Run discovery now' }));
    const alert = await within(header()).findByRole('alert');
    expect(within(alert).getByText(/Automation is off on the backend/)).toBeInTheDocument();
  });

  it('keeps the page on screen when a background refresh of the status fails', async () => {
    const { queryClient } = renderOverview();
    await screen.findByRole('table', { name: 'Scoreboard' });
    server.use(http.get('/api/automation/status', unavailable));
    await act(() => queryClient.refetchQueries({ queryKey: automationKeys.status }));
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Automation status' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Scoreboard' })).toBeInTheDocument();
    expect(screen.queryByText('Automation status unavailable')).toBeNull();
  });

  it('explains how to turn automation on when its endpoints are absent', async () => {
    server.use(http.get('/api/automation/status', () => new HttpResponse(null, { status: 404 })));
    renderOverview();
    expect(await screen.findByText('Automation is off')).toBeInTheDocument();
    expect(screen.getByText('trenching.automation.enabled=true')).toBeInTheDocument();
  });

  it('says how many days are pending when the scoreboard status filter matches none', async () => {
    resetAutomationMocks(discoveryOnly(createAutomationDb()));
    renderOverview('/automation?status=SUCCESS');
    expect(
      await screen.findByText(
        /^14 days pending: discovery only, so no buy is captured or replayed, so no variation is success yet\./,
      ),
    ).toBeInTheDocument();
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

  it('says how many days are pending and why when a verdict filter matches none', async () => {
    resetAutomationMocks(discoveryOnly(createAutomationDb()));
    const { user, router } = renderPage(<AutomationDaysPage />, {
      path: '/automation/days',
      url: '/automation/days?status=SUCCESS',
    });
    expect(
      await screen.findByText('14 days pending: discovery only, so no buy is captured or replayed'),
    ).toBeInTheDocument();
    expect(screen.queryByText('No days match')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Show pending days' }));
    expect(router.state.location.search).toContain('status=PENDING');
    expect(await screen.findByRole('table', { name: 'Trading days' })).toBeInTheDocument();
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

  it('refuses a date that does not exist', async () => {
    renderPage(<AutomationDaysPage />, {
      path: '/automation/days',
      url: '/automation/days?range=custom&from=2026-02-31&to=2026-03-02',
    });
    expect(await screen.findByText('2026-02-31 is not a real date.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Trading days' })).toBeNull();
  });

  it('says a day discovery never covered is not scanned yet, not that it had no buys', async () => {
    renderPage(<AutomationDaysPage />, {
      path: '/automation/days',
      url: '/automation/days?range=custom&from=2026-10-10&to=2026-10-16',
    });
    const table = await screen.findByRole('table', { name: 'Trading days' });
    expect(within(table).getAllByText('Not scanned yet').length).toBeGreaterThan(0);
    expect(within(table).queryByText('No buys')).toBeNull();
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
    const { router } = renderDay('2026-10-31');
    expect(await screen.findByRole('heading', { name: 'Sat 31 Oct' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 2 })).toHaveLength(1);
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Days' })).toHaveAttribute(
      'href',
      '/automation/days',
    );
    expect(await screen.findByText('Fully covered by discovery · complete')).toBeInTheDocument();
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
    // The default buy is pinned in the URL, so a refresh cannot move the selection.
    await waitFor(() => expect(router.state.location.search).toMatch(/^\?item=/));
  });

  it('shows a closing session as waiting for its buys instead of offering to close it', async () => {
    const closing = db.sessions.find((s) => s.state === 'CLOSING')!;
    const unfinished = db.items.filter(
      (i) =>
        i.sessionId === closing.id &&
        !['SIMULATED', 'FAILED', 'ABANDONED', 'SKIPPED_NOT_FIRST_BUY'].includes(i.state),
    ).length;
    renderDay(closing.tradingDay);
    const sessions = await screen.findByRole('table', { name: 'Sessions this day' });
    expect(
      within(sessions).getByText(
        `Closing: waits for ${unfinished} ${unfinished === 1 ? 'buy' : 'buys'}`,
      ),
    ).toBeInTheDocument();
    expect(within(sessions).queryByRole('button', { name: 'Close session now' })).toBeNull();
    // Its waiting buy tried last in October: overdue, with the date.
    expect(screen.getAllByText(/next try overdue since \d+ Oct, \d\d:\d\d/).length).toBeGreaterThan(
      0,
    );
  });

  it('rejects a date that does not exist', async () => {
    renderDay('2026-02-31');
    expect(await screen.findByText('Not a trading day')).toBeInTheDocument();
    expect(screen.getByText(/2026-02-31 is not a real date/)).toBeInTheDocument();
  });

  it('keeps the close dialog open while sending and opens it again without the old error', async () => {
    let calls = 0;
    let release: () => void = () => {};
    server.use(
      http.post('/api/automation/sessions/:id/close', async () => {
        calls += 1;
        if (calls > 1) await new Promise<void>((resolve) => (release = resolve));
        return unavailable();
      }),
    );
    const { user } = renderDay('2026-11-02');
    await user.click(await screen.findByRole('button', { name: 'Close session now' }));
    let dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Close session' }));
    expect(await within(dialog).findByText('Close failed')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await user.click(screen.getByRole('button', { name: 'Close session now' }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText('Close failed')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Close session' }));
    await waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeDisabled(),
    );
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    release();
    expect(await within(dialog).findByText('Close failed')).toBeInTheDocument();
  });

  it('keeps an open dialog when a background refresh of the status fails', async () => {
    const { user, queryClient } = renderDay('2026-11-02');
    await user.click(await screen.findByRole('button', { name: 'Close session now' }));
    await screen.findByRole('dialog');
    server.use(http.get('/api/automation/status', unavailable));
    await act(() => queryClient.refetchQueries({ queryKey: automationKeys.status }));
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
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

describe('AutomationDayPage retry', () => {
  const DAY = '2026-10-31';
  const daySessions = new Set(db.sessions.filter((s) => s.tradingDay === DAY).map((s) => s.id));
  const failed = db.items.filter((i) => daySessions.has(i.sessionId) && i.state === 'FAILED');
  const retryable = failed.find((i) => i.retryRefusal == null)!;
  const refused = failed.find((i) => i.retryRefusal != null)!;

  function renderDay(date = DAY, query = '') {
    return renderPage(<AutomationDayPage />, {
      path: '/automation/days/:date',
      url: `/automation/days/${date}${query}`,
    });
  }

  function rowOf(item: { mint: string }) {
    const table = screen.getByRole('table', { name: 'Buys this day' });
    return within(table)
      .getAllByRole('row')
      .find((row) => row.textContent?.includes(item.mint.slice(0, 4)))!;
  }

  function refusalOnce(status: number, code: string, message: string, fields = {}) {
    server.use(
      http.post('/api/automation/items/:id/retry', () =>
        HttpResponse.json({ code, message, fields }, { status }),
      ),
    );
  }

  it('has a retryable and a refused failed buy in the fixtures', () => {
    expect(retryable).toBeDefined();
    expect(refused.retryRefusal).toBe('RETRY_NOT_ALLOWED');
  });

  it('retries a failed buy with a fresh request id and shows its new state without a refresh', async () => {
    const bodies = postBodies<{ requestId: string }>('/retry');
    const { user } = renderDay(DAY, `?item=${retryable.id}`);
    expect(await screen.findByText(/Sends it back to the market step/)).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Buys this day' });
    expect(within(table).getAllByRole('button', { name: /^Retry buy at/ })).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull());
    expect(within(rowOf(retryable)).getByText('Capturing')).toBeInTheDocument();
    expect(within(rowOf(retryable)).getByText(/· retried 1×/)).toBeInTheDocument();
    expect(within(table).getAllByRole('button', { name: /^Retry buy at/ })).toHaveLength(1);
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.requestId).toMatch(/^ui-retry-[0-9a-f]{32}$/);
  });

  it('shows a refused buy as disabled with its reason in plain words', async () => {
    renderDay(DAY, `?item=${refused.id}`);
    expect(
      await screen.findByText(/Cannot be retried: Its transaction has no block time/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled();
    const rowButton = within(rowOf(refused)).getByRole('button', { name: /^Retry buy at/ });
    expect(rowButton).toHaveAttribute('aria-disabled', 'true');
    expect(rowButton).toHaveAccessibleDescription(/no block time/);
  });

  it('disables the button while the request is in flight and sends a new id on every click', async () => {
    const bodies = postBodies<{ requestId: string }>('/retry');
    let release: () => void = () => {};
    server.use(
      http.post('/api/automation/items/:id/retry', async () => {
        await new Promise<void>((resolve) => (release = resolve));
        return unavailable();
      }),
    );
    const { user } = renderDay(DAY, `?item=${retryable.id}`);
    const button = await screen.findByRole('button', { name: 'Retry' });
    await user.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    release();
    expect(await screen.findByRole('alert')).toHaveTextContent('Backend unreachable');
    await waitFor(() => expect(button).not.toBeDisabled());
    await user.click(button);
    await waitFor(() => expect(bodies).toHaveLength(2));
    release();
    expect(bodies[0]!.requestId).not.toBe(bodies[1]!.requestId);
  });

  it.each([
    ['ITEM_NOT_FAILED', {}, /no longer failed/],
    ['RETRY_NOT_ALLOWED', { failureCode: 'BACKFILL_BUDGET' }, /ran out of request budget/],
    ['RETRY_NOT_ALLOWED', { failureCode: 'MISSING_BLOCK_TIME' }, /no block time/],
    ['BACKFILL_NOT_CAPTURING', {}, /still scanning/],
    ['CAPTURE_DISABLED', {}, /Capture is off on the backend/],
  ])('explains a %s refusal %j', async (code, fields, text) => {
    refusalOnce(409, code, 'refused', fields);
    const { user } = renderDay(DAY, `?item=${retryable.id}`);
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
  });

  it('explains an unknown buy and a malformed request', async () => {
    refusalOnce(404, 'AUTOMATION_ITEM_NOT_FOUND', 'No automation item');
    const { user } = renderDay(DAY, `?item=${retryable.id}`);
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This buy no longer exists');
    refusalOnce(400, 'INVALID_REQUEST', 'requestId must not be blank');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByText('The backend refused the retry request: requestId must not be blank'),
    ).toBeInTheDocument();
  });

  it("retries a day's failed buys after confirmation and lists the refused ones", async () => {
    const bodies = postBodies<{ requestId: string }>('/days/');
    const { user } = renderDay();
    await user.click(await screen.findByRole('button', { name: 'Retry failed buys' }));
    const dialog = await screen.findByRole('dialog', { name: 'Retry 1 failed buy?' });
    expect(within(dialog).getByText(/1 other failed buy cannot be retried/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(bodies).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Retry failed buys' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Retry 1 buy' }),
    );
    const result = await screen.findByRole('dialog', { name: 'Retry sent' });
    expect(
      within(result).getByText(/1 buy sent back to the step where it failed/),
    ).toBeInTheDocument();
    expect(within(result).getByText('1 buy was not retried:')).toBeInTheDocument();
    expect(within(result).getByText(/no block time/)).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.requestId).toMatch(/^ui-retry-day-[0-9a-f]{32}$/);
    expect(bodies[0]!.requestId.length).toBeLessThanOrEqual(55);
    await waitFor(() =>
      expect(within(rowOf(retryable)).getByText('Capturing')).toBeInTheDocument(),
    );
    await user.click(within(result).getByRole('button', { name: 'Done' }));
    // Only the refused buy is left failed, so the day action now explains instead of sending.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Retry failed buys' })).toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    );
  });

  it("reports a repeated day retry's buys as unchanged", async () => {
    server.use(
      http.post('/api/automation/days/:date/retry', () =>
        HttpResponse.json({ day: DAY, retried: [], unchanged: [retryable.id], refused: {} }),
      ),
    );
    const { user } = renderDay();
    await user.click(await screen.findByRole('button', { name: 'Retry failed buys' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Retry 1 buy' }),
    );
    const result = await screen.findByRole('dialog', { name: 'Retry sent' });
    expect(within(result).getByText(/No buy was sent back/)).toBeInTheDocument();
    expect(
      within(result).getByText(/1 buy was already retried by this request and left as it is/),
    ).toBeInTheDocument();
  });

  it('keeps the day dialog open with the reason when the day retry is refused', async () => {
    server.use(
      http.post('/api/automation/days/:date/retry', () =>
        HttpResponse.json(
          { code: 'INVALID_REQUEST', message: 'date has an invalid value', fields: {} },
          { status: 400 },
        ),
      ),
    );
    const { user } = renderDay();
    await user.click(await screen.findByRole('button', { name: 'Retry failed buys' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Retry 1 buy' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The backend refused the retry request: date has an invalid value',
    );
  });

  it('shows no retry controls on a day without failed buys', async () => {
    renderDay('2026-11-02');
    await screen.findByRole('table', { name: 'Buys this day' });
    expect(screen.queryByRole('button', { name: 'Retry failed buys' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Retry buy at/ })).toBeNull();
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
    // The list refetches after the create and can show the new row as Scanning before the route commits, so wait for
    // the detail page's own title before reading its header.
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Dry run · 10 Oct → 16 Oct' }),
    ).toBeInTheDocument();
    expect(within(header()).getByText('Scanning')).toBeInTheDocument();
  });

  it('checks the budget and filters before sending, and says when a backfill is filtered', async () => {
    resetAutomationMocks(createAutomationDb({ activeBackfill: false }));
    const bodies = postBodies<BackfillCreateRequest>('/api/automation/backfills');
    const { user } = renderBackfills();
    await screen.findByRole('table', { name: 'Backfills' });
    const budget = screen.getByLabelText('Request budget');
    await user.click(screen.getByRole('checkbox', { name: /Dry run/ }));
    expect(budget).toHaveValue('2422');
    await user.clear(budget);
    await user.type(budget, '500');
    await user.click(screen.getByRole('button', { name: /Filters/ }));
    await user.type(screen.getByLabelText(/Minimum signal size/), '1.2');
    await user.click(screen.getByRole('button', { name: /Start capture backfill/ }));
    expect(screen.getByText('A whole number from 2,422 to 20,000')).toBeInTheDocument();
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
    expect(screen.getByRole('button', { name: 'Start capture backfill' })).toBeInTheDocument();
    expect(screen.getByText('Spends up to 3,150 requests.')).toBeInTheDocument();
  });

  it('ignores pre-fill values in the link that are not valid', async () => {
    renderBackfills(`/automation/backfills?from=2026-02-31&to=2026-10-16&dryRun=false&budget=abc`);
    expect(await screen.findByLabelText('Request budget')).toHaveValue('2422');
    expect(screen.getByLabelText('From day')).toHaveValue('2026-10-10');
    expect(screen.getByLabelText('To day')).toHaveValue('2026-10-16');
    expect(screen.getByText(/first day, budget were not valid/)).toBeInTheDocument();
  });

  it('keeps the page number in the URL', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/backfills?')) seen.push(new URL(request.url));
    });
    renderBackfills('/automation/backfills?page=2');
    expect(await screen.findByText('No backfills on this page')).toBeInTheDocument();
    expect(seen.at(-1)?.searchParams.get('page')).toBe('1');
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

  it('keeps the page and the open cancel dialog when a background refresh of the backfill fails', async () => {
    const { user, queryClient } = renderDetail(activeBackfill.id);
    expect(await screen.findByText(/Read back to/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel backfill' }));
    await screen.findByRole('dialog');
    server.use(http.get('/api/automation/backfills/:id', unavailable));
    await act(() =>
      queryClient.refetchQueries({ queryKey: automationKeys.backfill(activeBackfill.id) }),
    );
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Read back to/)).toBeInTheDocument();
  });

  it('opens the days of a backfill on the Days list', async () => {
    renderDetail(activeBackfill.id);
    expect(await screen.findByRole('link', { name: 'Open these days' })).toHaveAttribute(
      'href',
      `/automation/days?range=custom&from=${activeBackfill.fromDay}&to=${activeBackfill.toDay}`,
    );
  });
});

describe('AutomationSweepRunsPage URL input', () => {
  it('reads a page that is not a number as the first page, and never sends an invalid mint', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/sweep-runs')) seen.push(new URL(request.url));
    });
    renderPage(<AutomationSweepRunsPage />, {
      path: '/automation/sweep-runs',
      url: '/automation/sweep-runs?page=abc&mint=not-a-mint',
    });
    await screen.findByRole('table', { name: 'Sweep runs' });
    expect(seen.at(-1)?.searchParams.get('page')).toBe('0');
    expect(seen.at(-1)?.searchParams.has('mint')).toBe(false);
    expect(await screen.findByText('Not a valid mint address')).toBeInTheDocument();
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

describe('variations', () => {
  it('lists the active variations, with the archived ones behind a toggle', async () => {
    const { user, router } = renderPage(<VariationsPage />, {
      path: '/automation/variations',
      url: '/automation/variations',
    });
    const table = await screen.findByRole('table', { name: 'Variations' });
    const rows = () => within(table).getAllByRole('row').slice(1);
    expect(rows()).toHaveLength(10);
    const heads = () =>
      within(table)
        .getAllByRole('rowheader')
        .map((h) => h.textContent);
    expect(heads()).not.toContain('V07');
    expect(within(rows()[0]!).getByText('agreed (default)')).toBeInTheDocument();
    expect(within(rows()[0]!).getByText('baseline')).toBeInTheDocument();
    const v10 = rows().find((row) => within(row).queryByRole('link', { name: 'V10' }) != null)!;
    expect(within(v10).getByText('dip window 90 s')).toBeInTheDocument();
    expect(within(v10).getByRole('link', { name: 'V07' })).toHaveAttribute(
      'href',
      '/automation/variations/V07',
    );

    await user.click(screen.getByRole('switch', { name: 'Show archived' }));
    expect(router.state.location.search).toBe('?archived=1');
    await waitFor(() => expect(heads()).toContain('V07'));
    const row = within(table)
      .getAllByRole('rowheader')
      .find((h) => h.textContent === 'V07')!
      .closest('tr')!;
    expect(within(row).getByText('Archived')).toBeInTheDocument();
    expect(within(row).getByText('29 Oct, 15:00')).toBeInTheDocument();
    expect(within(row).getByText(/replaced by/)).toHaveTextContent('replaced by V10');
  });

  it('shows a variation with its settings, lineage and runs', async () => {
    renderPage(<VariationDetailPage />, {
      path: '/automation/variations/:label',
      url: '/automation/variations/V10',
    });
    const settings = await screen.findByRole('table', { name: 'Settings of V10' });
    expect(within(settings).getByText('90 s')).toBeInTheDocument();
    expect(within(settings).getByText('changed from V07')).toBeInTheDocument();
    expect(screen.getByText(/V10 replaces V07/)).toBeInTheDocument();
    expect(screen.getByText(/It runs only on buys captured from/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeEnabled();
    const runs = await screen.findByRole('table', { name: 'Sweep runs' });
    // No variation column on a variation's own runs; every buy is from 29 Oct on.
    expect(within(runs).queryByRole('columnheader', { name: 'Variation' })).not.toBeInTheDocument();
    const days = within(runs)
      .getAllByRole('row')
      .slice(1)
      .map((row) => within(row).getAllByRole('link')[0]!.getAttribute('href')!);
    expect(days.every((href) => href.slice('/automation/days/'.length, -1) >= '2026-10-29')).toBe(
      true,
    );
  });

  it('says "created" once after an edit, and only "replaces" after a reload', async () => {
    const opened = {
      pathname: '/automation/variations/V10',
      search: '?from=2026-10-30',
      state: { createdFrom: 'V07', created: true },
    };
    const first = renderPage(<VariationDetailPage />, {
      path: '/automation/variations/:label',
      url: opened,
    });
    expect(await screen.findByText('V10 created')).toBeInTheDocument();
    // The page never navigates by itself, since a late navigation of its own would undo a newer one: the entry keeps its
    // state and search, and is remembered as shown instead.
    expect(first.router.state.historyAction).toBe('POP');
    expect(first.router.state.location).toMatchObject({
      search: '?from=2026-10-30',
      state: opened.state,
    });
    const reloaded = first.router.state.location;
    first.unmount();

    renderPage(<VariationDetailPage />, {
      path: '/automation/variations/:label',
      url: reloaded,
    });
    expect(await screen.findByText('V10 replaces V07')).toBeInTheDocument();
    expect(screen.queryByText('V10 created')).not.toBeInTheDocument();
    expect(screen.queryByText(/that is from now on/)).not.toBeInTheDocument();
  });

  it('says when a label never existed', async () => {
    renderPage(<VariationDetailPage />, {
      path: '/automation/variations/:label',
      url: '/automation/variations/V99',
    });
    expect(await screen.findByText('No variation V99')).toBeInTheDocument();
  });

  it('links each scoreboard row to its variation', async () => {
    renderOverview();
    const board = await screen.findByRole('table', { name: 'Scoreboard' });
    expect(
      within(board).getByRole('link', { name: 'Open variation V04: settings and runs' }),
    ).toHaveAttribute('href', '/automation/variations/V04');
    expect(within(board).getByText('take profit 80%')).toBeInTheDocument();
    const grid = screen.getByRole('table', { name: 'Status by day' });
    expect(
      within(grid).getByRole('link', { name: 'Open variation V07: settings and runs' }),
    ).toBeInTheDocument();
  });
});

describe('sweep run filters', () => {
  it('sends the trading days as instants, keeps them in the URL, and asks for 50 rows', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/sweep-runs')) seen.push(new URL(request.url));
    });
    const { router } = renderPage(<AutomationSweepRunsPage />, {
      path: '/automation/sweep-runs',
      url: '/automation/sweep-runs?from=2026-10-29&to=2026-10-30',
    });
    const table = await screen.findByRole('table', { name: 'Sweep runs' });
    const last = seen.at(-1)!.searchParams;
    expect(last.get('from')).toBe('2026-10-29T07:00:00.000Z');
    expect(last.get('to')).toBe('2026-10-31T07:00:00.000Z');
    expect(last.get('page')).toBe('0');
    expect(last.get('size')).toBe('50');
    expect(screen.getByLabelText('From day')).toHaveValue('2026-10-29');
    expect(router.state.location.search).toContain('to=2026-10-30');
    for (const row of within(table).getAllByRole('row').slice(1)) {
      const day = within(row).getAllByRole('link')[0]!.getAttribute('href')!;
      expect(['2026-10-29', '2026-10-30'].some((d) => day.includes(d))).toBe(true);
    }
  });

  it('shows the token with a Solscan link from the row itself', async () => {
    renderPage(<AutomationSweepRunsPage />, {
      path: '/automation/sweep-runs',
      url: '/automation/sweep-runs',
    });
    const table = await screen.findByRole('table', { name: 'Sweep runs' });
    const row = within(table).getAllByRole('row')[1]!;
    const solscan = within(row).getByRole('link', { name: /on Solscan$/ });
    const mint = solscan.getAttribute('aria-label')!.replace(/^Open token (\S+) on Solscan$/, '$1');
    expect(solscan).toHaveAttribute('href', `https://solscan.io/token/${mint}`);
    expect(db.items.some((item) => item.mint === mint)).toBe(true);
  });

  it('never sends a day range that ends before it starts', async () => {
    const seen: URL[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/automation/sweep-runs')) seen.push(new URL(request.url));
    });
    renderPage(<AutomationSweepRunsPage />, {
      path: '/automation/sweep-runs',
      url: '/automation/sweep-runs?from=2026-10-30&to=2026-10-29',
    });
    expect(await screen.findByText('The end day is before the start day.')).toBeInTheDocument();
    expect(screen.getByText('Check the days')).toBeInTheDocument();
    expect(seen).toHaveLength(0);
  });
});

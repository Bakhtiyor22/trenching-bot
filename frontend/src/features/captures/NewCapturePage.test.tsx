import { screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../mocks/server';
import { captureConfig, captureOperation, captureSession } from '../../mocks/captureFixtures';
import type { CaptureSummary } from '../../api/captures';
import type { WalletAction, WalletActivity } from '../../api/setup';
import { BASE_MINT, WALLET } from '../../mocks/fixtures';
import { renderPage } from '../../test/render';
import { NewCapturePage } from './NewCapturePage';
import { CapturesPage } from './CapturesPage';

describe('capture setup and list', () => {
  it('keeps next-step navigation enabled while another capture is running', async () => {
    server.use(
      http.get('/api/captures', () =>
        HttpResponse.json({
          items: [
            { ...captureSession, id: 'idle-capture', name: 'Idle capture' },
            {
              ...captureSession,
              id: 'running-capture',
              name: 'Running capture',
              operation: { ...captureOperation, state: 'RUNNING', endedAt: null },
            },
          ],
          page: 0,
          size: 20,
          total: 2,
          statusCounts: { RUNNING: 1, NOT_STARTED: 1 },
        }),
      ),
    );
    renderPage(<CapturesPage />, { path: '/captures', url: '/captures' });
    const row = (await screen.findByRole('link', { name: 'Idle capture' })).closest('tr')!;
    const next = within(row).getByRole('link', { name: 'Open capture' });
    expect(next).toHaveAttribute('href', '/captures/idle-capture');
    expect(next).not.toHaveAttribute('data-disabled');
    expect(next).not.toHaveAttribute('aria-disabled', 'true');
  });
  it('prefills the suggested wallet and makes no activity call until the token step is opened', async () => {
    let calls = 0;
    server.use(
      http.get('/api/wallets/:address/activity', () => {
        calls++;
        return HttpResponse.json({
          wallet: WALLET,
          source: 'pumpfun-user-volume-accumulator',
          actions: [],
          unreadable: 0,
          scanned: 0,
          nextBefore: null,
          requestsUsed: 1,
          cachedAt: null,
        });
      }),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    expect(await screen.findByLabelText(/^Wallet address/)).toHaveValue(WALLET);
    expect(calls).toBe(0);
    await user.click(screen.getByRole('button', { name: 'Choose from recent wallet activity' }));
    await screen.findByText('No Pump.fun buys or sells found');
    expect(calls).toBe(1);
  });
  it('chooses a buy, disables sell selection, and creates a wallet-token capture with a generated name', async () => {
    let body: unknown;
    server.use(
      http.post('/api/captures', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(captureSession);
      }),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
      extraRoutes: [{ path: '/captures/:id', element: <div>Capture created</div> }],
    });
    await screen.findByLabelText(/^Wallet address/);
    await user.click(screen.getByRole('button', { name: 'Choose from recent wallet activity' }));
    await user.click(
      await screen.findByRole('radio', { name: `Select ${BASE_MINT} at slot 1000` }),
    );
    expect(screen.getByLabelText('Token mint address')).toHaveValue(BASE_MINT);
    await user.click(screen.getByRole('button', { name: 'Buys only' }));
    expect(
      await screen.findByRole('radio', { name: `Select ${BASE_MINT} at slot 999` }),
    ).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Create only' }));
    await screen.findByText('Capture created');
    expect(body).toMatchObject({
      name: '',
      timeZone: expect.any(String),
      wallet: { wallet: WALLET, scope: 'wallet-token', mint: BASE_MINT },
    });
  });
  it('keeps a typed name after a conflict and offers the server suggestion', async () => {
    server.use(
      http.post('/api/captures', () =>
        HttpResponse.json(
          {
            code: 'NAME_TAKEN',
            message: 'Capture name is already taken',
            fields: { name: 'is already taken' },
            suggestion: 'Research (2)',
          },
          { status: 409 },
        ),
      ),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    await user.type(await screen.findByLabelText(/^Session name/), 'Research');
    await user.type(screen.getByLabelText('Token mint address'), BASE_MINT);
    await user.click(screen.getByRole('button', { name: 'Create only' }));
    await user.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(screen.getByLabelText('Session name')).toHaveValue('Research (2)');
  });
  it('restores suggestion values with Undo and dismisses suggestions for the next visit', async () => {
    localStorage.clear();
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    const title = await screen.findByText('Capture one token at a time');
    const card = title.parentElement!.parentElement!;
    expect(within(card).getByText('Applied')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Discovery scope' })).toHaveValue('Wallet + token');
    await user.click(within(card).getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('combobox', { name: 'Discovery scope' })).toHaveValue(
      'Pump.fun user buys',
    );
    await user.click(within(card).getByRole('button', { name: 'Dismiss' }));
    expect(localStorage.getItem('rec-dismissed:SCOPE_WALLET_TOKEN')).toBe('true');
    localStorage.clear();
  });
  it('sends list sort and filter parameters and preserves them in the URL', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/captures', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({
          items: [],
          page: 0,
          size: 20,
          total: 0,
          statusCounts: { NEEDS_ATTENTION: 2 },
        });
      }),
    );
    const { user, router } = renderPage(<CapturesPage />, {
      path: '/captures',
      url: '/captures?sort=name%2Casc',
    });
    await user.click(await screen.findByRole('button', { name: 'Needs attention · 2' }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.get('status')).toBe('NEEDS_ATTENTION'),
    );
    expect(router.state.location.search).toContain('status=NEEDS_ATTENTION');
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('name,asc');
    expect(screen.queryByLabelText('Session name')).not.toBeInTheDocument();
  });
});

const action: WalletAction = {
  signature: '3'.repeat(64),
  instructionIndex: 0,
  innerIndex: null,
  slot: 1010,
  blockTime: '2026-10-16T12:00:02Z',
  side: 'BUY',
  mint: BASE_MINT,
  solLamports: '2400000001',
  tokenRaw: '100000000',
  tokenDecimals: 6,
  venue: 'PUMPFUN_CURVE',
  earlierActionInPage: false,
  overSignalThreshold: true,
  signalBasis: 'CURVE_PAYMENT_EXCLUDING_FEES',
  capturedIn: [],
};
const activityPage: WalletActivity = {
  wallet: WALLET,
  source: 'pumpfun-user-volume-accumulator',
  actions: [action],
  unreadable: 0,
  scanned: 1,
  nextBefore: null,
  requestsUsed: 3,
  cachedAt: '2026-10-16T12:00:05Z',
};
const summary: CaptureSummary = {
  status: 'STOPPED_LIMIT',
  reasonCode: null,
  currentStep: { index: 2, stage: 'MARKETS' },
  steps: [
    { stage: 'WALLET', state: 'DONE' },
    { stage: 'MARKETS', state: 'STOPPED' },
    { stage: 'RATES', state: 'TODO' },
    { stage: 'DATASET', state: 'TODO' },
  ],
  progress: { processed: 1323, selected: 1748, percent: '75' },
  nextAction: {
    kind: 'RESUME_MARKETS',
    stage: 'MARKETS',
    attemptId: null,
    caseId: null,
    maxRequests: 400,
  },
  cases: { total: 3, ready: 2, blocked: 1, downloading: 0 },
  lastProgressAt: null,
};

describe('capture design states', () => {
  it('separates list status, progress and navigation with readable legacy and wallet context', async () => {
    server.use(
      http.get('/api/captures', () =>
        HttpResponse.json({
          items: [{ ...captureSession, name: 'Research', displayName: 'Research (1)', summary }],
          page: 0,
          size: 20,
          total: 1,
          statusCounts: { STOPPED_LIMIT: 1, DONE: 0 },
        }),
      ),
    );
    renderPage(<CapturesPage />, { path: '/captures', url: '/captures' });
    await screen.findByRole('link', { name: 'Research (1)' });
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Capture',
      'Status',
      'Progress',
      'Next step',
      'Created ↓',
      '',
    ]);
    expect(
      screen.getByText('Two captures share this name; numbered by creation time'),
    ).toBeInTheDocument();
    expect(screen.getByText('2 of 3 tokens ready; 1 still blocked')).toBeInTheDocument();
    expect(
      screen.getByRole('img', {
        name: 'Wallet done, Markets stopped, Rates waiting, Dataset waiting',
      }),
    ).toBeInTheDocument();
    expect(screen.getByText('Step 2 of 4 · Markets')).toBeInTheDocument();
    expect(screen.getByText('1,323 of 1,748 transactions · 75%')).toBeInTheDocument();
    expect(screen.getByText('At most 400 requests')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'All · 1' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('button', { name: 'Done · 0' })).not.toBeInTheDocument();
    expect(screen.getByText('Showing 1–1 of 1 · Newest first')).toBeInTheDocument();
  });
  it('keeps a selected zero-count filter visible and offers Decu and attach actions when empty', async () => {
    server.use(
      http.get('/api/captures', () =>
        HttpResponse.json({ items: [], page: 0, size: 20, total: 0, statusCounts: { DONE: 0 } }),
      ),
    );
    renderPage(<CapturesPage />, { path: '/captures', url: '/captures?status=DONE' });
    expect(await screen.findByRole('button', { name: 'Done · 0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(await screen.findByRole('link', { name: 'New capture with Decu' })).toHaveAttribute(
      'href',
      '/captures/new',
    );
    expect(screen.getByRole('link', { name: 'Attach a saved capture folder' })).toHaveAttribute(
      'href',
      '/captures/new?mode=attach',
    );
  });
  it('preselects attachment from the empty-state link', async () => {
    renderPage(<NewCapturePage />, { path: '/captures/new', url: '/captures/new?mode=attach' });
    expect(await screen.findByRole('tab', { name: 'Attach existing' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByLabelText(/^Wallet capture directory/)).toBeInTheDocument();
    // Attaching sets no scope, cutoff or recovery, so those suggestions are not offered.
    expect(screen.queryByRole('heading', { name: 'Suggestions' })).not.toBeInTheDocument();
    // The generated-name preview never shows an empty part before a folder is typed.
    expect(screen.getAllByText(/^Attached · wallet folder · /).length).toBeGreaterThan(0);
  });
  it('keeps the original scope defaults when there is no suggested wallet', async () => {
    server.use(
      http.get('/api/captures/config', () =>
        HttpResponse.json({ ...captureConfig, suggestedWallets: [] }),
      ),
    );
    renderPage(<NewCapturePage />, { path: '/captures/new', url: '/captures/new' });
    expect(await screen.findByLabelText(/^Wallet address/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: 'Discovery scope' })).toHaveValue(
      'Pump.fun user buys',
    );
  });
  it('shows ordered activity columns, exact SOL titles and notes, with selected-row highlighting', async () => {
    const actions = [
      action,
      {
        ...action,
        signature: '4'.repeat(64),
        slot: 1009,
        side: 'SELL',
        capturedIn: [{ captureId: 'existing', displayName: 'Earlier capture' }],
        earlierActionInPage: true,
      },
      {
        ...action,
        signature: '5'.repeat(64),
        slot: 1008,
        capturedIn: [{ captureId: 'existing', displayName: 'Earlier capture' }],
        earlierActionInPage: true,
      },
      {
        ...action,
        signature: '6'.repeat(64),
        slot: 1007,
        earlierActionInPage: true,
        overSignalThreshold: false,
      },
      {
        ...action,
        signature: '7'.repeat(64),
        slot: 1006,
        overSignalThreshold: false,
        solLamports: '1900000000',
      },
    ];
    server.use(
      http.get('/api/wallets/:address/activity', () =>
        HttpResponse.json({ ...activityPage, actions }),
      ),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    await user.click(
      await screen.findByRole('button', { name: 'Choose from recent wallet activity' }),
    );
    const radio = await screen.findByRole('radio', { name: `Select ${BASE_MINT} at slot 1010` });
    await user.click(radio);
    expect(radio).toBeChecked();
    expect(radio.closest('tr')).toHaveStyle({ background: 'var(--mantine-color-blue-light)' });
    await user.click(screen.getByRole('button', { name: 'Buys only' }));
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Select',
      'When',
      'Action',
      'Token',
      'SOL',
      'Notes',
    ]);
    expect(screen.getAllByTitle('2.400000001')[0]).toHaveTextContent('2.4');
    expect(screen.getByTitle('1.9')).toHaveTextContent('1.9');
    expect(screen.getByText('Sells cannot start a capture')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Earlier capture' })).toHaveAttribute(
      'href',
      '/captures/existing',
    );
    expect(screen.getByText('An earlier action on this token is in the list')).toBeInTheDocument();
    expect(
      screen.getByText('At or under 1.9 SOL: the agreed strategy skips it'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('No earlier action in this list (proof comes in the wallet step)'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/No first-buy claim/)).not.toBeInTheDocument();
    expect(screen.getByText(/3 requests \(from cache\)/)).toHaveTextContent(
      /Loaded .* · 3 requests \(from cache\)/,
    );
    await user.click(screen.getByRole('button', { name: 'Token not listed? Paste a token mint' }));
    expect(screen.getByLabelText('Token mint address')).toHaveFocus();
  });
  it('cancels activity loading without another provider request', async () => {
    server.use(
      http.get('/api/wallets/:address/activity', async () => {
        await delay('infinite');
        return HttpResponse.json(activityPage);
      }),
    );
    const { user, queryClient } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    await user.click(
      await screen.findByRole('button', { name: 'Choose from recent wallet activity' }),
    );
    expect(await screen.findByText("Loading Decu's latest actions…")).toBeInTheDocument();
    expect(screen.getByText('at most 22 requests')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel loading' }));
    await waitFor(() =>
      expect(queryClient.isFetching({ queryKey: ['wallet-activity', WALLET] })).toBe(0),
    );
    expect(screen.getByText(/Activity loading cancelled/)).toBeInTheDocument();
  });
  it.each(['Load 20 older', 'Refresh activity'])(
    'keeps rows and blocks every activity fetch after %s is rate limited',
    async (actionName) => {
      let calls = 0;
      server.use(
        http.get('/api/wallets/:address/activity', () => {
          calls++;
          return calls === 2
            ? HttpResponse.json(
                { code: 'PROVIDER_RATE_LIMITED', message: 'Pause', retryAfterSeconds: 1 },
                { status: 429 },
              )
            : HttpResponse.json({ ...activityPage, nextBefore: 'older' });
        }),
      );
      const { user } = renderPage(<NewCapturePage />, {
        path: '/captures/new',
        url: '/captures/new',
      });
      await user.click(
        await screen.findByRole('button', { name: 'Choose from recent wallet activity' }),
      );
      await screen.findByRole('radio', { name: `Select ${BASE_MINT} at slot 1010` });
      await user.click(screen.getByRole('button', { name: actionName }));
      expect(await screen.findByText('The provider is busy (rate limited)')).toBeInTheDocument();
      expect(
        screen.getByRole('radio', { name: `Select ${BASE_MINT} at slot 1010` }),
      ).toBeInTheDocument();
      const retry = screen.getByRole('button', { name: 'Try now' });
      expect(retry).toBeDisabled();
      const refresh = screen.getByRole('button', { name: 'Refresh activity' });
      const older = screen.getByRole('button', { name: 'Load 20 older' });
      expect(refresh).toBeDisabled();
      expect(older).toBeDisabled();
      await user.click(refresh);
      await user.click(older);
      await user.click(retry);
      expect(calls).toBe(2);
      await waitFor(() => expect(retry).toBeEnabled(), { timeout: 3000 });
      expect(refresh).toBeEnabled();
      expect(older).toBeEnabled();
      await user.click(retry);
      await waitFor(() => expect(calls).toBe(3));
      expect(
        screen.getByRole('radio', { name: `Select ${BASE_MINT} at slot 1010` }),
      ).toBeInTheDocument();
    },
  );
  it('offers older activity when the empty page has a cursor', async () => {
    server.use(
      http.get('/api/wallets/:address/activity', () =>
        HttpResponse.json({ ...activityPage, actions: [], nextBefore: 'older' }),
      ),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    await user.click(
      await screen.findByRole('button', { name: 'Choose from recent wallet activity' }),
    );
    expect(await screen.findByRole('button', { name: 'Look further back' })).toBeInTheDocument();
  });
  it('shows checked-name feedback and disables the primary start action with a setup reason', async () => {
    server.use(
      http.get('/api/captures/config', () =>
        HttpResponse.json({ ...captureConfig, rpcConfigured: false }),
      ),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    await user.type(await screen.findByLabelText('Session name'), 'Available name');
    expect(await screen.findByText('✓ Name is free')).toBeInTheDocument();
    const start = screen.getByRole('button', { name: 'Create and start wallet step' });
    expect(start).toBeDisabled();
    expect(start).toHaveAttribute('data-variant', 'filled');
    expect(screen.getByText(/Configure SOLANA_RPC_URL for the backend/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create only' })).toHaveAttribute(
      'data-variant',
      'default',
    );
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/captures');
  });
});

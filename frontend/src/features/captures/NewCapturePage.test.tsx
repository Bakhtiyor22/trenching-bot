import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../mocks/server';
import { captureSession } from '../../mocks/captureFixtures';
import { BASE_MINT, WALLET } from '../../mocks/fixtures';
import { renderPage } from '../../test/render';
import { NewCapturePage } from './NewCapturePage';
import { CapturesPage } from './CapturesPage';

describe('capture setup and list', () => {
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
    await screen.findByText(
      'No trades match these filters. Paste a token mint below or load older activity.',
    );
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
    await user.click(screen.getByLabelText('Buys only'));
    expect(
      await screen.findByRole('radio', { name: `Select ${BASE_MINT} at slot 999` }),
    ).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Create capture' }));
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
    await user.click(screen.getByRole('button', { name: 'Create capture' }));
    await user.click(await screen.findByRole('button', { name: 'Use suggestion: Research (2)' }));
    expect(screen.getByLabelText('Session name')).toHaveValue('Research (2)');
  });
  it('restores suggestion values with Undo and dismisses suggestions for the next visit', async () => {
    localStorage.clear();
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures/new',
      url: '/captures/new',
    });
    const title = await screen.findByText('Focus on one wallet and token');
    const card = title.parentElement!;
    await user.click(within(card).getByRole('button', { name: 'Apply' }));
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
    await user.click(await screen.findByRole('button', { name: 'Needs attention (2)' }));
    await waitFor(() =>
      expect(requests.at(-1)?.searchParams.get('status')).toBe('NEEDS_ATTENTION'),
    );
    expect(router.state.location.search).toContain('status=NEEDS_ATTENTION');
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('name,asc');
    expect(screen.queryByLabelText('Session name')).not.toBeInTheDocument();
  });
});

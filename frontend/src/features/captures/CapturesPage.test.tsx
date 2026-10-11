import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { CAPTURE_ID, captureConfig, captureOperation, captureSession, readyInspection, walletTokenSessions } from '../../mocks/captureFixtures';
import { BASE_MINT, WALLET } from '../../mocks/fixtures';
import { CapturesPage } from './CapturesPage';
import { CaptureDetailPage } from './CaptureDetailPage';

function detail() { return renderPage(<CaptureDetailPage />, { path: '/captures/:id', url: '/captures/capture-demo' }); }

describe('Capture console', () => {
  it('creates a bounded wallet sample with explicit discovery scope', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.post('/api/captures', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(captureSession);
    }));
    const { user } = renderPage(<CapturesPage />, { path: '/captures', url: '/captures', extraRoutes: [{ path: '/captures/:id', element: <div>Created session</div> }] });
    await user.type(await screen.findByLabelText(/Session name/), 'First sample');
    await user.type(screen.getByLabelText(/Wallet address/), WALLET);
    await user.click(screen.getByRole('button', { name: 'Create session' }));
    expect(await screen.findByText('Created session')).toBeInTheDocument();
    expect(body).toMatchObject({ name: 'First sample', wallet: { wallet: WALLET, scope: 'pumpfun-user-buys', limit: 1, scanPages: 20 } });
    expect(body?.wallet).not.toHaveProperty('mint');
    expect(body?.requestId).toEqual(expect.any(String));
  });
  it('asks for a token mint only for the wallet + token scope and submits its defaults', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.post('/api/captures', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(captureSession);
    }));
    const { user } = renderPage(<CapturesPage />, { path: '/captures', url: '/captures', extraRoutes: [{ path: '/captures/:id', element: <div>Created session</div> }] });
    await user.type(await screen.findByLabelText(/Session name/), 'Token case');
    await user.type(screen.getByLabelText(/Wallet address/), WALLET);
    expect(screen.queryByLabelText(/Token mint address/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Discovery scope' }));
    await user.click(await screen.findByRole('option', { name: 'Wallet + token' }));
    expect(screen.getByText(/qualifies is only known after market capture/)).toBeInTheDocument();
    expect(screen.getByText(/Effective settings: 100 transaction\(s\), 5 scan pages/)).toBeInTheDocument();
    const mint = screen.getByLabelText(/Token mint address/);
    await user.type(mint, 'not-a-mint');
    await user.click(screen.getByRole('button', { name: 'Create session' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid token mint address.');
    await user.clear(mint);
    await user.type(mint, BASE_MINT);
    await user.click(screen.getByRole('button', { name: 'Create session' }));
    expect(await screen.findByText('Created session')).toBeInTheDocument();
    expect(body).toMatchObject({ name: 'Token case', wallet: { wallet: WALLET, scope: 'wallet-token', limit: 100, scanPages: 5, mint: BASE_MINT } });
  });
  it('restores the default sample when leaving the wallet + token scope', async () => {
    const { user } = renderPage(<CapturesPage />, { path: '/captures', url: '/captures' });
    const scope = await screen.findByRole('combobox', { name: 'Discovery scope' });
    await user.click(scope);
    await user.click(await screen.findByRole('option', { name: 'Wallet + token' }));
    await user.click(scope);
    await user.click(await screen.findByRole('option', { name: 'Wallet references' }));
    expect(screen.queryByLabelText(/Token mint address/)).not.toBeInTheDocument();
    expect(screen.getByText(/Effective settings: 1 transaction\(s\), 20 scan pages/)).toBeInTheDocument();
  });
  it('defaults the wallet budget of a wallet + token session to its larger preset', async () => {
    let body: Record<string, unknown> | undefined;
    const session = walletTokenSessions.incomplete;
    server.use(
      http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...session, id: CAPTURE_ID, inspection: { ...session.inspection!, wallet: { state: 'INCOMPLETE', reason: 'WALLET_CAPTURE_INCOMPLETE', canStart: false, canResume: true, requiresNewAttempt: false } } })),
      http.post('/api/captures/:id/operations', async ({ request }) => {
        body = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ ...captureSession.operation, id: 'wallet-op', step: 'WALLET', state: 'RUNNING', endedAt: null }, { status: 202 });
      }),
    );
    const { user } = detail();
    await user.click(await screen.findByRole('button', { name: 'Resume wallet capture' }));
    await waitFor(() => expect(body).toMatchObject({ step: 'WALLET', budget: captureConfig.walletTokenBudgetDefaults }));
  });
  it('keeps rates disabled for incomplete market coverage and offers fresh over-cap attempts', async () => {
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, marketAttempt: { ...captureSession.marketAttempt!, market: { scanPages: 50, maxTransactions: 300 } }, inspection: { ...readyInspection, market: { state: 'INCOMPLETE', reason: 'OVER_CAP', canStart: false, canResume: false, requiresNewAttempt: true }, rates: { state: 'NOT_STARTED', reason: 'MARKET_CAPTURE_NOT_READY', canStart: false, canResume: false, requiresNewAttempt: false }, canExport: false, canCalibrate: false } })));
    const { user } = detail();
    await user.click(await screen.findByRole('tab', { name: /Rates/ }));
    expect(screen.getByRole('button', { name: 'Start rates capture' })).toBeDisabled();
    await user.click(screen.getByRole('tab', { name: /Markets/ }));
    expect(screen.getByText(/capacity was exceeded/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New market attempt' })).toBeEnabled();
  });
  it('retains the identical operation request after a lost response', async () => {
    const requests: unknown[] = [];
    server.use(http.post('/api/captures/:id/operations', async ({ request }) => {
      requests.push(await request.json());
      if (requests.length === 1) return HttpResponse.error();
      return HttpResponse.json({ ...captureSession.operation, id: 'operation-new', requestId: (requests[0] as {requestId: string}).requestId, step: 'INSPECT', state: 'SUCCEEDED' });
    }));
    const { user } = detail();
    await user.click(await screen.findByRole('button', { name: 'Inspect evidence' }));
    await user.click(await screen.findByRole('button', { name: 'Reconcile and retry request' }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toEqual(requests[0]);
  });
  it('renders exact calibration values and independent dataset handoff', async () => {
    const { user } = detail();
    await user.click(await screen.findByRole('tab', { name: /Calibration/ }));
    expect(await screen.findByText('-1234.5')).toBeInTheDocument();
    expect(screen.getByText('5000.5')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download calibration JSON' })).toHaveAttribute('href', '/api/captures/capture-demo/downloads/calibration');
    await user.click(screen.getByRole('tab', { name: /Dataset/ }));
    await user.click(screen.getByRole('button', { name: 'Import dataset' }));
    expect(await screen.findByRole('link', { name: 'New run' })).toBeInTheDocument();
  });
  it('attaches existing evidence by root-relative paths', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.post('/api/captures', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(captureSession);
    }));
    const { user } = renderPage(<CapturesPage />, { path: '/captures', url: '/captures', extraRoutes: [{ path: '/captures/:id', element: <div>Attached session</div> }] });
    await user.click(await screen.findByRole('tab', { name: 'Attach existing' }));
    await user.type(screen.getByLabelText(/Session name/), 'Decu');
    await user.type(screen.getByLabelText(/Wallet capture directory/), 'decu-pumpfun-buys');
    await user.type(screen.getByLabelText(/Market capture directory/), 'decu-dip-markets');
    await user.click(screen.getByRole('button', { name: 'Attach and inspect' }));
    expect(await screen.findByText('Attached session')).toBeInTheDocument();
    expect(body).toMatchObject({ name: 'Decu', attach: { wallet: 'decu-pumpfun-buys', market: 'decu-dip-markets' } });
    expect(body).not.toHaveProperty('wallet');
  });
  it('shows the per-run budget apart from cumulative usage and stops polling at a terminal state', async () => {
    let polls = 0;
    const running = { ...captureOperation, step: 'MARKET' as const, state: 'RUNNING', endedAt: null, budget: { requestBudget: 3, byteBudget: 1000, operationTimeoutMinutes: 10 }, progress: { phase: 'LISTING', at: '2026-10-10T09:10:01Z', operationRequests: 3, operationBytes: 900, cumulativeRequests: 5, cumulativeBytes: 1500, processed: 4, selected: 2 } };
    server.use(http.get('/api/captures/capture-demo', () => {
      polls += 1;
      return HttpResponse.json({ ...captureSession, operation: polls === 1 ? running : { ...running, state: 'SUCCEEDED', endedAt: '2026-10-10T09:10:05Z' } });
    }));
    detail();
    expect(await screen.findByText('Budget for this run')).toBeInTheDocument();
    expect(screen.getByText('Total used for this capture')).toBeInTheDocument();
    expect(screen.getByText('3 / 3')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(await screen.findByText('SUCCEEDED', {}, { timeout: 5000 })).toBeInTheDocument();
    const settled = polls;
    await new Promise(resolve => setTimeout(resolve, 2500));
    expect(polls).toBe(settled);
  });
  it('disables acquisition when the backend has no RPC configuration', async () => {
    server.use(
      http.get('/api/captures/config', () => HttpResponse.json({ ...captureConfig, rpcConfigured: false })),
      http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, inspection: { ...readyInspection, wallet: { state: 'INCOMPLETE', reason: null, canStart: false, canResume: true, requiresNewAttempt: false } } })),
    );
    detail();
    expect(await screen.findByText(/Solana RPC acquisition is not configured/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resume wallet capture' })).toBeDisabled();
  });
  it('sends the chosen operation deadline with the wallet resume', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, inspection: { ...readyInspection, wallet: { state: 'INCOMPLETE', reason: 'WALLET_CAPTURE_INCOMPLETE', canStart: false, canResume: true, requiresNewAttempt: false } } })),
      http.post('/api/captures/:id/operations', async ({ request }) => {
        body = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ ...captureSession.operation, id: 'wallet-op', step: 'WALLET', state: 'RUNNING', endedAt: null }, { status: 202 });
      }),
    );
    const { user } = detail();
    await user.click(await screen.findByRole('button', { name: 'Advanced operation settings' }));
    const deadline = await screen.findByLabelText(/Operation deadline/);
    await user.clear(deadline);
    await user.type(deadline, '30');
    expect(screen.getByText(/30-minute deadline/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Resume wallet capture' }));
    await waitFor(() => expect(body).toMatchObject({ step: 'WALLET', budget: { requestBudget: 400, byteBudget: 104857600, operationTimeoutMinutes: 30 } }));
  });
  it('explains a deadline stop on the latest operation', async () => {
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, operation: { ...captureOperation, step: 'MARKET', state: 'INCOMPLETE', code: 'DEADLINE', message: 'The operation deadline ended this run. Saved evidence is kept; resume to continue with a fresh allowance.' } })));
    detail();
    expect(await screen.findByText('Stopped: DEADLINE')).toBeInTheDocument();
    expect(screen.getByText(/resume to continue with a fresh allowance/)).toBeInTheDocument();
  });
  it('labels each calibrated buy with its instruction', async () => {
    const { user } = detail();
    await user.click(await screen.findByRole('tab', { name: /Calibration/ }));
    expect(await screen.findByText('buy exact SOL in (v3)')).toBeInTheDocument();
    expect(screen.getByText('BUY_EXACT_QUOTE_IN_V3 1')).toBeInTheDocument();
  });
  it('deletes a capture after confirmation and returns to the list', async () => {
    const { user } = renderPage(<CaptureDetailPage />, { path: '/captures/:id', url: '/captures/capture-demo', extraRoutes: [{ path: '/captures', element: <div>Capture list</div> }] });
    await user.click(await screen.findByRole('button', { name: 'Delete capture' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Attached capture directories are only referenced and stay untouched/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Capture list')).toBeInTheDocument();
  });
  it('cannot delete a capture while its operation runs', async () => {
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, operation: { ...captureOperation, state: 'RUNNING', endedAt: null } })));
    detail();
    expect(await screen.findByRole('button', { name: 'Delete capture' })).toBeDisabled();
  });
  it('points to a smaller sample when the attempt is already over cap at the maximum capacity', async () => {
    const overCap = { state: 'UNUSABLE', reason: 'OVER_CAP', canStart: false, canResume: false, requiresNewAttempt: true };
    const atMaximum = { ...captureSession, inspection: { ...readyInspection, market: overCap, canExport: false, canCalibrate: false } };
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json(atMaximum)));
    const { user } = detail();
    await user.click(await screen.findByRole('tab', { name: /Markets/ }));
    expect(screen.getByText(/more market transactions than the maximum capacity allows/)).toBeInTheDocument();
    expect(screen.queryByText(/Create a new market attempt with a larger capacity/)).not.toBeInTheDocument();
  });
  it('still suggests a larger capacity when the attempt is below the maximum', async () => {
    const overCap = { state: 'UNUSABLE', reason: 'OVER_CAP', canStart: false, canResume: false, requiresNewAttempt: true };
    const marketAttempt = { ...captureSession.marketAttempt!, market: { scanPages: 50, maxTransactions: 300 } };
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json({ ...captureSession, marketAttempt, inspection: { ...readyInspection, market: overCap } })));
    const { user } = detail();
    await user.click(await screen.findByRole('tab', { name: /Markets/ }));
    expect(screen.getByText(/Create a new market attempt with a larger capacity/)).toBeInTheDocument();
  });
});

import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import { server } from '../../mocks/server';
import { setCaptureCases } from '../../mocks/captureHandlers';
import type { CaptureCase } from '../../api/captures';
import { renderPage } from '../../test/render';
import {
  CAPTURE_ID,
  captureConfig,
  captureOperation,
  captureSession,
  readyInspection,
  walletTokenSessions,
} from '../../mocks/captureFixtures';
import { BASE_MINT, WALLET } from '../../mocks/fixtures';
import { NewCapturePage } from './NewCapturePage';
import { CaptureDetailPage } from './CaptureDetailPage';

async function detail() {
  const result = renderPage(<CaptureDetailPage />, {
    path: '/captures/:id',
    url: '/captures/capture-demo',
  });
  await result.user.click(await screen.findByText('Advanced: limits, attempts and history'));
  return result;
}

afterEach(() => Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 }));

describe('Capture console', () => {
  it('creates a bounded wallet sample with explicit discovery scope', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/captures/config', () =>
        HttpResponse.json({ ...captureConfig, suggestedWallets: [] }),
      ),
      http.post('/api/captures', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(captureSession);
      }),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures',
      url: '/captures',
      extraRoutes: [{ path: '/captures/:id', element: <div>Created session</div> }],
    });
    await user.type(await screen.findByLabelText(/Session name/), 'First sample');
    await user.clear(screen.getByLabelText(/Wallet address/));
    await user.paste(WALLET);
    await user.click(screen.getByRole('button', { name: 'Create only' }));
    expect(await screen.findByText('Created session')).toBeInTheDocument();
    expect(body).toMatchObject({
      name: 'First sample',
      wallet: { wallet: WALLET, scope: 'pumpfun-user-buys', limit: 1, scanPages: 20 },
    });
    expect(body?.wallet).not.toHaveProperty('mint');
    expect(body?.requestId).toEqual(expect.any(String));
  });
  it('asks for a token mint only for the wallet + token scope and submits its defaults', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post('/api/captures', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(captureSession);
      }),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures',
      url: '/captures',
      extraRoutes: [{ path: '/captures/:id', element: <div>Created session</div> }],
    });
    await user.type(await screen.findByLabelText(/Session name/), 'Token case');
    await user.clear(screen.getByLabelText(/Wallet address/));
    await user.type(screen.getByLabelText(/Wallet address/), WALLET);
    expect(screen.getByLabelText(/Token mint address/)).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Discovery scope' }));
    await user.click(await screen.findByRole('option', { name: 'Wallet + token' }));
    expect(screen.getByText(/qualifies is only known after market capture/)).toBeInTheDocument();
    expect(
      screen.getByText(/Effective settings: 1000 transaction\(s\), 20 scan pages/),
    ).toBeInTheDocument();
    const mint = screen.getByLabelText(/Token mint address/);
    await user.type(mint, 'not-a-mint');
    await user.click(screen.getByRole('button', { name: 'Create only' }));
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('alert')
          .some((alert) => alert.textContent?.includes('Enter a valid token mint address.')),
      ).toBe(true),
    );
    await user.clear(mint);
    await user.paste(BASE_MINT);
    await user.click(screen.getByRole('button', { name: 'Create only' }));
    expect(await screen.findByText('Created session')).toBeInTheDocument();
    expect(body).toMatchObject({
      name: 'Token case',
      wallet: {
        wallet: WALLET,
        scope: 'wallet-token',
        limit: 1000,
        scanPages: 20,
        mint: BASE_MINT,
      },
    });
  });
  it('restores the default sample when leaving the wallet + token scope', async () => {
    const { user } = renderPage(<NewCapturePage />, { path: '/captures', url: '/captures' });
    const scope = await screen.findByRole('combobox', { name: 'Discovery scope' });
    await user.click(scope);
    await user.click(await screen.findByRole('option', { name: 'Wallet + token' }));
    await user.click(scope);
    await user.click(await screen.findByRole('option', { name: 'Wallet references' }));
    expect(screen.getByLabelText(/Token mint address/)).toBeInTheDocument();
    expect(
      screen.getByText(/Effective settings: 1 transaction\(s\), 20 scan pages/),
    ).toBeInTheDocument();
  });
  it('explains capped wallet history and offers no wallet start or resume action', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          wallet: { ...captureConfig.walletTokenDefaults, wallet: WALLET, mint: BASE_MINT },
          datasetReady: false,
          calibrationReady: false,
          inspection: {
            ...readyInspection,
            wallet: {
              state: 'INCOMPLETE',
              reason: 'HISTORY_NOT_EXHAUSTED',
              canStart: false,
              canResume: false,
              requiresNewAttempt: true,
            },
            market: {
              state: 'NOT_STARTED',
              reason: 'WALLET_CAPTURE_NOT_READY',
              canStart: false,
              canResume: false,
              requiresNewAttempt: false,
            },
            rates: {
              state: 'NOT_STARTED',
              reason: 'MARKET_CAPTURE_NOT_READY',
              canStart: false,
              canResume: false,
              requiresNewAttempt: false,
            },
            canExport: false,
            canCalibrate: false,
            marketWindows: [],
          },
        }),
      ),
    );
    await detail();
    const walletPanel = await screen.findByRole('tabpanel', { name: 'Wallet' });
    expect(
      within(walletPanel).getByText(
        "The token-account history was not read to its end: the listing stopped at the sample's record or page limit, so an earlier buy cannot be ruled out. Resuming cannot finish this capture; start a new session with a larger sample or more wallet scan pages.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /^(Start|Resume) wallet capture$/ }),
    ).not.toBeInTheDocument();
    expect(within(walletPanel).getByText('PUMPFUN_BONDING_CURVE_BUY')).toBeInTheDocument();
  });
  it('defaults the wallet budget of a wallet + token session to its larger preset', async () => {
    let body: Record<string, unknown> | undefined;
    const session = walletTokenSessions.incomplete;
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...session,
          id: CAPTURE_ID,
          inspection: {
            ...session.inspection!,
            wallet: {
              state: 'INCOMPLETE',
              reason: 'WALLET_CAPTURE_INCOMPLETE',
              canStart: false,
              canResume: true,
              requiresNewAttempt: false,
            },
          },
        }),
      ),
      http.post('/api/captures/:id/operations', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            ...captureSession.operation,
            id: 'wallet-op',
            step: 'WALLET',
            state: 'RUNNING',
            endedAt: null,
          },
          { status: 202 },
        );
      }),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('button', { name: 'Resume wallet capture' }));
    await waitFor(() =>
      expect(body).toMatchObject({
        step: 'WALLET',
        budget: captureConfig.walletTokenBudgetDefaults,
      }),
    );
  });
  it('opts into bounded recovery with visible totals and sends the configured attempt limit', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...walletTokenSessions.incomplete,
          id: CAPTURE_ID,
          inspection: {
            ...walletTokenSessions.incomplete.inspection!,
            wallet: {
              state: 'INCOMPLETE',
              reason: 'WALLET_CAPTURE_INCOMPLETE',
              canStart: false,
              canResume: true,
              requiresNewAttempt: false,
            },
          },
        }),
      ),
      http.post('/api/captures/:id/operations', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            ...captureOperation,
            id: 'wallet-recovery',
            step: 'WALLET',
            state: 'RUNNING',
            endedAt: null,
          },
          { status: 202 },
        );
      }),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('button', { name: 'Advanced operation settings' }));
    await user.click(
      await screen.findByRole('checkbox', { name: 'Enable automatic recovery for this stage' }),
    );
    expect(screen.getByLabelText('Total request budget')).toHaveValue('3000');
    expect(screen.getByLabelText('Total byte budget')).toHaveValue('314572800');
    expect(screen.getByLabelText('Maximum attempts')).toHaveValue('3');
    await user.clear(screen.getByLabelText('Total request budget'));
    await user.type(screen.getByLabelText('Total request budget'), '12000');
    await user.click(screen.getByRole('button', { name: 'Resume wallet capture' }));
    await waitFor(() =>
      expect(body).toMatchObject({
        step: 'WALLET',
        recovery: { totalRequestBudget: 12000, totalByteBudget: 314572800, maxAttempts: 3 },
      }),
    );
  });
  it('shows cumulative recovery usage, retry countdown, and batch-level controls', async () => {
    const waiting = {
      ...captureOperation,
      step: 'MARKET' as const,
      state: 'WAITING_RETRY',
      recovery: {
        enabled: true,
        totalRequestBudget: 1200,
        totalByteBudget: 314572800,
        maxAttempts: 3,
        attemptsUsed: 1,
        requestsUsed: 140,
        bytesUsed: 12000,
        reservedRequests: 400,
        reservedBytes: 104857600,
        nextRetryAt: new Date(Date.now() + 2000).toISOString(),
      },
    };
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({ ...captureSession, operation: waiting }),
      ),
    );
    const { user } = await detail();
    expect(await screen.findByText('Recovery limits')).toBeInTheDocument();
    expect(screen.getByText('140 / 1,200')).toBeInTheDocument();
    expect(screen.getByText('1 / 3')).toBeInTheDocument();
    expect(screen.getByText(/Next retry in \d+s/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pause remaining batch' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel remaining batch' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry now' })).toBeDisabled();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry now' })).toBeEnabled(), {
      timeout: 3000,
    });
    const commands: Record<string, unknown>[] = [];
    server.use(
      http.post('/api/captures/:id/operations/:operationId/pause', async ({ request }) => {
        commands.push((await request.json()) as Record<string, unknown>);
        return commands.length === 1
          ? HttpResponse.error()
          : HttpResponse.json({ ...waiting, state: 'PAUSED' });
      }),
    );
    await user.click(screen.getByRole('button', { name: 'Pause remaining batch' }));
    await user.click(await screen.findByRole('button', { name: 'Reconcile and retry request' }));
    await waitFor(() => expect(commands).toHaveLength(2));
    expect(commands.at(1)).toEqual(commands.at(0));
    expect(commands.at(0)?.requestId).toEqual(expect.any(String));
  });
  it('exports a validated case while the main batch runs and keeps a rates-blocked case actionable only for rates', async () => {
    const readyCase: CaptureCase = {
      caseId: 'case-ready',
      wallet: WALLET,
      mint: BASE_MINT,
      anchorSignature: '1'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 2,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: { slots: 4500 },
      selected: 12,
      captured: 12,
      gaps: 0,
      marketStage: 'COMPLETE',
      coverage: 'COMPLETE',
      progress: null,
      readiness: 'READY',
      captureComplete: true,
      validated: true,
      ready: true,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: false,
      canExport: true,
      blocker: null,
      operationId: null,
      operationState: null,
      datasetId: null,
    };
    const ratesBlocked: CaptureCase = {
      ...readyCase,
      caseId: 'case-rates',
      mint: '11111111111111111111111111111117',
      readiness: 'BLOCKED',
      validated: false,
      ready: false,
      canCaptureRates: true,
      canExport: false,
      blocker: 'RATES_MISSING',
    };
    let exported: Record<string, unknown> | undefined;
    setCaptureCases(CAPTURE_ID, [readyCase, ratesBlocked]);
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          operation: { ...captureOperation, state: 'RUNNING', endedAt: null },
        }),
      ),
      http.post('/api/captures/capture-demo/cases/case-ready/export', async ({ request }) => {
        exported = (await request.json()) as Record<string, unknown>;
        setCaptureCases(CAPTURE_ID, [
          { ...readyCase, exportReady: true, canRegisterDataset: true },
          ratesBlocked,
        ]);
        return HttpResponse.json(
          {
            ...captureOperation,
            id: 'case-export',
            caseId: 'case-ready',
            step: 'EXPORT',
            state: 'SUCCEEDED',
            endedAt: new Date().toISOString(),
          },
          { status: 202 },
        );
      }),
    );
    const { user } = renderPage(<CaptureDetailPage />, {
      path: '/captures/:id',
      url: '/captures/capture-demo',
      extraRoutes: [{ path: '/datasets/:id', element: <div>Existing dataset detail</div> }],
    });
    expect(await screen.findByText(/Case case-ready/)).toBeInTheDocument();
    expect(screen.getByText('Technical code: RATES_MISSING')).toBeInTheDocument();
    const exportButton = screen.getByRole('button', { name: 'Export validated case' });
    expect(exportButton).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Validate and prepare rates' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Import case dataset' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: 'Download case dataset JSON' }),
    ).not.toBeInTheDocument();
    await user.click(exportButton);
    await waitFor(() => expect(exported?.requestId).toEqual(expect.any(String)));
    expect(exported).toHaveProperty('requestId');
    await user.click(await screen.findByRole('button', { name: 'Import case dataset' }));
    expect(await screen.findByText('Existing dataset detail')).toBeInTheDocument();
  });
  it('keeps a ready-to-validate case keyboard actionable on a narrow viewport', async () => {
    const readyToValidate: CaptureCase = {
      caseId: 'case-mobile',
      wallet: WALLET,
      mint: BASE_MINT,
      anchorSignature: '6'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 0,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: { slots: 4500 },
      selected: 8,
      captured: 8,
      gaps: 0,
      marketStage: 'READY_TO_VALIDATE',
      coverage: 'COMPLETE',
      progress: null,
      readiness: 'READY_TO_VALIDATE',
      captureComplete: true,
      validated: false,
      ready: false,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: true,
      canExport: false,
      blocker: 'MARKET_CASE_NOT_VALIDATED',
      operationId: null,
      operationState: null,
      datasetId: null,
    };
    const requests: Record<string, unknown>[] = [];
    setCaptureCases(CAPTURE_ID, [readyToValidate]);
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 360 });
    window.dispatchEvent(new Event('resize'));
    server.use(
      http.post('/api/captures/capture-demo/cases/case-mobile/rates', async ({ request }) => {
        requests.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(
          {
            ...captureOperation,
            id: 'case-rates',
            caseId: 'case-mobile',
            step: 'RATES',
            state: 'RUNNING',
            endedAt: null,
          },
          { status: 202 },
        );
      }),
    );
    const { user } = await detail();
    const card = await screen.findByRole('article', { name: 'Capture case case-mobile' });
    expect(within(card).getByText('Validation needed')).toBeInTheDocument();
    expect(within(card).queryByText('Case is blocked')).not.toBeInTheDocument();
    const action = within(card).getByRole('button', { name: 'Validate and prepare rates' });
    action.focus();
    expect(action).toHaveFocus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(requests.at(0)?.requestId).toEqual(expect.any(String)));
  });
  it('reuses the case command request id when a successful response is malformed', async () => {
    const readyCase: CaptureCase = {
      caseId: 'case-malformed',
      wallet: WALLET,
      mint: BASE_MINT,
      anchorSignature: '7'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 1,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: { slots: 4500 },
      selected: 4,
      captured: 4,
      gaps: 0,
      marketStage: 'READY',
      coverage: 'COMPLETE',
      progress: null,
      readiness: 'READY',
      captureComplete: true,
      validated: true,
      ready: true,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: false,
      canExport: true,
      blocker: null,
      operationId: null,
      operationState: null,
      datasetId: null,
    };
    const requests: Record<string, unknown>[] = [];
    setCaptureCases(CAPTURE_ID, [readyCase]);
    server.use(
      http.post('/api/captures/capture-demo/cases/case-malformed/export', async ({ request }) => {
        requests.push((await request.json()) as Record<string, unknown>);
        if (requests.length === 1)
          return new HttpResponse('accepted but not json', { status: 202 });
        return HttpResponse.json(
          {
            ...captureOperation,
            id: 'case-export',
            caseId: 'case-malformed',
            step: 'EXPORT',
            state: 'SUCCEEDED',
          },
          { status: 202 },
        );
      }),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('button', { name: 'Export validated case' }));
    await user.click(await screen.findByRole('button', { name: 'Reconcile and retry request' }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.at(1)).toEqual(requests.at(0));
  });
  it('polls while an independent case is active and stops after its terminal projection arrives', async () => {
    const runningCase: CaptureCase = {
      caseId: 'case-polling',
      wallet: WALLET,
      mint: BASE_MINT,
      anchorSignature: '8'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 1,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: { slots: 4500 },
      selected: 4,
      captured: 2,
      gaps: 0,
      marketStage: 'DETAILS_INCOMPLETE',
      coverage: 'INCOMPLETE',
      progress: null,
      readiness: 'RUNNING',
      captureComplete: false,
      validated: false,
      ready: false,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: false,
      canExport: false,
      blocker: null,
      operationId: null,
      operationState: 'RUNNING',
      datasetId: null,
    };
    const completedCase: CaptureCase = {
      ...runningCase,
      captured: 4,
      marketStage: 'READY',
      coverage: 'COMPLETE',
      readiness: 'READY',
      captureComplete: true,
      validated: true,
      ready: true,
      canExport: true,
      operationState: 'SUCCEEDED',
    };
    let polls = 0;
    server.use(
      http.get('/api/captures/capture-demo/cases', () => {
        polls += 1;
        return HttpResponse.json({ items: polls === 1 ? [runningCase] : [completedCase] });
      }),
    );
    await detail();
    expect(await screen.findByText('Case capture running')).toBeInTheDocument();
    expect(
      await screen.findByText('Validated and ready', {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    const settled = polls;
    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(polls).toBe(settled);
  });
  it('stops polling and refreshing after a separate case operation becomes terminal', async () => {
    const activeCase: CaptureCase = {
      caseId: 'case-operation-polling',
      wallet: WALLET,
      mint: BASE_MINT,
      anchorSignature: '9'.repeat(64),
      anchorSlot: 1000,
      instructionIndex: 1,
      fromSlot: 1000,
      toSlot: 5500,
      windowParameters: { slots: 4500 },
      selected: 4,
      captured: 4,
      gaps: 0,
      marketStage: 'READY',
      coverage: 'COMPLETE',
      progress: null,
      readiness: 'READY',
      captureComplete: true,
      validated: true,
      ready: true,
      exportReady: false,
      canRegisterDataset: false,
      canCaptureRates: false,
      canExport: true,
      blocker: null,
      operationId: 'case-operation',
      operationState: 'RUNNING',
      datasetId: null,
    };
    let casePolls = 0;
    let operationPolls = 0;
    let terminal = false;
    server.use(
      http.get('/api/captures/capture-demo/cases', () => {
        casePolls += 1;
        return HttpResponse.json({
          items: [{ ...activeCase, operationState: terminal ? 'SUCCEEDED' : 'RUNNING' }],
        });
      }),
      http.get('/api/captures/capture-demo/operations/case-operation', () => {
        operationPolls += 1;
        terminal = operationPolls > 1;
        return HttpResponse.json({
          ...captureOperation,
          id: 'case-operation',
          caseId: activeCase.caseId,
          step: 'EXPORT',
          state: terminal ? 'SUCCEEDED' : 'RUNNING',
          endedAt: terminal ? new Date().toISOString() : null,
        });
      }),
    );
    const { queryClient } = await detail();
    const action = await screen.findByRole('button', { name: 'Export validated case' });
    expect(action).toBeDisabled();
    await waitFor(() => expect(action).toBeEnabled(), { timeout: 5000 });
    await waitFor(() =>
      expect(queryClient.isFetching({ queryKey: ['captures', CAPTURE_ID] })).toBe(0),
    );
    const settledCasePolls = casePolls;
    const settledOperationPolls = operationPolls;
    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(casePolls).toBe(settledCasePolls);
    expect(operationPolls).toBe(settledOperationPolls);
  });
  it('keeps rates disabled for incomplete market coverage and offers fresh over-cap attempts', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          marketAttempt: {
            ...captureSession.marketAttempt!,
            market: { scanPages: 50, maxTransactions: 300 },
          },
          inspection: {
            ...readyInspection,
            market: {
              state: 'INCOMPLETE',
              reason: 'OVER_CAP',
              canStart: false,
              canResume: false,
              requiresNewAttempt: true,
            },
            rates: {
              state: 'NOT_STARTED',
              reason: 'MARKET_CAPTURE_NOT_READY',
              canStart: false,
              canResume: false,
              requiresNewAttempt: false,
            },
            canExport: false,
            canCalibrate: false,
          },
        }),
      ),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('tab', { name: /Rates/ }));
    expect(screen.getByRole('button', { name: 'Start rates capture' })).toBeDisabled();
    await user.click(screen.getByRole('tab', { name: /Markets/ }));
    expect(screen.getByText(/capacity was exceeded/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New market attempt' })).toBeEnabled();
  });
  it('retains the identical operation request after a lost response', async () => {
    const requests: unknown[] = [];
    server.use(
      http.post('/api/captures/:id/operations', async ({ request }) => {
        requests.push(await request.json());
        if (requests.length === 1) return HttpResponse.error();
        return HttpResponse.json({
          ...captureSession.operation,
          id: 'operation-new',
          requestId: (requests[0] as { requestId: string }).requestId,
          step: 'INSPECT',
          state: 'SUCCEEDED',
        });
      }),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('button', { name: 'Re-check saved files' }));
    await user.click(await screen.findByRole('button', { name: 'Reconcile and retry request' }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toEqual(requests[0]);
  });
  it('renders exact calibration values and independent dataset handoff', async () => {
    const { user } = await detail();
    await user.click(await screen.findByRole('tab', { name: /Calibration/ }));
    expect(await screen.findByText('-1234.5')).toBeInTheDocument();
    expect(screen.getByText('5000.5')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download calibration JSON' })).toHaveAttribute(
      'href',
      '/api/captures/capture-demo/downloads/calibration',
    );
    await user.click(screen.getByRole('tab', { name: /Dataset/ }));
    await user.click(screen.getByRole('button', { name: 'Import dataset' }));
    expect(await screen.findByRole('link', { name: 'New run' })).toBeInTheDocument();
  });
  it('attaches existing evidence by root-relative paths', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post('/api/captures', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(captureSession);
      }),
    );
    const { user } = renderPage(<NewCapturePage />, {
      path: '/captures',
      url: '/captures',
      extraRoutes: [{ path: '/captures/:id', element: <div>Attached session</div> }],
    });
    await user.click(await screen.findByRole('tab', { name: 'Attach existing' }));
    await user.type(screen.getByLabelText(/Session name/), 'Decu');
    await user.type(screen.getByLabelText(/Wallet capture directory/), 'decu-pumpfun-buys');
    await user.type(screen.getByLabelText(/Market capture directory/), 'decu-dip-markets');
    await user.click(screen.getByRole('button', { name: 'Attach and inspect' }));
    expect(await screen.findByText('Attached session')).toBeInTheDocument();
    expect(body).toMatchObject({
      name: 'Decu',
      attach: { wallet: 'decu-pumpfun-buys', market: 'decu-dip-markets' },
    });
    expect(body).not.toHaveProperty('wallet');
  });
  it('shows the per-run budget apart from cumulative usage and stops polling at a terminal state', async () => {
    let polls = 0;
    const running = {
      ...captureOperation,
      step: 'MARKET' as const,
      state: 'WAITING_RETRY',
      endedAt: null,
      budget: { requestBudget: 3, byteBudget: 1000, operationTimeoutMinutes: 10 },
      progress: {
        phase: 'LISTING',
        at: '2026-10-10T09:10:01Z',
        operationRequests: 3,
        operationBytes: 900,
        cumulativeRequests: 5,
        cumulativeBytes: 1500,
        processed: 4,
        selected: 2,
      },
      recovery: {
        enabled: true,
        totalRequestBudget: 10,
        totalByteBudget: 5000,
        maxAttempts: 3,
        attemptsUsed: 1,
        requestsUsed: 5,
        bytesUsed: 1500,
        reservedRequests: 3,
        reservedBytes: 1000,
        nextRetryAt: new Date(Date.now() + 60000).toISOString(),
      },
    };
    server.use(
      http.get('/api/captures/capture-demo', () => {
        polls += 1;
        return HttpResponse.json({
          ...captureSession,
          operation:
            polls === 1
              ? running
              : { ...running, state: 'SUCCEEDED', endedAt: '2026-10-10T09:10:05Z' },
        });
      }),
    );
    await detail();
    expect(await screen.findByText('Requests, this run')).toBeInTheDocument();
    expect(screen.getByText('Requests, whole capture')).toBeInTheDocument();
    expect(screen.getByText('3 / 3')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    await waitFor(() => expect(polls).toBeGreaterThanOrEqual(2), { timeout: 5000 });
    const settled = polls;
    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(polls).toBe(settled);
  });
  it('disables acquisition when the backend has no RPC configuration', async () => {
    server.use(
      http.get('/api/captures/config', () =>
        HttpResponse.json({ ...captureConfig, rpcConfigured: false }),
      ),
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          inspection: {
            ...readyInspection,
            wallet: {
              state: 'INCOMPLETE',
              reason: null,
              canStart: false,
              canResume: true,
              requiresNewAttempt: false,
            },
          },
        }),
      ),
    );
    await detail();
    expect(await screen.findByText(/Solana RPC acquisition is not configured/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resume wallet capture' })).toBeDisabled();
  });
  it('sends the chosen operation deadline with the wallet resume', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          inspection: {
            ...readyInspection,
            wallet: {
              state: 'INCOMPLETE',
              reason: 'WALLET_CAPTURE_INCOMPLETE',
              canStart: false,
              canResume: true,
              requiresNewAttempt: false,
            },
          },
        }),
      ),
      http.post('/api/captures/:id/operations', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            ...captureSession.operation,
            id: 'wallet-op',
            step: 'WALLET',
            state: 'RUNNING',
            endedAt: null,
          },
          { status: 202 },
        );
      }),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('button', { name: 'Advanced operation settings' }));
    const deadline = await screen.findByLabelText(/Operation deadline/);
    await user.clear(deadline);
    await user.type(deadline, '30');
    expect(screen.getByText(/30-minute deadline/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Resume wallet capture' }));
    await waitFor(() =>
      expect(body).toMatchObject({
        step: 'WALLET',
        budget: { requestBudget: 400, byteBudget: 104857600, operationTimeoutMinutes: 30 },
      }),
    );
  });
  it('explains a deadline stop on the latest operation', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          operation: {
            ...captureOperation,
            step: 'MARKET',
            state: 'INCOMPLETE',
            code: 'DEADLINE',
            message:
              'The operation deadline ended this run. Saved evidence is kept; resume to continue with a fresh allowance.',
          },
        }),
      ),
    );
    await detail();
    expect(await screen.findByText('Stopped early')).toBeInTheDocument();
    expect(
      screen.getAllByText(/Reached the time allowed for this run. Evidence so far is kept./).length,
    ).toBeGreaterThan(0);
    expect(screen.getAllByText('Technical code: DEADLINE').length).toBeGreaterThan(0);
  });
  it('labels each calibrated buy with its instruction', async () => {
    const { user } = await detail();
    await user.click(await screen.findByRole('tab', { name: /Calibration/ }));
    expect(await screen.findByText('buy exact SOL in (v3)')).toBeInTheDocument();
    expect(screen.getByText('BUY_EXACT_QUOTE_IN_V3 1')).toBeInTheDocument();
  });
  it('deletes a capture after confirmation and returns to the list', async () => {
    const { user } = renderPage(<CaptureDetailPage />, {
      path: '/captures/:id',
      url: '/captures/capture-demo',
      extraRoutes: [{ path: '/captures', element: <div>Capture list</div> }],
    });
    await user.click(await screen.findByRole('button', { name: 'Delete capture' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(
        /Attached capture directories are only referenced and stay untouched/,
      ),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Capture list')).toBeInTheDocument();
  });
  it('cannot delete a capture while its operation runs', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          operation: { ...captureOperation, state: 'RUNNING', endedAt: null },
        }),
      ),
    );
    await detail();
    expect(await screen.findByRole('button', { name: 'Delete capture' })).toBeDisabled();
  });
  it('points to a smaller sample when the attempt is already over cap at the maximum capacity', async () => {
    const overCap = {
      state: 'UNUSABLE',
      reason: 'OVER_CAP',
      canStart: false,
      canResume: false,
      requiresNewAttempt: true,
    };
    const atMaximum = {
      ...captureSession,
      inspection: { ...readyInspection, market: overCap, canExport: false, canCalibrate: false },
    };
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json(atMaximum)));
    const { user } = await detail();
    await user.click(await screen.findByRole('tab', { name: /Markets/ }));
    expect(
      screen.getByText(/more market transactions than the maximum capacity allows/),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Create a new market attempt with a larger capacity/),
    ).not.toBeInTheDocument();
  });
  it('still suggests a larger capacity when the attempt is below the maximum', async () => {
    const overCap = {
      state: 'UNUSABLE',
      reason: 'OVER_CAP',
      canStart: false,
      canResume: false,
      requiresNewAttempt: true,
    };
    const marketAttempt = {
      ...captureSession.marketAttempt!,
      market: { scanPages: 50, maxTransactions: 300 },
    };
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...captureSession,
          marketAttempt,
          inspection: { ...readyInspection, market: overCap },
        }),
      ),
    );
    const { user } = await detail();
    await user.click(await screen.findByRole('tab', { name: /Markets/ }));
    expect(
      screen.getByText(/Create a new market attempt with a larger capacity/),
    ).toBeInTheDocument();
  });
});

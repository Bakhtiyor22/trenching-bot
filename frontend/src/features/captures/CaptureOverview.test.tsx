import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { CAPTURE_STATUSES, type CaptureSession, type CaptureSummary } from '../../api/captures';
import { captureConfig, captureOperation, captureSession } from '../../mocks/captureFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { CaptureOverview, CaptureStepper } from './CaptureOverview';
import { nowCopy } from './captureNow';

const summary: CaptureSummary = {
  status: 'PAUSED',
  reasonCode: null,
  currentStep: { index: 1, stage: 'WALLET' },
  steps: [
    { stage: 'WALLET', state: 'STOPPED' },
    { stage: 'MARKETS', state: 'TODO' },
    { stage: 'RATES', state: 'TODO' },
    { stage: 'DATASET', state: 'TODO' },
  ],
  progress: { processed: 4, selected: 9, percent: '44' },
  nextAction: {
    kind: 'RESUME_WALLET',
    stage: 'WALLET',
    attemptId: 'wallet-1',
    caseId: null,
    maxRequests: 400,
  },
  cases: { total: 0, ready: 0, blocked: 0, downloading: 0 },
  lastProgressAt: null,
};
describe('capture next actions', () => {
  it.each(CAPTURE_STATUSES)('shows one now headline and explanation for %s', (status) => {
    const state = { ...summary, status };
    const session = { ...captureSession, summary: state };
    renderPage(<CaptureOverview session={session} config={captureConfig} />);
    const panel = screen.getByRole('region', { name: 'Now' });
    const copy = nowCopy(state, session.operation);
    expect(within(panel).getByRole('heading', { name: copy.headline })).toBeInTheDocument();
    expect(within(panel).getByText(copy.body)).toBeInTheDocument();
  });

  it('shows a single four-stage stepper with plain states and recorded details', () => {
    renderPage(
      <CaptureStepper
        session={{ ...captureSession, summary: { ...summary, status: 'STOPPED_LIMIT' } }}
      />,
    );
    const steps = screen.getByRole('region', { name: 'Four capture steps' });
    expect(within(steps).getByText('Stopped · request limit')).toBeInTheDocument();
    expect(within(steps).getByText('Waiting for markets')).toBeInTheDocument();
    expect(within(steps).getByText('42 of 42 transactions')).toBeInTheDocument();
    expect(within(steps).queryByText('4 of 9 transactions')).not.toBeInTheDocument();
    expect(within(steps).getByText('Offline check and packaging; no requests')).toBeInTheDocument();
  });
  it('shows no invented Markets counts while Wallet is still running', () => {
    renderPage(<CaptureStepper session={{ ...captureSession, inspection: null, summary }} />);
    expect(screen.getByText('Not downloaded yet')).toBeInTheDocument();
    expect(screen.queryByText('4 of 9 transactions')).not.toBeInTheDocument();
    expect(screen.queryByText('0 of 0 transactions')).not.toBeInTheDocument();
  });
  it('uses summary progress when the current step is Markets', () => {
    renderPage(
      <CaptureStepper
        session={{
          ...captureSession,
          summary: { ...summary, currentStep: { index: 2, stage: 'MARKETS' } },
        }}
      />,
    );
    expect(screen.getByText('4 of 9 transactions')).toBeInTheDocument();
  });
  it('uses the matching market operation progress without borrowing Wallet summary progress', () => {
    renderPage(
      <CaptureStepper
        session={{
          ...captureSession,
          summary,
          operation: {
            ...captureOperation,
            step: 'MARKET',
            progress: {
              phase: 'MARKET_DETAILS',
              at: captureOperation.updatedAt,
              operationRequests: 1,
              operationBytes: null,
              cumulativeRequests: null,
              cumulativeBytes: null,
              processed: 12,
              selected: 30,
            },
          },
        }}
      />,
    );
    expect(screen.getByText('12 of 30 transactions')).toBeInTheDocument();
    expect(screen.queryByText('4 of 9 transactions')).not.toBeInTheDocument();
  });
  it('uses the saved market windows after every stage is complete', () => {
    const inspection = captureSession.inspection!;
    const window = inspection.marketWindows[0];
    if (!window) throw new Error('Fixture needs a saved market window');
    renderPage(
      <CaptureStepper
        session={{
          ...captureSession,
          inspection: {
            ...inspection,
            marketWindows: [
              ...inspection.marketWindows,
              { ...window, selected: 10, detailGaps: 2, observations: 3 },
            ],
          },
          summary: {
            ...summary,
            status: 'DONE',
            currentStep: { index: 4, stage: 'DATASET' },
            progress: null,
            steps: summary.steps.map((step) => ({ ...step, state: 'DONE' })),
          },
        }}
      />,
    );
    expect(screen.getByText('50 of 52 transactions')).toBeInTheDocument();
    expect(screen.queryByText('0 of 0 transactions')).not.toBeInTheDocument();
  });
  it('resumes the existing paused operation instead of creating another operation', async () => {
    const session: CaptureSession = {
      ...captureSession,
      summary,
      operation: { ...captureOperation, step: 'WALLET', state: 'PAUSED' },
    };
    let resumed = false;
    let started = false;
    server.use(
      http.get('/api/captures/capture-demo', () => HttpResponse.json(session)),
      http.post('/api/captures/capture-demo/operations', () => {
        started = true;
        return HttpResponse.json(captureOperation);
      }),
      http.post('/api/captures/capture-demo/operations/:id/resume', () => {
        resumed = true;
        return HttpResponse.json(captureOperation);
      }),
    );
    const { user } = renderPage(<CaptureOverview session={session} config={captureConfig} />, {
      path: '/captures/:id',
      url: '/captures/capture-demo',
    });
    await user.click(screen.getByRole('button', { name: 'Resume wallet step' }));
    await waitFor(() => expect(resumed).toBe(true));
    expect(started).toBe(false);
  });
  it('exports before trying to register an unexported dataset', async () => {
    const session: CaptureSession = {
      ...captureSession,
      datasetReady: false,
      summary: {
        ...summary,
        status: 'READY_TO_REPLAY',
        nextAction: {
          ...summary.nextAction,
          kind: 'REGISTER_DATASET',
          stage: 'DATASET',
          maxRequests: null,
        },
      },
    };
    let step: string | undefined;
    let registered = false;
    server.use(
      http.get('/api/captures/capture-demo', () => HttpResponse.json(session)),
      http.post('/api/captures/capture-demo/operations', async ({ request }) => {
        step = ((await request.json()) as { step: string }).step;
        return HttpResponse.json(captureOperation);
      }),
      http.post('/api/captures/capture-demo/dataset', () => {
        registered = true;
        return HttpResponse.json({});
      }),
    );
    const { user } = renderPage(<CaptureOverview session={session} config={captureConfig} />, {
      path: '/captures/:id',
      url: '/captures/capture-demo',
    });
    await user.click(screen.getByRole('button', { name: 'Prepare dataset' }));
    await waitFor(() => expect(step).toBe('EXPORT'));
    expect(registered).toBe(false);
  });
  it('disables acquisition and names only the configuration variable when RPC is missing', () => {
    const session = { ...captureSession, summary };
    renderPage(
      <CaptureOverview session={session} config={{ ...captureConfig, rpcConfigured: false }} />,
      { path: '/captures/:id', url: '/captures/capture-demo' },
    );
    expect(screen.getByRole('button', { name: 'Resume wallet step' })).toBeDisabled();
    expect(
      screen.getByText('Configure SOLANA_RPC_URL in the backend process to start this step.'),
    ).toBeInTheDocument();
  });
});

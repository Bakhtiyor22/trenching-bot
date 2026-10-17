import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CaptureSession, CaptureSummary } from '../../api/captures';
import { captureConfig, captureOperation, captureSession } from '../../mocks/captureFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { CaptureOverview } from './CaptureOverview';

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

import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CaptureCase, CaptureSession, CaptureSummary } from '../../api/captures';
import { captureOperation, captureSession } from '../../mocks/captureFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { CaptureDetailPage } from './CaptureDetailPage';

const summary: CaptureSummary = {
  status: 'STOPPED_LIMIT',
  reasonCode: 'REQUEST_BUDGET',
  currentStep: { index: 2, stage: 'MARKETS' },
  steps: [
    { stage: 'WALLET', state: 'DONE' },
    { stage: 'MARKETS', state: 'STOPPED' },
    { stage: 'RATES', state: 'TODO' },
    { stage: 'DATASET', state: 'TODO' },
  ],
  progress: { processed: 40, selected: 100, percent: '40' },
  nextAction: {
    kind: 'RESUME_MARKETS',
    stage: 'MARKETS',
    attemptId: 'market-1',
    caseId: null,
    maxRequests: 400,
  },
  cases: { total: 1, ready: 0, downloading: 1, blocked: 0 },
  lastProgressAt: '2026-10-10T09:10:02Z',
};
const session: CaptureSession = {
  ...captureSession,
  summary,
  operation: { ...captureOperation, step: 'MARKET', state: 'STOPPED_BUDGET' },
};
const token: CaptureCase = {
  caseId: 'token-ready',
  wallet: '1'.repeat(32),
  mint: '2'.repeat(32),
  anchorSignature: '3'.repeat(64),
  anchorSlot: 1000,
  instructionIndex: 0,
  fromSlot: 1000,
  toSlot: 5500,
  windowParameters: {},
  selected: 100,
  captured: 100,
  gaps: 0,
  marketStage: 'COMPLETE',
  coverage: 'COMPLETE',
  progress: null,
  readiness: 'READY',
  captureComplete: true,
  validated: true,
  ready: true,
  exportReady: true,
  canRegisterDataset: false,
  canCaptureRates: false,
  canExport: false,
  blocker: null,
  operationId: null,
  operationState: null,
  datasetId: 'dataset-ready',
};
const renderDetail = () =>
  renderPage(<CaptureDetailPage />, { path: '/captures/:id', url: '/captures/capture-demo' });

describe('capture detail design', () => {
  beforeEach(() => {
    server.use(http.get('/api/captures/capture-demo', () => HttpResponse.json(session)));
  });

  it('has one stage layout, consolidated progress, and the terms aside', async () => {
    renderDetail();
    expect(await screen.findByRole('region', { name: 'Now' })).toBeInTheDocument();
    expect(screen.getAllByRole('region', { name: 'Four capture steps' })).toHaveLength(1);
    expect(screen.queryByRole('region', { name: 'Capture stages' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Latest operation' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Markets progress' })).toBeInTheDocument();
    expect(screen.getByText('Requests, whole capture')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: "See this capture's API usage" })).toHaveAttribute(
      'href',
      '/usage?captureId=capture-demo',
    );
    const terms = screen.getByRole('complementary', { name: 'Terms on this page' });
    expect(within(terms).getAllByRole('term')).toHaveLength(6);
    expect(within(terms).getAllByRole('definition')).toHaveLength(6);
  });

  it('shows an offline check without download counters and explains its stop in plain words', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...session,
          summary: {
            ...summary,
            status: 'NEEDS_ATTENTION',
            reasonCode: 'MANIFEST_VERSION',
            progress: null,
          },
          operation: {
            ...captureOperation,
            step: 'INSPECT',
            state: 'FAILED',
            code: 'MANIFEST_VERSION',
            message: 'Capture validation failed category=MANIFEST_VERSION',
            progress: null,
          },
        }),
      ),
    );
    renderDetail();
    expect(
      await screen.findByRole('heading', { name: 'Latest saved-file check' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Requests, this run')).not.toBeInTheDocument();
    expect(screen.queryByText(/— of — transactions/)).not.toBeInTheDocument();
    expect(
      screen.getAllByText(/older version in a format this version cannot read/).length,
    ).toBeGreaterThan(0);
    expect(screen.queryByText(/category=MANIFEST_VERSION/)).not.toBeInTheDocument();
  });

  it('explains why tokens cannot be listed yet instead of showing a retryable error', async () => {
    server.use(
      http.get('/api/captures/capture-demo/cases', () =>
        HttpResponse.json(
          {
            code: 'MANIFEST_VERSION',
            message: 'Capture cases cannot be inspected until their wallet plan is valid',
            fields: {},
          },
          { status: 409 },
        ),
      ),
    );
    renderDetail();
    expect(
      await screen.findByText(/Tokens appear once the wallet evidence can be read/),
    ).toBeInTheDocument();
    expect(screen.queryByText('Could not load market cases')).not.toBeInTheDocument();
  });

  it('keeps all six tabs and their controls inside closed Advanced', async () => {
    const { user } = renderDetail();
    const label = await screen.findByText('Advanced: limits, attempts and history');
    const advanced = label.closest('details');
    if (!advanced) throw new Error('Advanced disclosure is missing');
    expect(advanced).not.toHaveAttribute('open');
    await user.click(label);
    expect(within(advanced).getAllByRole('tab')).toHaveLength(6);
    await user.click(screen.getByRole('tab', { name: 'Markets' }));
    expect(screen.getByRole('button', { name: 'New market attempt' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Rates' }));
    expect(screen.getByRole('button', { name: 'New rates attempt' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Dataset' }));
    expect(screen.getByRole('button', { name: 'Export dataset' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Calibration' }));
    expect(screen.getByRole('button', { name: 'Calibrate execution' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'History' }));
    expect(await screen.findByRole('heading', { name: 'Attempt history' })).toBeInTheDocument();
  });

  it('opens the right stage settings and checks and focuses automatic resume', async () => {
    const { user } = renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Turn on automatic resume' }));
    const checkbox = await screen.findByRole('checkbox', {
      name: 'Enable automatic recovery for this stage',
    });
    expect(checkbox).toBeChecked();
    await waitFor(() => expect(checkbox).toHaveFocus());
    expect(screen.getByRole('tab', { name: 'Markets', selected: true })).toBeInTheDocument();
    expect(screen.getByLabelText('Total request budget')).toBeInTheDocument();
  });

  it('renames inline and retains the name conflict suggestion', async () => {
    server.use(
      http.patch('/api/captures/capture-demo', () =>
        HttpResponse.json(
          {
            code: 'NAME_TAKEN',
            message: 'Another capture already has this name.',
            fields: { name: 'Name is taken' },
            suggestion: 'Suggested name',
          },
          { status: 409 },
        ),
      ),
    );
    const { user } = renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Rename capture' }));
    expect(
      screen.queryByRole('heading', { name: session.displayName ?? session.name }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save name' }));
    await user.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(screen.getByRole('textbox', { name: /Capture name/ })).toHaveValue('Suggested name');
  });

  it('shows a ready token with facts and direct run and dataset actions', async () => {
    server.use(
      http.get('/api/captures/capture-demo/cases', () => HttpResponse.json({ items: [token] })),
    );
    renderDetail();
    const card = await screen.findByRole('article', { name: 'Capture case token-ready' });
    expect(
      within(card).getByText(/Buy at slot 1,000 · Window slots 1,000–5,500/),
    ).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Start a run' })).toHaveAttribute(
      'href',
      '/runs/new?datasetId=dataset-ready',
    );
    expect(within(card).getByRole('link', { name: 'Open dataset' })).toHaveAttribute(
      'href',
      '/datasets/dataset-ready',
    );
    expect(screen.getByRole('heading', { name: 'Tokens in this capture' })).toBeInTheDocument();
  });

  it('opens market controls from a blocked token and explains an unavailable preview', async () => {
    server.use(
      http.get('/api/captures/capture-demo/cases', () =>
        HttpResponse.json({
          items: [
            {
              ...token,
              ready: false,
              readiness: 'BLOCKED',
              datasetId: null,
              blocker: 'REQUEST_BUDGET_EXHAUSTED',
            },
          ],
        }),
      ),
    );
    const { user } = renderDetail();
    const card = await screen.findByRole('article', { name: 'Capture case token-ready' });
    expect(within(card).getByText('Technical code: REQUEST_BUDGET_EXHAUSTED')).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Chart preview' })).toBeDisabled();
    expect(
      within(card).getByText('Chart preview is available after the dataset is prepared.'),
    ).toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: 'Resume market step' }));
    expect(screen.getByRole('tab', { name: 'Markets', selected: true })).toBeInTheDocument();
    expect(
      screen.getByText('Advanced: limits, attempts and history').closest('details'),
    ).toHaveAttribute('open');
  });

  it('explains an empty capture and provides the wallet action', async () => {
    server.use(
      http.get('/api/captures/capture-demo', () =>
        HttpResponse.json({
          ...session,
          operation: null,
          summary: {
            ...summary,
            status: 'NOT_STARTED',
            currentStep: { index: 1, stage: 'WALLET' },
            nextAction: { ...summary.nextAction, stage: 'WALLET', kind: 'START_WALLET' },
          },
        }),
      ),
      http.get('/api/captures/capture-demo/cases', () => HttpResponse.json({ items: [] })),
    );
    renderDetail();
    expect(
      await screen.findByText(
        'No tokens to capture yet. Run the wallet step first. It finds the buys this capture will follow.',
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Start wallet step' })).toHaveLength(2);
  });
});

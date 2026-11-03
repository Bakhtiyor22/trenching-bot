import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CaptureSession } from '../../api/captures';
import { captureOperation, captureSession } from '../../mocks/captureFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { CaptureDetailPage } from './CaptureDetailPage';
import { CapturesPage } from './CapturesPage';

const failure = () =>
  HttpResponse.json(
    { code: 'UNAVAILABLE', message: 'Backend unreachable', fields: {} },
    { status: 503 },
  );

const renderDetail = (url = '/captures/capture-demo') =>
  renderPage(<CaptureDetailPage />, { path: '/captures/:id', url });

describe('capture pages keep their data through a failed refresh', () => {
  it('keeps the captures list on screen with a refresh notice', async () => {
    const { queryClient } = renderPage(<CapturesPage />, { path: '/captures', url: '/captures' });
    const link = await screen.findByRole('link', { name: captureSession.name });
    server.use(http.get('/api/captures', failure));
    await queryClient.refetchQueries({ queryKey: ['captures'] });
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(link).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
  });

  it('keeps the capture detail and a half-typed rename on screen', async () => {
    const { user, queryClient } = renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Rename capture' }));
    const input = screen.getByRole('textbox', { name: /Capture name/ });
    await user.clear(input);
    await user.type(input, 'Half typed');
    server.use(http.get('/api/captures/capture-demo', failure));
    await queryClient.refetchQueries({ queryKey: ['captures', 'capture-demo'], exact: true });
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Capture name/ })).toHaveValue('Half typed');
    expect(screen.getByRole('region', { name: 'Four capture steps' })).toBeInTheDocument();
    expect(screen.queryByText('Could not load capture')).not.toBeInTheDocument();
  });
});

describe('capture detail URL state and confirmations', () => {
  it('opens Advanced on the tab named in ?tab and writes a new tab to the URL', async () => {
    const { user, router } = renderDetail('/captures/capture-demo?tab=market');
    expect(
      await screen.findByRole('button', { name: /Advanced: limits, attempts and history/ }),
    ).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('tab', { name: 'Markets', selected: true })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'History' }));
    expect(router.state.location.search).toBe('?tab=history');
    expect(await screen.findByRole('heading', { name: 'Attempt history' })).toBeInTheDocument();
  });

  it('asks before cancelling the remaining batch', async () => {
    const running: CaptureSession = {
      ...captureSession,
      operation: {
        ...captureOperation,
        step: 'MARKET',
        state: 'RUNNING',
        endedAt: null,
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
          nextRetryAt: null,
        },
      },
    };
    const cancels: unknown[] = [];
    server.use(
      http.get('/api/captures/capture-demo', () => HttpResponse.json(running)),
      http.post('/api/captures/:id/operations/:operationId/cancel', async ({ request }) => {
        cancels.push(await request.json());
        return HttpResponse.json({ ...running.operation, state: 'CANCELLED' });
      }),
    );
    const { user } = renderDetail();
    expect((await screen.findAllByText('Updating live')).length).toBeGreaterThan(0);
    await user.click(await screen.findByRole('button', { name: 'Cancel remaining batch' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel the remaining batch?' });
    await user.click(within(dialog).getByRole('button', { name: 'Keep running' }));
    expect(cancels).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Cancel remaining batch' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel batch' }),
    );
    await waitFor(() => expect(cancels).toHaveLength(1));
  });
});

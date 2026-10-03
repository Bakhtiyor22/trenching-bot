import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { SAMPLE_TOTAL_PNL, dashboardFixture } from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { DashboardPage } from './DashboardPage';

describe('DashboardPage', () => {
  it('refreshes dashboard samples every five seconds without probing Solana', async () => {
    let polls = 0;
    let probes = 0;
    server.use(
      http.get('/api/dashboard', () => {
        polls += 1;
        return HttpResponse.json(dashboardFixture());
      }),
      http.post('/api/solana/check', () => {
        probes += 1;
        return HttpResponse.json({});
      }),
    );
    renderPage(<DashboardPage />);
    await screen.findByLabelText('Application health: UP');
    expect(polls).toBe(1);
    await waitFor(() => expect(polls).toBe(2), { timeout: 6500 });
    expect(probes).toBe(0);
  });

  it('shows Solana as not configured (neutral) while the other tiles are healthy', async () => {
    renderPage(<DashboardPage />);
    const app = await screen.findByLabelText('Application health: UP');
    const db = screen.getByLabelText('Database health: UP');
    const solana = await screen.findByLabelText('Solana health: Not configured');

    expect(within(app).getByText('UP')).toBeInTheDocument();
    expect(within(db).getByText('UP')).toBeInTheDocument();
    expect(within(solana).getByText('Set SOLANA_RPC_URL to enable')).toBeInTheDocument();
    // Neutral, not an error color.
    expect(within(solana).getByText('Not configured')).not.toHaveStyle({
      color: 'var(--mantine-color-red-text)',
    });
    expect(within(solana).getByText('Not configured').getAttribute('style') ?? '').not.toContain(
      'red',
    );

    expect(screen.getByText(SAMPLE_TOTAL_PNL)).toBeInTheDocument();
    expect(screen.getByText('2 in the last 24 h')).toBeInTheDocument();
  });

  it('copes with a backend that does not send the newer optional fields', async () => {
    server.use(
      http.get('/api/dashboard', () =>
        HttpResponse.json({
          generatedAt: '2026-10-03T10:00:00Z',
          health: { status: 'UP', database: null },
          runs: { total: 7, recent: [] },
          rejectionsByReason: [],
          gauges: { heapUsedBytes: null, heapMaxBytes: null, uptimeMs: 61_000 },
        }),
      ),
    );
    renderPage(<DashboardPage />);
    expect(await screen.findByText('7')).toBeInTheDocument();
    expect(screen.getByLabelText('Database health: Unknown')).toBeInTheDocument();
    expect(screen.getByText('No runs yet')).toBeInTheDocument();
    expect(screen.getByText('No rejection data')).toBeInTheDocument();
    expect(screen.queryByText(/in the last 24 h/)).not.toBeInTheDocument();
    expect(screen.getAllByText('1m 1s').length).toBeGreaterThan(0);
  });

  it('shows an error state when the dashboard cannot be loaded', async () => {
    server.use(http.get('/api/dashboard', () => new HttpResponse('Bad gateway', { status: 502 })));
    renderPage(<DashboardPage />);
    expect(await screen.findByText('Dashboard unavailable')).toBeInTheDocument();
    expect(screen.getByText(/The backend is not reachable \(HTTP 502\)/)).toBeInTheDocument();
  });
});

import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../../test/render';
import { ApiUsagePage } from './ApiUsagePage';
import { server } from '../../mocks/server';
import { captureSession } from '../../mocks/captureFixtures';
import type { UsageReport } from '../../api/setup';

describe('API usage page', () => {
  it('shows units, purposes and tasks and updates the selected period in the URL', async () => {
    const { user, router } = renderPage(<ApiUsagePage />, {
      path: '/usage',
      url: '/usage?captureId=capture-demo',
    });
    expect(
      await screen.findByText(
        'Download one full transaction: wallet buys, market trades and first-buy checks.',
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByText('Solana RPC').length).toBeGreaterThan(0);
    expect(screen.getByText('HTTP calls, all APIs, last hour')).toBeInTheDocument();
    expect(screen.getByText('response bodies, last hour')).toBeInTheDocument();
    expect(screen.getByText('HTTP 429 answers, retried automatically')).toBeInTheDocument();
    expect(screen.getByText('transactions, no request sent')).toBeInTheDocument();
    expect(
      within(screen.getByRole('table'))
        .getAllByRole('columnheader')
        .some((cell) => cell.getAttribute('scope') === 'colgroup'),
    ).toBe(true);
    expect(
      screen.getByText('your configured provider; address hidden because it can contain a key'),
    ).toBeInTheDocument();
    expect(screen.getByText('public market data, no key')).toBeInTheDocument();
    expect(screen.getAllByText('2.5 MB').length).toBeGreaterThan(0);
    expect(screen.getByText('Market step')).toBeInTheDocument();
    expect(screen.getByText(/Gradle CLI captures are not counted/)).toBeInTheDocument();
    await user.click(screen.getByText('Last 24 hours'));
    expect(router.state.location.search).toContain('period=LAST_24_HOURS');
  });

  it('offers every capture after paging beyond the first twenty', async () => {
    const pages: number[] = [];
    server.use(
      http.get('/api/captures', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        pages.push(page);
        const items = Array.from({ length: 21 }, (_, index) => ({
          ...captureSession,
          id: `choice-${index + 1}`,
          name: `Capture ${index + 1}`,
          displayName: `Capture ${index + 1}`,
        }));
        return HttpResponse.json({
          items: items.slice(page * 20, page * 20 + 20),
          page,
          size: 20,
          total: 21,
        });
      }),
    );
    const { user, router } = renderPage(<ApiUsagePage />, { path: '/usage', url: '/usage' });
    await waitFor(() => expect(pages).toEqual([0, 1]));
    await user.click(await screen.findByRole('combobox', { name: 'For' }));
    await user.click(await screen.findByRole('option', { name: 'Capture 21' }));
    expect(router.state.location.search).toContain('captureId=choice-21');
  });

  it('explains an empty period and switches to the last twenty-four hours', async () => {
    const report: UsageReport = {
      from: '2026-10-19T00:00:00Z',
      to: '2026-10-19T01:00:00Z',
      recordingStartedAt: null,
      bucketSeconds: 300,
      totals: {
        requests: 0,
        failedRequests: 0,
        rateLimitedResponses: 0,
        responseBytes: 0,
        cacheHits: 0,
      },
      byCall: [],
      byTask: [],
      series: [],
      pacing: [],
    };
    server.use(http.get('/api/usage', () => HttpResponse.json(report)));
    const { user, router } = renderPage(<ApiUsagePage />, { path: '/usage', url: '/usage' });
    expect(await screen.findByText('No calls in the last hour')).toBeInTheDocument();
    expect(screen.getAllByText(/Nothing has been recorded yet/).length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: 'Show last 24 hours' }));
    expect(router.state.location.search).toContain('period=LAST_24_HOURS');
  });
});

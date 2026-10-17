import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../../test/render';
import { server } from '../../mocks/server';
import { SAMPLE_RUN_ID, v4Chart } from '../../mocks/fixtures';
import { RunChartPage } from './RunChartPage';

describe('results chart page', () => {
  it('keeps chart requests and the URL in sync after zooming and jumping to an event', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/runs/:id/chart', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({
          ...v4Chart,
          extent: {
            firstTime: v4Chart.range.from,
            lastTime: v4Chart.range.to,
            firstSlot: 1000,
            lastSlot: 5500,
          },
        });
      }),
    );
    const { user, router } = renderPage(<RunChartPage />, {
      path: '/runs/:id/chart',
      url: `/runs/${SAMPLE_RUN_ID}/chart`,
    });
    await user.click(await screen.findByRole('button', { name: 'Whole window' }));
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    await waitFor(() =>
      expect(
        requests.some((url) => url.searchParams.has('from') && url.searchParams.has('to')),
      ).toBe(true),
    );
    expect(router.state.location.search).toContain('from=');
    expect(screen.getByLabelText('From (local time)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Reference buy ·/ }));
    expect(router.state.location.search).toContain('pair=');
  });
});

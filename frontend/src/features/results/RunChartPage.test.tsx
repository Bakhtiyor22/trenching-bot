import { fireEvent, screen, waitFor } from '@testing-library/react';
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
    expect(screen.getByLabelText('From (local time)')).toHaveAttribute('step', '1');
    expect(requests[0]?.searchParams.get('maxBuckets')).toBe('1');
    expect(requests[0]?.searchParams.get('markerLimit')).toBe('1');
    expect(screen.getByRole('button', { name: 'Previous token' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next token' })).toBeEnabled();
    expect(screen.getByRole('list')).toBeInTheDocument();
    expect(screen.getByText(/fees .* impact/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Decu bought/ }));
    expect(router.state.location.search).toContain('pair=');
  });

  it('applies From seconds on Enter, marks custom ranges, and retains seconds in the chart request', async () => {
    const requests: URL[] = [];
    const start = Date.parse(v4Chart.range.from);
    server.use(
      http.get('/api/runs/:id/chart', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({
          ...v4Chart,
          extent: {
            firstTime: new Date(start).toISOString(),
            lastTime: new Date(start + 10 * 60_000).toISOString(),
            firstSlot: 1000,
            lastSlot: 5500,
          },
          range: {
            from: new Date(start).toISOString(),
            to: new Date(start + 10 * 60_000).toISOString(),
          },
        });
      }),
    );
    const { user, router } = renderPage(<RunChartPage />, {
      path: '/runs/:id/chart',
      url: `/runs/${SAMPLE_RUN_ID}/chart`,
    });
    const from = await screen.findByLabelText('From (local time)');
    const desired = start + 17_000;
    const local = new Date(desired - new Date(desired).getTimezoneOffset() * 60_000)
      .toISOString()
      .slice(0, 19);
    fireEvent.change(from, { target: { value: local } });
    await user.click(from);
    await user.keyboard('{Enter}');
    expect(new URLSearchParams(router.state.location.search).get('from')).toBe(
      new Date(desired).toISOString(),
    );
    await waitFor(() =>
      expect(
        requests.some((url) => url.searchParams.get('from') === new Date(desired).toISOString()),
      ).toBe(true),
    );
    expect(screen.getByText('Custom')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'This trade' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await user.click(screen.getByRole('button', { name: 'Whole window' }));
    expect(screen.getByRole('button', { name: 'Whole window' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});

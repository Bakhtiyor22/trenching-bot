import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import { v4Chart, WALLET } from '../mocks/fixtures';
import { server } from '../mocks/server';
import { renderPage } from '../test/render';
import { TradeChart } from './TradeChart';

afterEach(() => localStorage.clear());
function setup(url = '/') {
  const requests: URL[] = [];
  server.use(
    http.get('/api/datasets/test/chart', ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ ...v4Chart, paperFills: [] });
    }),
  );
  return {
    ...renderPage(
      <TradeChart kind="dataset" id="test" pair="base/quote" trackedWallet={WALLET} />,
      { url },
    ),
    requests,
  };
}
describe('Market & trades redesign', () => {
  it('hides secondary controls by default and exposes provenance and layers in popovers', async () => {
    const { user } = setup();
    await screen.findByRole('button', { name: 'Layers' });
    expect(screen.queryByText(v4Chart.valuationBasis.label)).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Watchlist wallet' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Valuation information' }));
    expect(await screen.findByText(v4Chart.valuationBasis.label)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Layers' }));
    expect(await screen.findByRole('textbox', { name: 'Watchlist wallet' })).toBeInTheDocument();
  });
  it('round trips action selection, wraps the stepper and closes on Esc without dropping quote', async () => {
    const { user, router } = setup('/?quote=usdt');
    await user.click(await screen.findByRole('button', { name: 'Next Decu action' }));
    expect(await screen.findByRole('dialog', { name: 'Trade details' })).toBeInTheDocument();
    const first = v4Chart.markers.items
      .filter((item) => item.isTrackedWallet)
      .sort((a, b) => Date.parse(a.time) - Date.parse(b.time))[0]!;
    expect(router.state.location.search).toContain(
      `action=${encodeURIComponent(first.observationId)}`,
    );
    await user.keyboard('{ArrowLeft}');
    expect(router.state.location.search).toContain('action=');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('quote')).toBe('usdt');
    expect(params.has('action')).toBe(false);
  });
  it('writes duration ranges to the URL and reuses complete ticks for navigation', async () => {
    const { user, requests, router } = setup();
    await user.click(await screen.findByRole('button', { name: '15m' }));
    await waitFor(() => expect(router.state.location.search).toContain('from='));
    expect(requests).toHaveLength(1);
    expect(requests[0]?.searchParams.get('tickLimit')).toBe('5000');
    await user.click(screen.getByRole('button', { name: 'All' }));
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
  });
});

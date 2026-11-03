import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../../test/render';
import { server } from '../../mocks/server';
import type { Scenario } from '../../api/types';
import {
  HOLDING_SELL_TIME,
  NO_DIP_MINT,
  SAMPLE_RUN_ID,
  sampleRunDetail,
  SOL_MINT,
  STOP_LOSS_MINT,
  timeExitScenario,
  v4Chart,
  WALLET_SELL_RUN_ID,
  walletSoldScenario,
} from '../../mocks/fixtures';
import { withRunSetup } from '../../mocks/setupHandlers';
import { formatLocalTime } from '../../lib/format';
import { RunChartPage } from './RunChartPage';

describe('results chart page', () => {
  it('uses one URL window for zooming, date inputs and event jumps', async () => {
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
    await user.click(await screen.findByRole('button', { name: 'Fit' }));
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(router.state.location.search).toContain('from=');
    expect(screen.getByLabelText('From (local time)')).toBeInTheDocument();
    expect(screen.getByLabelText('From (local time)')).toHaveAttribute('step', '1');
    expect(requests[0]?.searchParams.get('maxBuckets')).toBe('2000');
    expect(requests[0]?.searchParams.get('tickLimit')).toBe('5000');
    expect(screen.getByRole('button', { name: 'Previous token' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next token' })).toBeEnabled();
    expect(screen.getByRole('list')).toBeInTheDocument();
    expect(screen.getByText(/fees .* impact/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Decu bought/ }));
    const jumped = new URLSearchParams(router.state.location.search);
    expect(Date.parse(jumped.get('to') ?? '') - Date.parse(jumped.get('from') ?? '')).toBe(30_000);
  });

  it('applies From seconds on Enter and retains chart state in the Back link', async () => {
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
      url: `/runs/${SAMPLE_RUN_ID}/chart?view=price&style=line&layers=decu,paper&action=missing-action`,
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
    await waitFor(() => {
      const option = JSON.parse(
        screen.getByTestId('echarts').getAttribute('data-option') ?? '{}',
      ) as { dataZoom: { startValue?: number }[] };
      expect(option.dataZoom[0]?.startValue).toBe(desired);
    });
    const back = new URL(
      screen.getByRole('link', { name: 'Back to run' }).getAttribute('href') ?? '',
      'http://localhost',
    );
    expect(back.searchParams.get('from')).toBe(new Date(desired).toISOString());
    expect(back.searchParams.get('view')).toBe('price');
    expect(back.searchParams.get('layers')).toBe('decu,paper');
    expect(back.searchParams.get('action')).toBe('missing-action');
  });
  it('labels a time exit trigger as a time exit, never as a stop loss', async () => {
    const detail = withRunSetup(sampleRunDetail);
    const [first, ...rest] = detail.scenarios as [Scenario, ...Scenario[]];
    server.use(
      http.get('/api/runs/:id', () =>
        HttpResponse.json({
          ...detail,
          scenarios: [{ ...first, position: timeExitScenario.position }, ...rest],
        }),
      ),
      http.get('/api/runs/:id/chart', () =>
        HttpResponse.json({
          ...v4Chart,
          extent: {
            firstTime: v4Chart.range.from,
            lastTime: v4Chart.range.to,
            firstSlot: 1000,
            lastSlot: 5500,
          },
        }),
      ),
    );
    renderPage(<RunChartPage />, { path: '/runs/:id/chart', url: `/runs/${SAMPLE_RUN_ID}/chart` });
    expect(await screen.findByText('Time exit started')).toBeInTheDocument();
    expect(screen.queryByText('Stop loss crossed')).not.toBeInTheDocument();
    expect(screen.getByText(/sold against the standing curve/)).toBeInTheDocument();
  });

  it("places a Decu-sold exit at his sell, not at the exit trigger's slot", async () => {
    const clock = formatLocalTime;
    const { user, router } = renderPage(<RunChartPage />, {
      path: '/runs/:id/chart',
      url: `/runs/${WALLET_SELL_RUN_ID}/chart?pair=${STOP_LOSS_MINT}/${SOL_MINT}`,
    });
    const sold = await screen.findByRole('button', { name: /Decu sold the token/ });
    expect(sold).toHaveTextContent(clock(HOLDING_SELL_TIME));
    expect(sold).toHaveTextContent('Decu sold the token: our whole position is sold.');
    // The trigger is dated at our fill (slot 2003), a minute after his sell in this fixture.
    const triggerAt = walletSoldScenario.position?.exitTrigger?.time ?? '';
    expect(Date.parse(triggerAt)).toBeGreaterThan(Date.parse(HOLDING_SELL_TIME));
    expect(sold).not.toHaveTextContent(clock(triggerAt));
    await user.click(sold);
    const jumped = new URLSearchParams(router.state.location.search);
    expect(Date.parse(jumped.get('from') ?? '') + 15_000).toBe(Date.parse(HOLDING_SELL_TIME));
  });

  it('marks a cycle Decu sold before the dip at his sell', async () => {
    renderPage(<RunChartPage />, {
      path: '/runs/:id/chart',
      url: `/runs/${WALLET_SELL_RUN_ID}/chart?pair=${NO_DIP_MINT}/${SOL_MINT}`,
    });
    expect(await screen.findByText('Decu sold before the dip')).toBeInTheDocument();
  });
});

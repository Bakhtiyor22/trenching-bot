import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RunStartRequest } from '../../api/types';
import {
  EDGE_DATASET_ID,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  sampleDataset,
  sampleRun,
} from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { NewRunPage } from './NewRunPage';
import { runPreset } from '../../mocks/setupHandlers';

function renderNewRun(url = `/runs/new?datasetId=${SAMPLE_DATASET_ID}`) {
  return renderPage(<NewRunPage />, {
    path: '/runs/new',
    url,
    extraRoutes: [{ path: '/runs/:id', element: <div>Run detail page</div> }],
  });
}

function errorIdsOf(input: HTMLElement): string[] {
  return (input.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
}

describe('NewRunPage', () => {
  it('formats changed agreed values and resets just one field', async () => {
    const { user } = renderNewRun();
    const stop = await screen.findByLabelText(/Stop loss/);
    await user.clear(stop);
    await user.type(stop, '41');
    const size = screen.getByLabelText(/Position size/);
    await user.clear(size);
    await user.type(size, '0.2');
    const cap = screen.getByLabelText(/Minimum market cap/);
    await user.clear(cap);
    await user.type(cap, '12000');
    expect(screen.getByText('Changed by you · agreed 40%')).toBeInTheDocument();
    expect(screen.getByText('Changed by you · agreed 0.1 SOL')).toBeInTheDocument();
    expect(screen.getByText('Changed by you · agreed $10,000')).toBeInTheDocument();
    expect(screen.getByText('Changed · 3 settings')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reset to 40%' }));
    expect(stop).toHaveValue('40');
    expect(size).toHaveValue('0.2');
    expect(cap).toHaveValue('12000');
    expect(screen.getByText('Changed · 2 settings')).toBeInTheDocument();
  });

  it('retries loading the agreed strategy before displaying the form', async () => {
    let requests = 0;
    server.use(
      http.get('/api/runs/preset', () => {
        requests++;
        return requests === 1
          ? HttpResponse.json(
              { code: 'UNAVAILABLE', message: 'Unavailable', fields: {} },
              { status: 503 },
            )
          : HttpResponse.json(runPreset);
      }),
    );
    const { user } = renderNewRun();
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByLabelText(/Position size/)).toHaveValue('0.1');
    expect(requests).toBe(2);
  });

  it('starts the comparison flow once and never offers an undo for the action', async () => {
    const bodies: RunStartRequest[] = [];
    server.use(
      http.post('/api/runs', async ({ request }) => {
        bodies.push((await request.json()) as RunStartRequest);
        return HttpResponse.json({
          run: { ...sampleRun, id: `comparison-${bodies.length}` },
          created: true,
        });
      }),
    );
    const { user } = renderPage(<NewRunPage />, {
      path: '/runs/new',
      url: `/runs/new?datasetId=${SAMPLE_DATASET_ID}`,
      extraRoutes: [{ path: '/compare', element: <div>Comparison results</div> }],
    });
    const action = await screen.findByRole('button', { name: 'Add comparison run' });
    await user.dblClick(action);
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
    await screen.findByText('Comparison results');
    expect(bodies).toHaveLength(2);
    expect(bodies[0]?.presetId).toBe(runPreset.id);
    expect(bodies[1]).toEqual({ datasetId: SAMPLE_DATASET_ID });
  });
  it('uses a linked dataset even when it is outside the first catalog page', async () => {
    server.use(
      http.get('/api/datasets', () =>
        HttpResponse.json({ items: [sampleDataset], page: 0, size: 100, total: 101 }),
      ),
    );
    let submitted: RunStartRequest | undefined;
    server.use(
      http.post('/api/runs', async ({ request }) => {
        submitted = (await request.json()) as RunStartRequest;
        return HttpResponse.json({ run: sampleRun, created: false });
      }),
    );
    const { user } = renderNewRun(`/runs/new?datasetId=${EDGE_DATASET_ID}`);
    expect(await screen.findByRole('combobox', { name: /Dataset/ })).toHaveValue(
      `edge-cases — 1 token, 1 trades`,
    );
    await user.click(screen.getByRole('button', { name: /Start run/ }));
    await screen.findByText('Run detail page');
    expect(submitted?.datasetId).toBe(EDGE_DATASET_ID);
  });

  it('shows an unknown linked dataset instead of choosing a different one', async () => {
    renderNewRun('/runs/new?datasetId=missing');
    expect(await screen.findByText('Dataset missing not found')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Start run/ })).not.toBeInTheDocument();
  });

  it('prefills the agreed preset and labels its gaps', async () => {
    renderNewRun();
    expect(await screen.findByLabelText(/Position size/)).toHaveValue('0.1');
    expect(screen.getByLabelText(/Fill delay/)).toHaveValue('1');
    expect(screen.getByLabelText(/Cost per transaction/)).toHaveValue('0.001');
    expect(screen.queryByLabelText(/Fee \(bps\)/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Slippage/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Dip below Decu's first buy/)).toHaveValue('20');
    expect(screen.getByLabelText(/Take profit/)).toHaveValue('55');
    expect(screen.getByLabelText(/Stop loss/)).toHaveValue('40');
    expect(screen.getByLabelText(/Minimum market cap/)).toHaveValue('10000');
    expect(screen.getByLabelText(/Observation window/)).toHaveValue('4500');
    expect(screen.getByText(/This simulator .* applies .* agreed rules exactly/)).toBeVisible();
    expect(screen.getByText(/rules need the new decu-dip v2 engine/)).toBeVisible();
  });

  it('shows a server fields.latencySlots error next to the latency input', async () => {
    server.use(
      http.post('/api/runs', () =>
        HttpResponse.json(
          {
            code: 'CONFIG_INVALID',
            message: 'Simulation configuration is invalid',
            fields: { latencySlots: 'must be an integer between 1 and 150' },
          },
          { status: 400 },
        ),
      ),
    );
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    const message = await screen.findByText('must be an integer between 1 and 150');
    const latencyInput = screen.getByLabelText(/Fill delay/);
    expect(latencyInput).toHaveAttribute('aria-invalid', 'true');
    expect(errorIdsOf(latencyInput)).toContain(message.id);
    // Other inputs are not marked.
    expect(screen.getByLabelText(/Cost per transaction/)).not.toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(
      screen
        .getAllByRole('alert')
        .some((alert) => alert.textContent?.includes('Simulation configuration is invalid')),
    ).toBe(true);
  });

  it('sends notional and transaction cost as strings and integers as numbers, without fee or slippage, then opens the run', async () => {
    let body: RunStartRequest | undefined;
    server.use(
      http.post('/api/runs', async ({ request }) => {
        body = (await request.json()) as RunStartRequest;
        return HttpResponse.json({ run: sampleRun, created: true }, { status: 201 });
      }),
    );
    const { user, router } = renderNewRun();
    const notional = await screen.findByLabelText(/Position size/);
    await user.clear(notional);
    await user.type(notional, '1.000000001');
    const cost = screen.getByLabelText(/Cost per transaction/);
    await user.clear(cost);
    await user.type(cost, '0');
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    await screen.findByText('Run detail page');
    expect(router.state.location.pathname).toBe(`/runs/${SAMPLE_RUN_ID}`);
    expect(body?.datasetId).toBe(SAMPLE_DATASET_ID);
    expect(body?.config?.notional).toBe('1.000000001');
    expect(body?.config?.transactionCostSol).toBe('0');
    expect(body?.config?.latencySlots).toBe(1);
    expect(body?.config).not.toHaveProperty('feeBps');
    expect(body?.config).not.toHaveProperty('slippageBps');
  });

  it('validates on the client before calling the server', async () => {
    let called = false;
    server.use(
      http.post('/api/runs', () => {
        called = true;
        return HttpResponse.json({ run: sampleRun, created: true }, { status: 201 });
      }),
    );
    const { user } = renderNewRun();
    const latency = await screen.findByLabelText(/Fill delay/);
    await user.clear(latency);
    await user.type(latency, '151');
    const notional = screen.getByLabelText(/Position size/);
    await user.clear(notional);
    await user.type(notional, '0.0000000005');
    const cost = screen.getByLabelText(/Cost per transaction/);
    await user.clear(cost);
    await user.type(cost, '0.2');
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    expect(await screen.findByText('Must be between 1 and 150')).toBeInTheDocument();
    expect(screen.getByText('At most 9 decimals')).toBeInTheDocument();
    expect(screen.getByText('Must be between 0 and 0.1')).toBeInTheDocument();
    await waitFor(() => expect(called).toBe(false));
  });

  it('resets to the defaults', async () => {
    const { user } = renderNewRun();
    const latency = await screen.findByLabelText(/Fill delay/);
    await user.clear(latency);
    await user.type(latency, '7');
    const cost = screen.getByLabelText(/Cost per transaction/);
    await user.clear(cost);
    await user.type(cost, '0.005');
    await user.click(screen.getByRole('button', { name: /Reset to agreed/ }));
    expect(screen.getByLabelText(/Fill delay/)).toHaveValue('1');
    expect(screen.getByLabelText(/Cost per transaction/)).toHaveValue('0.001');
  });

  it('rejects a three-decimal percent before making a run request', async () => {
    let calls = 0;
    server.use(
      http.post('/api/runs', () => {
        calls++;
        return HttpResponse.json({ run: sampleRun, created: true });
      }),
    );
    const { user } = renderNewRun();
    const dip = await screen.findByLabelText(/Dip below Decu's first buy/);
    await user.clear(dip);
    await user.type(dip, '20.255');
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    expect(await screen.findByText('Use a percent with at most 2 decimals')).toBeInTheDocument();
    expect(calls).toBe(0);
  });

  it('uses clearly labelled engine defaults if the preset cannot be loaded', async () => {
    server.use(
      http.get('/api/runs/preset', () =>
        HttpResponse.json(
          { code: 'UNAVAILABLE', message: 'Preset unavailable', fields: {} },
          { status: 503 },
        ),
      ),
    );
    const { user } = renderNewRun();
    expect(await screen.findByText('Agreed strategy could not be loaded')).toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(screen.getByText('Technical code: STRATEGY_PRESET_UNAVAILABLE')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue with defaults' }));
    expect(screen.getByLabelText(/Dip below Decu's first buy/)).toHaveValue('15');
    expect(screen.getAllByText('Engine default · not the agreed strategy').length).toBeGreaterThan(
      0,
    );
  });

  it('submits percent strings and the preset id without their basis point twins', async () => {
    let body: RunStartRequest | undefined;
    server.use(
      http.post('/api/runs', async ({ request }) => {
        body = (await request.json()) as RunStartRequest;
        return HttpResponse.json({ run: sampleRun, created: true });
      }),
    );
    const { user } = renderNewRun();
    await screen.findByLabelText(/Dip below Decu's first buy/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await screen.findByText('Run detail page');
    expect(body).toMatchObject({
      presetId: 'decu-dip-v2-on-dip-entry-v1',
      config: { dipPercent: '20', takeProfitPercent: '55', stopLossPercent: '40' },
    });
    expect(body?.config).not.toHaveProperty('dipBps');
  });
});

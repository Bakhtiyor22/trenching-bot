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
      'edge-cases · b3f1…8f90',
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
    expect(await screen.findByLabelText(/Notional/)).toHaveValue('0.1');
    expect(screen.getByLabelText(/Latency \(slots\)/)).toHaveValue('1');
    expect(screen.getByLabelText(/Transaction cost \(SOL\)/)).toHaveValue('0.001000000000');
    expect(screen.queryByLabelText(/Fee \(bps\)/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Slippage/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Dip below first buy/)).toHaveValue('20');
    expect(screen.getByLabelText(/Take profit/)).toHaveValue('55');
    expect(screen.getByLabelText(/Stop loss/)).toHaveValue('40');
    expect(screen.getByLabelText(/Minimum market cap/)).toHaveValue('10000.000000000000');
    expect(screen.getByLabelText(/Horizon/)).toHaveValue('4500');
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
    await screen.findByLabelText(/Latency \(slots\)/);
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    const message = await screen.findByText('must be an integer between 1 and 150');
    const latencyInput = screen.getByLabelText(/Latency \(slots\)/);
    expect(latencyInput).toHaveAttribute('aria-invalid', 'true');
    expect(errorIdsOf(latencyInput)).toContain(message.id);
    // Other inputs are not marked.
    expect(screen.getByLabelText(/Transaction cost \(SOL\)/)).not.toHaveAttribute(
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
    const notional = await screen.findByLabelText(/Notional/);
    await user.clear(notional);
    await user.type(notional, '1.000000001');
    const cost = screen.getByLabelText(/Transaction cost \(SOL\)/);
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
    const latency = await screen.findByLabelText(/Latency \(slots\)/);
    await user.clear(latency);
    await user.type(latency, '151');
    const notional = screen.getByLabelText(/Notional/);
    await user.clear(notional);
    await user.type(notional, '0.0000000005');
    const cost = screen.getByLabelText(/Transaction cost \(SOL\)/);
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
    const latency = await screen.findByLabelText(/Latency \(slots\)/);
    await user.clear(latency);
    await user.type(latency, '7');
    const cost = screen.getByLabelText(/Transaction cost \(SOL\)/);
    await user.clear(cost);
    await user.type(cost, '0.005');
    await user.click(screen.getByRole('button', { name: /Reset to agreed/ }));
    expect(screen.getByLabelText(/Latency \(slots\)/)).toHaveValue('1');
    expect(screen.getByLabelText(/Transaction cost \(SOL\)/)).toHaveValue('0.001000000000');
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
    const dip = await screen.findByLabelText(/Dip below first buy/);
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
    renderNewRun();
    expect(await screen.findByText('Agreed preset unavailable')).toBeInTheDocument();
    expect(screen.getByLabelText(/Dip below first buy/)).toHaveValue('15');
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
    await screen.findByLabelText(/Dip below first buy/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await screen.findByText('Run detail page');
    expect(body).toMatchObject({
      presetId: 'decu-dip-v2-on-dip-entry-v1',
      config: { dipPercent: '20', takeProfitPercent: '55', stopLossPercent: '40' },
    });
    expect(body?.config).not.toHaveProperty('dipBps');
  });
});

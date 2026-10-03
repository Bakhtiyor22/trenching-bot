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

  it('prefills the MVP defaults', async () => {
    renderNewRun();
    expect(await screen.findByLabelText(/Notional/)).toHaveValue('100');
    expect(screen.getByLabelText(/Fee \(bps\)/)).toHaveValue('10');
    expect(screen.getByLabelText(/Slippage \(bps\)/)).toHaveValue('50');
    expect(screen.getByLabelText(/Holding period/)).toHaveValue('5');
  });

  it('shows a server fields.feeBps error next to the fee input', async () => {
    server.use(
      http.post('/api/runs', () =>
        HttpResponse.json(
          {
            code: 'CONFIG_INVALID',
            message: 'Simulation configuration is invalid',
            fields: { feeBps: 'must be an integer between 0 and 9999' },
          },
          { status: 400 },
        ),
      ),
    );
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fee \(bps\)/);
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    const message = await screen.findByText('must be an integer between 0 and 9999');
    const feeInput = screen.getByLabelText(/Fee \(bps\)/);
    expect(feeInput).toHaveAttribute('aria-invalid', 'true');
    expect(errorIdsOf(feeInput)).toContain(message.id);
    // Other inputs are not marked.
    expect(screen.getByLabelText(/Slippage \(bps\)/)).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('Simulation configuration is invalid');
  });

  it('sends notional as a string and integers as numbers, then opens the run', async () => {
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
    await user.type(notional, '1.0000000000001');
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    await screen.findByText('Run detail page');
    expect(router.state.location.pathname).toBe(`/runs/${SAMPLE_RUN_ID}`);
    expect(body?.datasetId).toBe(SAMPLE_DATASET_ID);
    expect(body?.config?.notional).toBe('1.0000000000001');
    expect(typeof body?.config?.notional).toBe('string');
    expect(body?.config?.feeBps).toBe(10);
    expect(typeof body?.config?.feeBps).toBe('number');
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
    const fee = await screen.findByLabelText(/Fee \(bps\)/);
    await user.clear(fee);
    await user.type(fee, '10000');
    const notional = screen.getByLabelText(/Notional/);
    await user.clear(notional);
    await user.type(notional, '0.0000000000005');
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    expect(await screen.findByText('Must be between 0 and 9999')).toBeInTheDocument();
    expect(
      screen.getByText('Must be greater than zero after rounding to 12 decimals'),
    ).toBeInTheDocument();
    await waitFor(() => expect(called).toBe(false));
  });

  it('resets to the MVP defaults', async () => {
    const { user } = renderNewRun();
    const fee = await screen.findByLabelText(/Fee \(bps\)/);
    await user.clear(fee);
    await user.type(fee, '77');
    await user.click(screen.getByRole('button', { name: /Reset to MVP defaults/ }));
    expect(screen.getByLabelText(/Fee \(bps\)/)).toHaveValue('10');
  });
});

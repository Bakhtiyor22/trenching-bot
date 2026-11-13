import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RunStartRequest } from '../../api/types';
import {
  EDGE_DATASET_ID,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  sampleDataset,
  sampleRun,
  v4Dataset,
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

/** Records each run start body, then lets the default mock answer it the way the backend would. */
function recordRunStarts(): RunStartRequest[] {
  const bodies: RunStartRequest[] = [];
  server.use(
    http.post('/api/runs', async ({ request }) => {
      bodies.push((await request.clone().json()) as RunStartRequest);
    }),
  );
  return bodies;
}

/** Refuses every run start with a 400 that names [fields] by their API property, as `ConsoleExceptionHandler` does. */
function refuseRunStarts(code: string, message: string, fields: Record<string, string>) {
  server.use(
    http.post('/api/runs', () => HttpResponse.json({ code, message, fields }, { status: 400 })),
  );
}

/** Asserts that [input] is marked invalid and described by the message [text]. */
function expectFieldError(input: HTMLElement, text: string) {
  expect(input).toHaveAttribute('aria-invalid', 'true');
  expect(errorIdsOf(input)).toContain(screen.getByText(text).id);
}

const BOTH_RULES_TITLE = "This dataset cannot show Decu's sells or size his buy";
const SELLS_CHECKBOX = "Follow Decu's sells (no entry, and exit if holding)";
const NOT_STARTED = 'The run was not started';
const CONFIG_INVALID_MESSAGE = 'Simulation configuration is invalid';

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

  it('keeps the form and its edits when a refresh of the datasets fails', async () => {
    const { user, queryClient } = renderNewRun();
    const stop = await screen.findByLabelText(/Stop loss/);
    await user.clear(stop);
    await user.type(stop, '41');
    server.use(
      http.get('/api/datasets', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Datasets unavailable', fields: {} },
          { status: 500 },
        ),
      ),
    );
    await queryClient.refetchQueries({ queryKey: ['datasets'] });
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Stop loss/)).toHaveValue('41');
    expect(screen.getByRole('button', { name: 'Start run' })).toBeInTheDocument();
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
    expect(
      await screen.findByText('Replay dataset not found datasetId=missing'),
    ).toBeInTheDocument();
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

  it('puts the backend clock errors, sent in milliseconds, on the seconds inputs in seconds', async () => {
    refuseRunStarts('CONFIG_INVALID', CONFIG_INVALID_MESSAGE, {
      dipWindowMs: 'must be an integer between 1000 and 20000',
      maxHoldMs: 'must be an integer between 1000 and 120000',
    });
    const { user } = renderNewRun();
    await screen.findByLabelText(/Dip window/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    expect(
      within(refusal).getByText('Simulation configuration is invalid. See the highlighted fields.'),
    ).toBeInTheDocument();
    expectFieldError(screen.getByLabelText(/Dip window/), 'Must be between 1 and 20 s');
    expectFieldError(screen.getByLabelText(/Maximum hold/), 'Must be between 1 and 120 s');
    expect(screen.getByLabelText(/Hard exit start/)).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText(/20000|120000/)).not.toBeInTheDocument();
  });

  it('points at the highlighted field only while it still is', async () => {
    refuseRunStarts('CONFIG_INVALID', CONFIG_INVALID_MESSAGE, {
      hardExitStartMs: 'must be set together with maxHoldMs',
    });
    const { user } = renderNewRun();
    const hard = await screen.findByLabelText(/Hard exit start/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    expect(
      within(refusal).getByText('Simulation configuration is invalid. See the highlighted field.'),
    ).toBeInTheDocument();
    expectFieldError(hard, 'Set together with the maximum hold');
    // Editing the field clears its highlight, so the refusal stops pointing at it.
    await user.type(hard, '0');
    expect(hard).not.toHaveAttribute('aria-invalid', 'true');
    expect(within(refusal).getByText(CONFIG_INVALID_MESSAGE)).toBeInTheDocument();
  });

  it('says why an unknown preset was refused without pointing at fields, as no input shows it', async () => {
    const message = 'Invalid request field=presetId reason=is not a known preset';
    refuseRunStarts('INVALID_REQUEST', message, { presetId: 'is not a known preset' });
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    // The message already gives the reason, so it is said once, and nothing claims a highlight.
    expect(within(refusal).getByText(message)).toBeInTheDocument();
    expect(refusal).not.toHaveTextContent(/highlighted/);
    expect(within(refusal).getByText('Technical code: INVALID_REQUEST')).toBeInTheDocument();
    expect(document.querySelectorAll('[aria-invalid="true"]')).toHaveLength(0);
  });

  it('highlights the fields it can and says the reason of any other in the refusal', async () => {
    refuseRunStarts('CONFIG_INVALID', CONFIG_INVALID_MESSAGE, {
      latencySlots: 'must be an integer between 1 and 150',
      maxHoldMs: 'must be an integer between 1000 and 120000',
      presetId: 'is not a known preset',
    });
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    expect(
      within(refusal).getByText(
        'Simulation configuration is invalid. The agreed strategy is not a known preset. See the highlighted fields.',
      ),
    ).toBeInTheDocument();
    expectFieldError(screen.getByLabelText(/Fill delay/), 'must be an integer between 1 and 150');
    expectFieldError(screen.getByLabelText(/Maximum hold/), 'Must be between 1 and 120 s');
    expect(document.querySelectorAll('[aria-invalid="true"]')).toHaveLength(2);
  });

  it('lets a long id in the refusal wrap inside the alert instead of being clipped', async () => {
    const message = `Replay dataset not found datasetId=${SAMPLE_DATASET_ID}`;
    server.use(
      http.post('/api/runs', () =>
        HttpResponse.json({ code: 'DATASET_NOT_FOUND', message, fields: {} }, { status: 404 }),
      ),
    );
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    expect(within(refusal).getByText(message)).toHaveStyle({ overflowWrap: 'anywhere' });
  });

  it('highlights the fields a refused comparison run names, as a refused start does', async () => {
    refuseRunStarts('CONFIG_INVALID', CONFIG_INVALID_MESSAGE, {
      dipWindowMs: 'must be an integer between 1000 and 20000',
    });
    const { user } = renderNewRun();
    await user.click(await screen.findByRole('button', { name: 'Add comparison run' }));

    const refusal = await screen.findByRole('alert', { name: NOT_STARTED });
    expect(
      within(refusal).getByText('Simulation configuration is invalid. See the highlighted field.'),
    ).toBeInTheDocument();
    expectFieldError(screen.getByLabelText(/Dip window/), 'Must be between 1 and 20 s');
  });

  it('explains a dataset cut short by the old cutoff and offers the old defaults instead', async () => {
    const bodies: RunStartRequest[] = [];
    server.use(
      http.post('/api/runs', async ({ request }) => {
        const body = (await request.json()) as RunStartRequest;
        bodies.push(body);
        return body.config
          ? HttpResponse.json(
              {
                code: 'MARKET_COVERAGE_ENDED',
                message:
                  'Market coverage ends before the outcome is decided mint=x decidedAt=2 throughSlot=1',
                fields: {},
              },
              { status: 409 },
            )
          : HttpResponse.json({ run: sampleRun, created: true }, { status: 201 });
      }),
    );
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: /Start run/ }));

    expect(
      await screen.findByText('This dataset ends before these settings can finish'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/decidedAt=/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run the old defaults instead' }));

    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(bodies.at(-1)).toEqual({ datasetId: SAMPLE_DATASET_ID });
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
  it('prefills the preset clocks in seconds, counts a changed clock and sends cleared clocks as null', async () => {
    let body: RunStartRequest | undefined;
    server.use(
      http.post('/api/runs', async ({ request }) => {
        body = (await request.json()) as RunStartRequest;
        return HttpResponse.json({ run: sampleRun, created: true });
      }),
    );
    const { user } = renderNewRun();
    const window = await screen.findByLabelText(/Dip window/);
    const hold = screen.getByLabelText(/Maximum hold/);
    const hard = screen.getByLabelText(/Hard exit start/);
    expect([window, hold, hard].map((input) => (input as HTMLInputElement).value)).toEqual([
      '30',
      '180',
      '240',
    ]);
    expect(screen.getByText(/4 rules need the new decu-dip v2 engine/)).toHaveTextContent(
      'Exit retries, slippage steps and fee escalation',
    );
    await user.clear(window);
    expect(screen.getByText('Changed by you · agreed 30 s')).toBeInTheDocument();
    await user.clear(hold);
    await user.clear(hard);
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await screen.findByText('Run detail page');
    expect(body?.config).toMatchObject({
      dipWindowMs: null,
      maxHoldMs: null,
      hardExitStartMs: null,
    });
  });

  it('prefills the post-trade measure and the sell rule from the preset and counts a change to either', async () => {
    const { user } = renderNewRun();
    const measure = await screen.findByRole('combobox', { name: 'Price measure' });
    expect(measure).toHaveValue('Price after each trade (spec)');
    const sells = screen.getByRole('checkbox', {
      name: "Follow Decu's sells (no entry, and exit if holding)",
    });
    expect(sells).toBeChecked();
    expect(screen.getByText('Applied')).toBeInTheDocument();
    await user.click(sells);
    expect(screen.getByText('Changed by you · agreed On')).toBeInTheDocument();
    expect(screen.getByText('Changed · 1 settings')).toBeInTheDocument();
    await user.click(measure);
    await user.click(await screen.findByRole('option', { name: "Each trade's average price" }));
    expect(screen.getByText('Changed by you · agreed Post-trade price')).toBeInTheDocument();
    expect(screen.getByText('Changed · 2 settings')).toBeInTheDocument();
  });

  it('says 4 unsupported rules, without post-trade prices or the signal size (engine dip-paper-v6)', async () => {
    renderNewRun();
    const rules = await screen.findByText(/rules need the new decu-dip v2 engine/);
    expect(rules).toHaveTextContent(/^4 rules/);
    expect(rules).not.toHaveTextContent(/post-trade/i);
    expect(rules).not.toHaveTextContent(/1\.9 SOL/);
    expect(screen.getByText(/applies 13 of the agreed rules exactly/)).toBeInTheDocument();
  });

  it('prefills the agreed 1.9 SOL minimum signal size and sends it', async () => {
    const bodies: RunStartRequest[] = [];
    server.use(
      http.post('/api/runs', async ({ request }) => {
        bodies.push((await request.json()) as RunStartRequest);
        return HttpResponse.json({ run: sampleRun, created: true });
      }),
    );
    const { user } = renderNewRun();
    const minimum = await screen.findByLabelText(/Minimum signal size/);
    expect(minimum).toHaveValue('1.9');
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await screen.findByText('Run detail page');
    expect(bodies[0]?.config?.minSignalSol).toBe('1.9');
  });

  it('flags a minimum signal size finer than a lamport', async () => {
    const { user } = renderNewRun();
    const minimum = await screen.findByLabelText(/Minimum signal size/);
    await user.clear(minimum);
    await user.type(minimum, '1.0000000001');
    await user.tab();
    expect(await screen.findByText('At most 9 decimals')).toBeInTheDocument();
    expect(
      screen.getByText('Changed by you · agreed More than 1.9 SOL, fees included'),
    ).toBeInTheDocument();
  });

  // The schema 3 sample with the agreed strategy: both its sell rule and its minimum signal size need trader data the
  // sample does not record. These tests use the default mock, which refuses them as the backend does.
  it('flags both rules the schema 3 sample cannot run and starts it after one action that turns them off', async () => {
    const bodies = recordRunStarts();
    const { user } = renderNewRun();
    const notice = await screen.findByRole('status', { name: BOTH_RULES_TITLE });
    expect(notice).toHaveTextContent(
      'Its market recording is schema 3, which does not record who traded, so Decu\'s sells cannot be followed and the size of his buy cannot be checked. Turn off "Follow Decu\'s sells" and clear "Minimum signal size" to run it, or choose a dataset of schema 4 or later.',
    );
    expect(
      screen.getAllByText("Can't run on this dataset: schema 3 does not record who traded."),
    ).toHaveLength(2);

    await user.click(within(notice).getByRole('button', { name: 'Turn off both settings' }));
    expect(screen.getByRole('checkbox', { name: SELLS_CHECKBOX })).not.toBeChecked();
    expect(screen.getByLabelText(/Minimum signal size/)).toHaveValue('');
    expect(screen.getByText('Changed by you · agreed On')).toBeInTheDocument();
    expect(
      screen.getByText('Changed by you · agreed More than 1.9 SOL, fees included'),
    ).toBeInTheDocument();
    expect(screen.getByText('Changed · 2 settings')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: BOTH_RULES_TITLE })).not.toBeInTheDocument();
    expect(screen.queryByText(/Can't run on this dataset/)).not.toBeInTheDocument();
    // The action's notice is gone, so focus waits on the next step.
    expect(screen.getByRole('button', { name: 'Start run' })).toHaveFocus();

    await user.keyboard('{Enter}');
    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toMatchObject({
      presetId: runPreset.id,
      config: { followWalletSells: false, minSignalSol: null, priceMeasure: 'POST_TRADE' },
    });
  });

  it('starts the schema 3 sample after one action when the agreed rules are refused, never refusing twice', async () => {
    const bodies = recordRunStarts();
    const { user } = renderNewRun();
    await screen.findByLabelText(/Fill delay/);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    // The backend names only the sell rule, but the one action turns off both.
    const refusal = await screen.findByRole('alert', { name: BOTH_RULES_TITLE });
    expect(
      within(refusal).getByText('Technical code: SELL_CHECK_NEEDS_TRADES'),
    ).toBeInTheDocument();
    expect(refusal).not.toHaveTextContent(/minimumSchemaVersion/);
    await user.click(within(refusal).getByRole('button', { name: 'Run without both settings' }));

    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(bodies).toHaveLength(2);
    const [refused, rerun] = bodies;
    expect(refused?.config).toMatchObject({ followWalletSells: true, minSignalSol: '1.9' });
    expect(rerun).toEqual({
      ...refused,
      config: { ...refused?.config, followWalletSells: false, minSignalSol: null },
    });
  });

  it('refuses the minimum signal size on its own once the sell rule is off, and reruns without it', async () => {
    const bodies = recordRunStarts();
    const { user } = renderNewRun();
    await user.click(await screen.findByRole('checkbox', { name: SELLS_CHECKBOX }));
    const notice = screen.getByRole('status', { name: "This dataset cannot size Decu's buy" });
    expect(
      within(notice).getByRole('button', { name: 'Clear the minimum signal size' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Start run' }));
    const refusal = await screen.findByRole('alert', {
      name: "This dataset cannot size Decu's buy",
    });
    expect(
      within(refusal).getByText('Technical code: SIGNAL_SIZE_NEEDS_TRADES'),
    ).toBeInTheDocument();
    await user.click(
      within(refusal).getByRole('button', { name: 'Run without a minimum signal size' }),
    );

    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(
      bodies.map((body) => [body.config?.followWalletSells, body.config?.minSignalSol]),
    ).toEqual([
      [false, '1.9'],
      [false, null],
    ]);
  });

  it('refuses the sell rule on its own once the minimum is cleared, and reruns without it', async () => {
    const bodies = recordRunStarts();
    const { user } = renderNewRun();
    await user.clear(await screen.findByLabelText(/Minimum signal size/));
    const notice = screen.getByRole('status', { name: "This dataset cannot show Decu's sells" });
    expect(
      within(notice).getByRole('button', { name: "Turn off Follow Decu's sells" }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Start run' }));
    const refusal = await screen.findByRole('alert', {
      name: "This dataset cannot show Decu's sells",
    });
    await user.click(within(refusal).getByRole('button', { name: 'Run without following sells' }));

    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(
      bodies.map((body) => [body.config?.followWalletSells, body.config?.minSignalSol]),
    ).toEqual([
      [true, null],
      [false, null],
    ]);
  });

  it('flags the rules only while the chosen dataset cannot run them, and never changes them itself', async () => {
    server.use(
      http.get('/api/datasets', () =>
        HttpResponse.json({ items: [sampleDataset, v4Dataset], page: 0, size: 100, total: 2 }),
      ),
    );
    const bodies = recordRunStarts();
    const { user } = renderNewRun();
    expect(await screen.findByRole('status', { name: BOTH_RULES_TITLE })).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: /Dataset/ }));
    await user.click(await screen.findByRole('option', { name: /chart-capture-v4/ }));
    await waitFor(() =>
      expect(screen.queryByRole('status', { name: BOTH_RULES_TITLE })).not.toBeInTheDocument(),
    );
    expect(screen.queryByText(/Can't run on this dataset/)).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: SELLS_CHECKBOX })).toBeChecked();
    expect(screen.getByLabelText(/Minimum signal size/)).toHaveValue('1.9');
    expect(screen.getByText('Applied')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Start run' }));
    expect(await screen.findByText('Run detail page')).toBeInTheDocument();
    expect(bodies[0]).toMatchObject({
      datasetId: v4Dataset.id,
      config: { followWalletSells: true, minSignalSol: '1.9' },
    });
  });
});

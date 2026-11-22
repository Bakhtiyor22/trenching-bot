import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { VariationEditRequest } from '../../api/automation';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { VariationDetailPage } from './VariationDetailPage';

/** Inside the fixtures' today: Mon 2 Nov 2026, 22:00 UTC+5. */
const NOW = new Date('2026-11-02T17:00:00Z');
const EDIT_URL = '/api/automation/variations/V04/edit';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  sessionStorage.clear();
});
afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
});

function renderEdit(label = 'V04') {
  return renderPage(<VariationDetailPage />, {
    path: '/automation/variations/:label',
    url: `/automation/variations/${label}?edit=1`,
  });
}

function editBodies(): VariationEditRequest[] {
  const bodies: VariationEditRequest[] = [];
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && request.url.endsWith(EDIT_URL))
      bodies.push((await request.clone().json()) as VariationEditRequest);
  });
  return bodies;
}

const input = (name: RegExp) => screen.getByRole('textbox', { name });

describe('VariationEditForm', () => {
  it('is prefilled from the variation and lists each change before saving', async () => {
    const { user } = renderEdit();
    const form = await screen.findByRole('form', { name: 'Edit variation V04' });
    expect(input(/^Take profit/)).toHaveValue('80');
    expect(input(/^Stop loss/)).toHaveValue('40');
    expect(input(/^Minimum signal size/)).toHaveValue('1.9');
    expect(within(form).getByText(/No setting changed yet/)).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Archive V04 and save' })).toBeDisabled();

    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '50');
    const changes = within(form).getByRole('list', { name: 'Changes' });
    expect(within(changes).getAllByRole('listitem')).toHaveLength(1);
    expect(within(changes).getByText('Stop loss')).toBeInTheDocument();
    expect(within(changes).getByText('40% → 50%')).toBeInTheDocument();
    // What saving does: archive, a replacement from the next captured buy, existing runs untouched.
    expect(within(form).getByText(/V04 is archived: it is no longer replayed/)).toBeInTheDocument();
    expect(within(form).getByText(/V11 unless another edit is saved first/)).toBeInTheDocument();
    expect(within(form).getByText(/its existing runs stay where they are/)).toBeInTheDocument();
  });

  it('posts the complete configuration and opens the new variation, which runs only from now on', async () => {
    const bodies = editBodies();
    const { user, router } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '50');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/automation/variations/V11'));
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.config).toMatchObject({
      takeProfitPercent: '80',
      stopLossPercent: '50',
      dipPercent: '20',
      minSignalSol: '1.9',
      dipWindowMs: 30000,
      maxHoldMs: 180000,
      hardExitStartMs: 240000,
      priceMeasure: 'POST_TRADE',
      followWalletSells: true,
    });
    expect(Object.keys(bodies[0]!.config)).toHaveLength(16);
    await waitFor(() =>
      expect(screen.getByRole('table', { name: 'Settings of V11' })).toBeInTheDocument(),
    );
    const notice = screen
      .getAllByRole('status')
      .find((element) => element.textContent?.includes('V11 created'));
    expect(notice).toHaveTextContent(/runs only on buys captured from .* on, that is from now on/);
  });

  it('puts the server field errors on their inputs, a clock in seconds', async () => {
    server.use(
      http.post(EDIT_URL, () =>
        HttpResponse.json(
          {
            code: 'CONFIG_INVALID',
            message: 'Simulation configuration is invalid',
            fields: {
              stopLossPercent: 'must be between 0.01 and 99.99 percent',
              maxHoldMs: 'must be an integer between 1000 and 3600000',
            },
          },
          { status: 400 },
        ),
      ),
    );
    const { user } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '50');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    expect(await screen.findByText('must be between 0.01 and 99.99 percent')).toBeInTheDocument();
    expect(input(/^Stop loss/)).toHaveAttribute('aria-invalid', 'true');
    expect(input(/^Maximum hold/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Must be between 1 and 3600 s')).toBeInTheDocument();
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('The backend refused some settings. Nothing was saved.');
    expect(alert).toHaveTextContent('See the highlighted fields.');
  });

  it('reuses one request id when the same submit is retried, and a new one for a new edit', async () => {
    let failures = 1;
    server.use(
      http.post(EDIT_URL, () => {
        if (failures-- > 0)
          return HttpResponse.json(
            { code: 'UNAVAILABLE', message: 'Backend unreachable', fields: {} },
            { status: 503 },
          );
        return undefined;
      }),
    );
    const bodies = editBodies();
    const { user, router } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '50');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    expect(await screen.findByRole('button', { name: 'Save again' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save again' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/automation/variations/V11'));
    expect(bodies).toHaveLength(2);
    expect(bodies[0]!.requestId).toMatch(/^ui-edit-/);
    expect(bodies[1]!.requestId).toBe(bodies[0]!.requestId);

    // V04 is archived now; a new edit of another variation starts its own request.
    const next = editBodies();
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.includes('/variations/V05/edit'))
        next.push((await request.clone().json()) as VariationEditRequest);
    });
    await router.navigate('/automation/variations/V05?edit=1');
    await screen.findByRole('form', { name: 'Edit variation V05' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '30');
    await user.click(screen.getByRole('button', { name: 'Archive V05 and save' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/automation/variations/V12'));
    expect(next.at(-1)!.requestId).not.toBe(bodies[0]!.requestId);
  });

  it('refuses a copy of another active variation, naming it', async () => {
    const { user } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Take profit/));
    await user.type(input(/^Take profit/), '55');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    // The agreed configuration: V04 edited back to it duplicates V00.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Active variation V00 already has exactly these settings',
    );
  });

  it('explains an edit lost to a concurrent one', async () => {
    server.use(
      http.post(EDIT_URL, () =>
        HttpResponse.json(
          { code: 'AUTOMATION_CONFLICT', message: 'conflict', fields: {} },
          { status: 409 },
        ),
      ),
    );
    const { user } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '45');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Another edit of V04 was saved at the same moment',
    );
  });

  it('never offers the baseline or an archived variation for editing', async () => {
    const { router } = renderEdit('V00');
    expect(await screen.findByRole('table', { name: 'Settings of V00' })).toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit' })).toHaveAttribute('aria-disabled', 'true');
    expect(
      screen.getByText(/agreed baseline every other variation is compared with/),
    ).toBeInTheDocument();
    await router.navigate('/automation/variations/V07?edit=1');
    expect(await screen.findByRole('table', { name: 'Settings of V07' })).toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(screen.getByText(/edit its replacement, V10, instead/)).toBeInTheDocument();
  });
});

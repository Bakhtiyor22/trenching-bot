import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { VariationEditRequest } from '../../api/automation';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { VariationDetailPage } from './VariationDetailPage';
import { VariationNewPage } from './VariationNewPage';

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
    // It says what the edit saves as: the next version in V04's line.
    expect(within(form).getByText(/^Saves as/)).toHaveTextContent('Saves as V04.1.');
    expect(within(form).getByText(/V04.1, the next version of V04,/)).toBeInTheDocument();
    expect(within(form).getByText(/its existing runs stay where they are/)).toBeInTheDocument();
  });

  it('posts the complete configuration and opens the new variation, which runs only from now on', async () => {
    const bodies = editBodies();
    const { user, router } = renderEdit();
    await screen.findByRole('form', { name: 'Edit variation V04' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '50');
    await user.click(screen.getByRole('button', { name: 'Archive V04 and save' }));
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/automation/variations/V04.1'),
    );
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
      expect(screen.getByRole('table', { name: 'Settings of V04.1' })).toBeInTheDocument(),
    );
    const notice = screen
      .getAllByRole('status')
      .find((element) => element.textContent?.includes('V04.1 created'));
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
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/automation/variations/V04.1'),
    );
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
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/automation/variations/V05.1'),
    );
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
    // Its next version is the legacy label V10, shown by its name.
    expect(screen.getByText(/edit its next version, V07.1, instead/)).toBeInTheDocument();
  });
});

describe('new variation', () => {
  afterEach(() => server.events.removeAllListeners());

  function renderNew(url = '/automation/variations/new') {
    return renderPage(<VariationNewPage />, {
      path: '/automation/variations/new',
      url,
      extraRoutes: [
        { path: '/automation/variations/:label', element: <VariationDetailPage /> },
        { path: '/automation/variations', element: <p>Variations list</p> },
      ],
    });
  }

  it('starts from the agreed settings, copies another variation on request, and saves as the next base number', async () => {
    const { user, router } = renderNew();
    const form = await screen.findByRole('form', { name: 'New variation' });
    expect(within(form).getByRole('combobox', { name: /^Copy settings from/ })).toHaveValue(
      'V00 (agreed)',
    );
    expect(input(/^Take profit/)).toHaveValue('55');
    expect(within(form).getByText(/^Saves as/)).toHaveTextContent(
      'Saves as V11, unless another variation is created first.',
    );
    // An unchanged copy of an active variation would be a duplicate, so it cannot be created yet.
    expect(within(form).getByRole('button', { name: 'Create V11' })).toBeDisabled();
    expect(
      within(form).getByText(/an active variation already has these settings/),
    ).toBeInTheDocument();

    await user.click(within(form).getByRole('combobox', { name: /^Copy settings from/ }));
    await user.click(await screen.findByRole('option', { name: 'V04' }));
    await waitFor(() => expect(router.state.location.search).toBe('?from=V04'));
    expect(input(/^Take profit/)).toHaveValue('80');
    expect(screen.getByRole('form', { name: 'New variation' })).toHaveTextContent(
      'Settings, copied from V04',
    );
  });

  it('creates it and opens its page, which says it runs from now on', async () => {
    const bodies: VariationEditRequest[] = [];
    server.events.on('request:start', async ({ request }) => {
      if (
        request.method === 'POST' &&
        new URL(request.url).pathname === '/api/automation/variations'
      )
        bodies.push((await request.clone().json()) as VariationEditRequest);
    });
    const { user, router } = renderNew();
    await screen.findByRole('form', { name: 'New variation' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '45');
    await user.click(screen.getByRole('button', { name: 'Create V11' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/automation/variations/V11'));
    expect(bodies).toHaveLength(1);
    expect(bodies[0]!.requestId).toMatch(/^ui-create-/);
    expect(bodies[0]!.config).toMatchObject({ stopLossPercent: '45', takeProfitPercent: '55' });
    // The page's own notice, once the new variation has loaded (a toast says "created" too).
    expect(
      await screen.findByText(/It runs only on buys captured from now on/),
    ).toBeInTheDocument();
    expect(screen.getByRole('status', { name: /V11 created/ })).toBeInTheDocument();
  });

  it("allows an archived variation's settings back, and says plainly why a copy of an active one is refused", async () => {
    const { user } = renderNew('/automation/variations/new?from=V10');
    const form = await screen.findByRole('form', { name: 'New variation' });
    expect(
      within(form).getByText(/which is archived, so its settings can run again/),
    ).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Create V11' })).toBeEnabled();
    // V07.2 is active and V10's dip window differs; set it back to V07.2's to get a duplicate.
    await user.clear(input(/^Dip window/));
    await user.type(input(/^Dip window/), '120');
    await user.click(within(form).getByRole('button', { name: 'Create V11' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Active variation V07.2 already has exactly these settings',
    );
  });

  it('explains a create refused at the plan limit', async () => {
    server.use(
      http.post('/api/automation/variations', () =>
        HttpResponse.json(
          { code: 'VARIATION_LIMIT_REACHED', message: 'limit', fields: {} },
          { status: 409 },
        ),
      ),
    );
    const { user } = renderNew();
    await screen.findByRole('form', { name: 'New variation' });
    await user.clear(input(/^Stop loss/));
    await user.type(input(/^Stop loss/), '45');
    await user.click(screen.getByRole('button', { name: 'Create V11' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The variation was not created');
    expect(alert).toHaveTextContent('The plan has reached its limit of variations');
  });
});

describe('a request id used by another change', () => {
  afterEach(() => server.events.removeAllListeners());

  it('says so plainly, and saving again sends a fresh request id', async () => {
    let refusals = 1;
    server.use(
      http.post(EDIT_URL, () => {
        if (refusals-- > 0)
          return HttpResponse.json(
            {
              code: 'VARIATION_REQUEST_REUSED',
              message: 'reused',
              fields: { requestId: 'was already used by another create or edit' },
            },
            { status: 409 },
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
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This request was already used for a different change, so nothing was saved.',
    );
    await user.click(screen.getByRole('button', { name: 'Save again' }));
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/automation/variations/V04.1'),
    );
    expect(bodies).toHaveLength(2);
    expect(bodies[1]!.requestId).not.toBe(bodies[0]!.requestId);
  });
});

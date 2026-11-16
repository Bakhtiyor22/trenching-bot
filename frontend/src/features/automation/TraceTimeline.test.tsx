import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { AutomationItemEvent } from '../../api/automation';
import { MOCK_HISTORY_FROM } from '../../mocks/automationEventFixtures';
import { createAutomationDb, MOCK_ZONE } from '../../mocks/automationFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { TraceTimeline } from './TraceTimeline';

const blank: Omit<AutomationItemEvent, 'seq' | 'at' | 'kind'> = {
  fromState: null,
  toState: null,
  step: null,
  attempt: null,
  captureSessionId: null,
  operationId: null,
  requestsUsed: null,
  failureCode: null,
  failureMessage: null,
  actor: null,
  requestId: null,
  note: null,
  detail: null,
};

/** Sent out of order on purpose: the timeline must follow `seq`. */
const EVENTS: AutomationItemEvent[] = [
  {
    ...blank,
    seq: 3,
    at: '2026-11-02T14:20:30Z',
    kind: 'ATTEMPT_FAILED',
    step: 'MARKET',
    attempt: 2,
    captureSessionId: 'cap-1',
    operationId: 'op-2',
    requestsUsed: 9,
    failureCode: 'TIMEOUT',
    failureMessage: 'The provider did not answer in time.',
  },
  {
    ...blank,
    seq: 1,
    at: '2026-11-02T14:00:00Z',
    kind: 'HISTORY_STARTED',
    toState: 'CAPTURING',
    step: 'MARKET',
  },
  {
    ...blank,
    seq: 2,
    at: '2026-11-02T14:18:00Z',
    kind: 'OPERATION_STARTED',
    step: 'MARKET',
    attempt: 2,
    captureSessionId: 'cap-1',
    operationId: 'op-2',
  },
  {
    ...blank,
    seq: 4,
    at: '2026-11-02T14:25:00Z',
    kind: 'STOP_REQUESTED',
    actor: 'console',
    note: 'budget is low',
  },
];

function renderTrace(itemId: string) {
  return renderPage(<TraceTimeline itemId={itemId} zone={MOCK_ZONE} live={false} />, {
    path: '*',
    url: '/',
  });
}

function steps() {
  const timeline = screen.getByRole('list', { name: 'Steps' });
  return within(timeline).getAllByRole('listitem');
}

describe('TraceTimeline', () => {
  it('shows every event in seq order with its UTC+5 time, the gap before it, and what it did', async () => {
    server.use(
      http.get('/api/automation/items/:id/events', () =>
        HttpResponse.json({ itemId: 'item-1', events: EVENTS }),
      ),
    );
    renderTrace('item-1');
    await screen.findByText(/History recorded from/);
    const items = steps();
    expect(items).toHaveLength(4);

    expect(items[0]).toHaveTextContent(
      'History recorded from 2 Nov, 19:00 UTC+5; earlier steps were not recorded.',
    );
    expect(items[0]).toHaveTextContent('2 Nov, 19:00:00');

    expect(items[1]).toHaveTextContent('Started the market capture (attempt 2)');
    expect(items[1]).toHaveTextContent('19:18:00');
    expect(items[1]).toHaveTextContent('+18m 0s');
    expect(items[1]).not.toHaveTextContent('2 Nov,');

    expect(items[2]).toHaveTextContent('Attempt 2 of the market step failed');
    expect(items[2]).toHaveTextContent('+2m 30s');
    expect(items[2]).toHaveTextContent('step market · attempt 2 · 9 requests · took 2m 30s');
    expect(items[2]).toHaveTextContent('The provider did not answer in time.');

    // A kind this console does not know yet (Phase 2) still shows, with its facts.
    expect(items[3]).toHaveTextContent('Stop requested');
    expect(items[3]).toHaveTextContent('by console');
    expect(items[3]).toHaveTextContent('Note: budget is low');
  });

  it('expands a capture operation to its record from the capture API', async () => {
    const db = createAutomationDb();
    const failed = db.items.find(
      (i) => i.state === 'FAILED' && Date.parse(i.createdAt) >= Date.parse(MOCK_HISTORY_FROM),
    )!;
    const { user } = renderTrace(failed.id);
    const buttons = await screen.findAllByRole('button', { name: 'Capture details' });
    // One per operation: four finished steps before the market step would be fewer; the market step ran twice.
    expect(buttons.length).toBeGreaterThanOrEqual(2);
    const last = buttons.at(-1)!;
    await user.click(last);
    expect(last).toHaveAttribute('aria-expanded', 'true');
    const record = await screen.findByRole('table', { name: /^Capture operation / });
    expect(record).toHaveTextContent('Failed');
    expect(record).toHaveTextContent('9 of 400 allowed');
    expect(record).toHaveTextContent(failed.failureMessage!);
    expect(screen.getByRole('link', { name: 'Open the capture and its evidence' })).toHaveAttribute(
      'href',
      `/captures/${failed.captureSessionId}`,
    );
  });

  it('says when nothing is recorded yet', async () => {
    server.use(
      http.get('/api/automation/items/:id/events', () =>
        HttpResponse.json({ itemId: 'empty', events: [] }),
      ),
    );
    renderTrace('empty');
    expect(await screen.findByText('No steps recorded yet.')).toBeInTheDocument();
  });

  it('says the history is not available when the backend has no events endpoint (404)', async () => {
    server.use(
      http.get('/api/automation/items/:id/events', () => new HttpResponse(null, { status: 404 })),
    );
    renderTrace('item-1');
    expect(
      await screen.findByText('Step history is not available from this backend yet.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says the same when the backend cannot be reached', async () => {
    server.use(http.get('/api/automation/items/:id/events', () => HttpResponse.error()));
    renderTrace('item-1');
    expect(
      await screen.findByText('Step history is not available from this backend yet.', undefined, {
        timeout: 10_000,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('still reports a server failure as an error', async () => {
    server.use(
      http.get('/api/automation/items/:id/events', () =>
        HttpResponse.json(
          { code: 'FAILED', message: 'Events table locked', fields: {} },
          { status: 500 },
        ),
      ),
    );
    renderTrace('item-1');
    expect(
      await screen.findByText('Events table locked', undefined, { timeout: 10_000 }),
    ).toBeInTheDocument();
  });
});

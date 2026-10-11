import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CaptureSession } from '../../api/captures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { captureSession, FIRST_BUY_SIGNATURE, OUTSIDE_SIGNATURE, PROVEN_CLAIM, REPLAY_FAILURE_CATEGORY, SAME_SLOT_BUY_SIGNATURE, TOKEN_ACCOUNT, TRIGGER_MARKET_CAP_USD, TRIGGER_PRICE, walletTokenSessions } from '../../mocks/captureFixtures';
import { SAMPLE_PNL, TAKE_PROFIT_ENTRY_PRICE } from '../../mocks/fixtures';
import { CaptureDetailPage } from './CaptureDetailPage';
import { TokenVerdict } from './TokenVerdict';

function show(session: CaptureSession) {
  return renderPage(<CaptureDetailPage />, { path: '/captures/:id', url: `/captures/${session.id}` });
}
type Panel = ReturnType<typeof within> & { element: HTMLElement };
async function verdictPanel(): Promise<Panel> {
  const title = await screen.findByRole('heading', { name: 'Token verdict' });
  const element = title.closest('section')!;
  return Object.assign(within(element), { element });
}
/** The verdict row whose label starts with `label`. */
function row(panel: Panel, label: RegExp) {
  const header = [...panel.element.querySelectorAll('th')].find(cell => label.test(cell.textContent ?? ''));
  expect(header, `row ${label}`).toBeDefined();
  return within(header!.closest('tr')!);
}

describe('Token verdict', () => {
  it('shows a qualifying take-profit with the proven first buy and its coverage claim', async () => {
    show(walletTokenSessions.qualifying);
    const panel = await verdictPanel();
    expect(panel.getByText('Qualifying dip')).toBeInTheDocument();
    expect(row(panel, /^Capture/).getByText('COMPLETE')).toBeInTheDocument();
    expect(row(panel, /^Earliest buy through token account/).getByText('FOUND')).toBeInTheDocument();
    expect(row(panel, /^Earliest buy through token account/).getByText(`${TOKEN_ACCOUNT.slice(0, 4)}…${TOKEN_ACCOUNT.slice(-4)}`)).toBeInTheDocument();
    const actual = row(panel, /^Actual first Pump\.fun curve buy/);
    expect(actual.getByText('PROVEN')).toBeInTheDocument();
    expect(actual.getByText(PROVEN_CLAIM)).toHaveStyle({ overflowWrap: 'anywhere' });
    expect(actual.getByText(`${FIRST_BUY_SIGNATURE.slice(0, 4)}…${FIRST_BUY_SIGNATURE.slice(-4)}`)).toHaveStyle({ overflowWrap: 'anywhere' });
    expect(actual.getByText('Creation')).toBeInTheDocument();
    const dip = row(panel, /^Dip condition/);
    expect(dip.getByText('QUALIFIED')).toBeInTheDocument();
    expect(dip.getByText(TRIGGER_PRICE)).toBeInTheDocument();
    expect(dip.getByText(TRIGGER_MARKET_CAP_USD)).toBeInTheDocument();
    expect(row(panel, /^Entry/).getByText(TAKE_PROFIT_ENTRY_PRICE)).toBeInTheDocument();
    const exit = row(panel, /^Exit/);
    expect(exit.getByText('CLOSED')).toBeInTheDocument();
    expect(exit.getByText('Take profit')).toBeInTheDocument();
    expect(exit.getByText(SAMPLE_PNL)).toBeInTheDocument();
    expect(row(panel, /^Replay/).getByText('RAN')).toBeInTheDocument();
  });

  it('labels a missed dip as a negative case with its outcome', async () => {
    show(walletTokenSessions.noDip);
    const panel = await verdictPanel();
    expect(panel.getByText('Negative case')).toBeInTheDocument();
    const dip = row(panel, /^Dip condition/);
    expect(dip.getByText('NOT QUALIFIED')).toBeInTheDocument();
    expect(dip.getByText(/No dip/)).toBeInTheDocument();
    expect(row(panel, /^Entry/).getByText('NOT FILLED')).toBeInTheDocument();
  });

  it('shows the cap reached and the scanned counts for an unproven first buy, never as complete or negative', async () => {
    show(walletTokenSessions.unproven);
    const panel = await verdictPanel();
    expect(panel.getByText('First buy unproven')).toBeInTheDocument();
    const actual = row(panel, /^Actual first Pump\.fun curve buy/);
    expect(actual.getByText('UNPROVEN')).toBeInTheDocument();
    expect(actual.getByText(/Verification reached a page or fetch cap/)).toBeInTheDocument();
    expect(actual.getByText('Pump accumulator page cap')).toBeInTheDocument();
    const pump = within(actual.getByRole('cell', { name: 'Pump accumulator' }).closest('tr')!);
    expect(pump.getByText('50')).toBeInTheDocument();
    expect(pump.getByText('50000')).toBeInTheDocument();
    expect(pump.getByText('977')).toBeInTheDocument();
    expect(actual.getByText('212')).toBeInTheDocument();
    expect(actual.queryByText(PROVEN_CLAIM)).not.toBeInTheDocument();
    expect(row(panel, /^Replay/).getByText('Runs automatically after export')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
    expect(panel.queryByText('COMPLETE')).not.toBeInTheDocument();
  });

  it('explains why a first buy into another token account stops capture', async () => {
    show(walletTokenSessions.outside);
    const panel = await verdictPanel();
    expect(panel.getByText('First buy outside token account')).toBeInTheDocument();
    const actual = row(panel, /^Actual first Pump\.fun curve buy/);
    expect(actual.getByText('OUTSIDE TOKEN ACCOUNT')).toBeInTheDocument();
    expect(actual.getByText(`${OUTSIDE_SIGNATURE.slice(0, 4)}…${OUTSIDE_SIGNATURE.slice(-4)}`)).toBeInTheDocument();
    expect(actual.getByText('The wallet’s actual first buy went to another token account; this version does not adopt it, so market capture cannot proceed.')).toBeInTheDocument();
  });

  it('keeps a replay failure apart from strategy outcomes', async () => {
    show(walletTokenSessions.replayFailed);
    const panel = await verdictPanel();
    expect(panel.getByText('Replay failed')).toBeInTheDocument();
    expect(row(panel, /^Capture/).getByText('COMPLETE')).toBeInTheDocument();
    const replay = row(panel, /^Replay/);
    expect(replay.getByText(REPLAY_FAILURE_CATEGORY)).toBeInTheDocument();
    expect(replay.getByText('Replay failed — this is not a strategy outcome')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
    expect(panel.queryByText('QUALIFIED')).not.toBeInTheDocument();
  });

  it('shows an inspection-only verdict for an incomplete capture', async () => {
    show(walletTokenSessions.incomplete);
    const panel = await verdictPanel();
    expect(panel.getByText('Capture incomplete')).toBeInTheDocument();
    expect(row(panel, /^Capture/).getByText('INCOMPLETE')).toBeInTheDocument();
    expect(row(panel, /^Actual first Pump\.fun curve buy/).getByText('PROVEN')).toBeInTheDocument();
    expect(row(panel, /^Dip condition/).getByText('NOT EVALUATED')).toBeInTheDocument();
    expect(row(panel, /^Replay/).getByText('Runs automatically after export')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
    expect(panel.queryByRole('button', { name: 'Qualify' })).not.toBeInTheDocument();
  });

  it('offers a manual qualification once the export succeeded', async () => {
    const requests: Array<Record<string, unknown>> = [];
    server.use(http.post('/api/captures/:id/operations', async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      requests.push(body);
      return HttpResponse.json({ ...captureSession.operation, id: 'qualify-op', requestId: body.requestId, step: 'QUALIFY' }, { status: 202 });
    }));
    const { user } = show(walletTokenSessions.replayFailed);
    const panel = await verdictPanel();
    await user.click(panel.getByRole('button', { name: 'Qualify' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({ step: 'QUALIFY' });
  });

  it('explains an exported dataset awaiting qualification without calling it a negative case', async () => {
    show(walletTokenSessions.pendingQualification);
    const panel = await verdictPanel();
    const guidance = 'Dataset exported; qualification has not run yet or did not finish. Use Qualify.';
    expect(panel.getByText('Qualification pending')).toBeInTheDocument();
    expect(row(panel, /^Capture/).getByText(guidance)).toBeInTheDocument();
    expect(panel.getAllByText(guidance).length).toBeGreaterThan(1);
    expect(panel.getByRole('button', { name: 'Qualify' })).toBeEnabled();
    expect(panel.queryByText(/complete the capture first/i)).not.toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
  });

  it('uses the backend readiness flag for pending qualification actions', async () => {
    const session = walletTokenSessions.pendingQualification;
    renderPage(<TokenVerdict target={session.inspection?.targetToken} verdict={session.qualification} canQualify={false} disabled={false} onQualify={() => {}} />);
    const panel = await verdictPanel();
    expect(panel.queryByRole('button', { name: 'Qualify' })).not.toBeInTheDocument();
  });

  it('explains ambiguous same-slot buys and links the blocking transaction', async () => {
    show(walletTokenSessions.sameSlotBuy);
    const panel = await verdictPanel();
    const firstBuy = row(panel, /^Earliest buy through token account/);
    expect(firstBuy.getByText('Another buy of this token by the wallet is in the same slot as its earliest buy, and the order within a slot is not recorded, so neither can be called the first.')).toBeInTheDocument();
    expect(firstBuy.getByRole('link')).toHaveAttribute('href', `https://solscan.io/tx/${SAME_SLOT_BUY_SIGNATURE}`);
    expect(firstBuy.getByRole('button', { name: `Copy full value: ${SAME_SLOT_BUY_SIGNATURE}` })).toBeInTheDocument();
    expect(row(panel, /^Actual first Pump\.fun curve buy/).getByText('NOT VERIFIED')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
  });

  it('explains the missing known buy and links its blocking signature', async () => {
    show(walletTokenSessions.firstBuyNotListed);
    const panel = await verdictPanel();
    const firstBuy = row(panel, /^Actual first Pump\.fun curve buy/);
    expect(firstBuy.getByText("The wallet's Pump.fun accumulator listing did not include its known buy, so the provider's listing cannot be trusted to show earlier buys for this case.")).toBeInTheDocument();
    expect(firstBuy.getByRole('link')).toHaveAttribute('href', `https://solscan.io/tx/${FIRST_BUY_SIGNATURE}`);
    expect(firstBuy.getByRole('button', { name: `Copy full value: ${FIRST_BUY_SIGNATURE}` })).toBeInTheDocument();
    expect(firstBuy.getByText('UNPROVEN')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
  });

  it('explains a replay first-buy mismatch as a data problem and retains its category', async () => {
    const session = walletTokenSessions.replayFailed;
    if (!session.qualification) throw new Error('Replay failure fixture must include a verdict');
    renderPage(<TokenVerdict target={session.inspection?.targetToken} verdict={{ ...session.qualification, replay: { state: 'FAILED', category: 'FIRST_BUY_MISMATCH' } }} canQualify={true} disabled={false} onQualify={() => {}} />);
    const panel = await verdictPanel();
    const replay = row(panel, /^Replay/);
    expect(replay.getByText('FIRST_BUY_MISMATCH')).toBeInTheDocument();
    expect(replay.getByText('The replay started from a different buy than the proven first buy; this is a data problem, not a strategy outcome.')).toBeInTheDocument();
    expect(panel.queryByText(/negative case/i)).not.toBeInTheDocument();
  });

  it('stays hidden for sessions of the other scopes', async () => {
    renderPage(<CaptureDetailPage />, { path: '/captures/:id', url: `/captures/${captureSession.id}` });
    expect(await screen.findByRole('button', { name: 'Inspect evidence' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Token verdict' })).not.toBeInTheDocument();
  });
});

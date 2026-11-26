import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RpcAccounts, RpcBalanceProblem } from '../../api/types';
import { createHandlers } from '../../mocks/handlers';
import { rpcAccountsFixture, type RpcAccountScenario } from '../../mocks/rpcAccountFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { RpcAccountsSection } from './RpcAccountsSection';
import { SolanaPage } from './SolanaPage';

/** Sunday 22 Nov 2026: the fixtures' primary cycle then ends on 30 Nov, the backup's on 13 Dec. */
const NOW = new Date('2026-11-22T09:30:00Z');
const HOLD = 'Capture is held until an account has credits again';

// Only `Date` is faked, so "in 8 days" and the fixtures' relative times are the same on every run.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

function renderSection(scenario: RpcAccountScenario) {
  server.use(...createHandlers({ solanaConfigured: true, rpcAccounts: scenario }));
  return renderPage(<RpcAccountsSection />);
}

function answerWith(data: RpcAccounts) {
  server.use(http.get('/api/solana/accounts', () => HttpResponse.json(data)));
}

const card = (name: 'Primary' | 'Backup') =>
  screen.findByRole('article', { name: `${name} RPC account` });
const chip = (scope: HTMLElement, label: string) =>
  within(scope).getByText(label, { selector: '.console-chip' });

describe('RPC accounts section', () => {
  it('shows the account in use with its credits, cycle end, plan and last check', async () => {
    renderSection('healthy');
    const primary = await card('Primary');
    expect(chip(primary, 'In use')).toHaveAttribute('data-tone', 'success');
    expect(within(primary).getByText('mainnet.helius-rpc.com')).toBeInTheDocument();
    expect(within(primary).getByText('412,300')).toBeInTheDocument();
    expect(within(primary).getByText('/ 1,000,000')).toBeInTheDocument();
    const meter = within(primary).getByRole('meter', {
      name: 'Primary account: credits left of the limit',
    });
    expect(meter).toHaveAttribute('aria-valuenow', '412300');
    expect(meter).toHaveAttribute('aria-valuemax', '1000000');
    expect(within(primary).getByText('41.2% left · 587,700 used this cycle')).toBeInTheDocument();
    expect(within(primary).getByText('30 Nov 2026 (UTC)')).toBeInTheDocument();
    expect(within(primary).getByText('in 8 days')).toBeInTheDocument();
    expect(within(primary).getByText('free')).toBeInTheDocument();
    expect(within(primary).getByText('Balance checked')).toBeInTheDocument();
    expect(within(primary).getByText('4 min ago')).toBeInTheDocument();
    expect(within(primary).queryByText('Out of credits since')).not.toBeInTheDocument();

    const backup = await card('Backup');
    expect(chip(backup, 'Standby')).toHaveAttribute('data-tone', 'neutral');
    expect(
      within(backup).getByText('Has credits and takes over when the account in use runs out.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Requests go to the primary account.')).toBeInTheDocument();
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
  });

  it('never shows a URL or a key and never asks for one', async () => {
    const { container } = renderSection('balance-problem');
    await card('Primary');
    expect(container.textContent).not.toMatch(/https?:\/\//);
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('marks a balance under ten percent as low in words, not by colour alone', async () => {
    renderSection('low-balance');
    const primary = await card('Primary');
    expect(
      within(primary).getByText('6.2% left · low: under 10% · 937,520 used this cycle'),
    ).toBeInTheDocument();
  });

  it('shows the backup in use while the primary is out of credits', async () => {
    renderSection('backup-active');
    const primary = await card('Primary');
    expect(chip(primary, 'Out of credits')).toHaveAttribute('data-tone', 'attention');
    expect(within(primary).getByText('Out of credits since')).toBeInTheDocument();
    expect(within(primary).getByText('0')).toBeInTheDocument();
    expect(
      within(primary).getByText(/used again on its own once a balance read shows credits/),
    ).toBeInTheDocument();
    expect(chip(await card('Backup'), 'In use')).toBeInTheDocument();
    expect(
      screen.getByText(/Requests go to the backup account while the primary is out of credits/),
    ).toBeInTheDocument();
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
  });

  it('says capture is held when both accounts are out of credits', async () => {
    renderSection('all-out');
    expect(await screen.findByText(HOLD)).toBeInTheDocument();
    expect(screen.getByText(/No RPC account can take requests/)).toBeInTheDocument();
    expect(chip(await card('Primary'), 'Out of credits')).toBeInTheDocument();
    expect(chip(await card('Backup'), 'Out of credits')).toBeInTheDocument();
    expect(screen.queryByText(/Requests go to/)).not.toBeInTheDocument();
    // On the Solana page itself the notice needs no link to it.
    expect(screen.queryByRole('link', { name: /Solana page/ })).not.toBeInTheDocument();
  });

  it('explains a backup that has credits but still waits for its mainnet check', async () => {
    renderSection('backup-unverified');
    expect(await screen.findByText(HOLD)).toBeInTheDocument();
    expect(
      screen.getByText(/The backup has credits but is not used until it has been checked/),
    ).toBeInTheDocument();
    expect(
      within(await card('Backup')).getByText(
        'Has credits, but is not used until it has been checked as Solana mainnet.',
      ),
    ).toBeInTheDocument();
  });

  it('says how to add a backup that is not configured, by variable name only', async () => {
    renderSection('no-backup');
    const backup = await card('Backup');
    expect(chip(backup, 'Not configured')).toHaveAttribute('data-tone', 'neutral');
    expect(within(backup).getByText(/No backup account is set up/)).toBeInTheDocument();
    expect(within(backup).getByText('SOLANA_RPC_BACKUP_URL')).toBeInTheDocument();
    expect(within(backup).getByText('HELIUS_BACKUP_PROJECT_ID')).toBeInTheDocument();
    expect(within(backup).queryByRole('meter')).not.toBeInTheDocument();
    expect(within(backup).queryByText('Credits left')).not.toBeInTheDocument();
    // A missing backup is not a hold: the primary takes the requests.
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
  });

  it('explains a balance that was never read and one that keeps its last figures', async () => {
    renderSection('balance-problem');
    const primary = await card('Primary');
    expect(within(primary).getByText('Not known')).toBeInTheDocument();
    expect(within(primary).queryByRole('meter')).not.toBeInTheDocument();
    expect(within(primary).getByText('The balance could not be read')).toBeInTheDocument();
    expect(within(primary).getByText('HELIUS_PROJECT_ID')).toBeInTheDocument();
    expect(
      within(primary).getByText('No balance has been read for this account yet.'),
    ).toBeInTheDocument();
    expect(within(primary).getByText('Technical code: NO_PROJECT_ID')).toBeInTheDocument();
    // The account still takes requests: a balance problem is not an outage.
    expect(chip(primary, 'In use')).toBeInTheDocument();

    const backup = await card('Backup');
    expect(within(backup).getByText(/Helius rate limited the balance read/)).toBeInTheDocument();
    expect(within(backup).getByText('874,112')).toBeInTheDocument();
    expect(
      within(backup).getByText('The credits shown are from the last read that worked.'),
    ).toBeInTheDocument();
  });

  it.each<[RpcBalanceProblem, RegExp, string | null]>([
    ['NO_PROJECT_ID', /No Helius project id is set for this account/, 'HELIUS_BACKUP_PROJECT_ID'],
    ['NO_API_KEY', /carries no api-key parameter/, 'SOLANA_RPC_BACKUP_URL'],
    ['INVALID_PROJECT', /project id does not belong to this account/, 'HELIUS_BACKUP_PROJECT_ID'],
    ['AUTHENTICATION', /Helius refused this account.s key/, 'SOLANA_RPC_BACKUP_URL'],
    ['ADMIN_API_DISABLED', /has not enabled its admin API for this project/, null],
    ['RATE_LIMITED', /Helius rate limited the balance read/, null],
    ['UNAVAILABLE', /Helius could not be reached for the balance read/, null],
    ['MALFORMED', /in a form this version cannot read/, null],
  ])(
    'explains the balance problem %s and names the backup variable',
    async (problem, text, variable) => {
      const data = rpcAccountsFixture('healthy', NOW.getTime());
      answerWith({
        ...data,
        accounts: data.accounts.map((account) =>
          account.name === 'backup' ? { ...account, balanceProblem: problem } : account,
        ),
      });
      renderPage(<RpcAccountsSection />);
      const backup = await card('Backup');
      // The sentence is split by the variable names, so it is matched on the paragraph's whole text.
      const alert = within(backup).getByText('The balance could not be read').closest('[role]');
      expect(alert?.textContent).toMatch(text);
      if (variable) expect(within(backup).getAllByText(variable).length).toBeGreaterThan(0);
      expect(within(backup).getByText(`Technical code: ${problem}`)).toBeInTheDocument();
    },
  );

  it('says a backup on another network is never used', async () => {
    renderSection('wrong-network');
    const backup = await card('Backup');
    expect(chip(backup, 'Wrong network')).toHaveAttribute('data-tone', 'failure');
    expect(within(backup).getByText('Not on Solana mainnet')).toBeInTheDocument();
    expect(within(backup).getByText(/so it is never used/)).toBeInTheDocument();
    expect(within(backup).getByText('SOLANA_RPC_BACKUP_URL')).toBeInTheDocument();
    expect(chip(await card('Primary'), 'In use')).toBeInTheDocument();
  });

  it('shows a state and a problem it has no words for in words, with the code beside them', async () => {
    const data = rpcAccountsFixture('healthy', NOW.getTime());
    answerWith({
      ...data,
      accounts: data.accounts.map((account) =>
        account.name === 'backup'
          ? ({ ...account, state: 'BEING_MOVED', balanceProblem: 'QUOTA_PAUSED' } as never)
          : account,
      ),
    });
    renderPage(<RpcAccountsSection />);
    const backup = await card('Backup');
    expect(chip(backup, 'Being moved')).toBeInTheDocument();
    expect(within(backup).getByText('Technical code: BEING_MOVED')).toBeInTheDocument();
    expect(
      within(backup).getByText('The balance could not be read: quota paused.'),
    ).toBeInTheDocument();
  });

  it('says where the accounts will appear while no RPC is configured', async () => {
    renderPage(<RpcAccountsSection />);
    expect(await screen.findByText(/No RPC account is set up yet/)).toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    // No RPC at all is a setup state, not a hold for credits.
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
  });

  it('shows the error state with a retry when the accounts never loaded', async () => {
    server.use(
      http.get('/api/solana/accounts', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Backend unreachable' }, { status: 503 }),
      ),
    );
    const { user } = renderPage(<RpcAccountsSection />);
    expect(await screen.findByText('Could not load the RPC accounts')).toBeInTheDocument();
    expect(screen.getByText('Backend unreachable')).toBeInTheDocument();
    answerWith(rpcAccountsFixture('healthy', NOW.getTime()));
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await card('Primary')).toBeInTheDocument();
  });

  it('keeps the accounts on screen when a refresh fails, and stops saying it is live', async () => {
    const { queryClient } = renderSection('healthy');
    const primary = await card('Primary');
    expect(screen.getByText('Updating live')).toBeInTheDocument();
    server.use(
      http.get('/api/solana/accounts', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Backend unreachable' }, { status: 503 }),
      ),
    );
    await queryClient.refetchQueries();
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(within(primary).getByText('412,300')).toBeInTheDocument();
    expect(screen.queryByText('Could not load the RPC accounts')).not.toBeInTheDocument();
    expect(screen.queryByText('Updating live')).not.toBeInTheDocument();
  });

  it('follows a switch of the account in use on the next read', async () => {
    const { queryClient } = renderSection('healthy');
    expect(chip(await card('Primary'), 'In use')).toBeInTheDocument();
    answerWith(rpcAccountsFixture('backup-active', NOW.getTime()));
    await queryClient.refetchQueries();
    await waitFor(async () => expect(chip(await card('Backup'), 'In use')).toBeInTheDocument());
    expect(chip(await card('Primary'), 'Out of credits')).toBeInTheDocument();
  });
});

describe('Solana page with RPC accounts', () => {
  it('shows the accounts above the connectivity checks', async () => {
    server.use(...createHandlers({ solanaConfigured: true }));
    renderPage(<SolanaPage />);
    expect(await card('Primary')).toBeInTheDocument();
    expect(await screen.findByText('api.devnet.solana.com')).toBeInTheDocument();
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(headings).toEqual(['RPC accounts', 'RPC status', 'Address check']);
  });

  it('keeps the connectivity checks usable when the accounts cannot be loaded', async () => {
    server.use(...createHandlers({ solanaConfigured: true }));
    server.use(
      http.get('/api/solana/accounts', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Backend unreachable' }, { status: 503 }),
      ),
    );
    renderPage(<SolanaPage />);
    expect(await screen.findByText('Could not load the RPC accounts')).toBeInTheDocument();
    expect(await screen.findByText('api.devnet.solana.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run connectivity check' })).toBeEnabled();
  });
});

import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { queryKeys } from '../../api/queries';
import { createAutomationDb } from '../../mocks/automationFixtures';
import { createHandlers } from '../../mocks/handlers';
import type { RpcAccountScenario } from '../../mocks/rpcAccountFixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { StatusAlerts } from '../automation/AutomationShared';
import { RpcCreditsHold } from './RpcCreditsHold';

const HOLD = 'Capture is held until an account has credits again';
const status = createAutomationDb().status;

function accountsAre(scenario: RpcAccountScenario) {
  server.use(...createHandlers({ solanaConfigured: true, rpcAccounts: scenario }));
}

describe('RPC credits hold notice', () => {
  it('says capture is held and links to the Solana page while no account has credits', async () => {
    accountsAre('all-out');
    renderPage(<RpcCreditsHold />);
    expect(await screen.findByText(HOLD)).toBeInTheDocument();
    expect(
      screen.getByText(/the automation keeps each buy at its step with no attempt charged/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'See both accounts on the Solana page' }),
    ).toHaveAttribute('href', '/solana');
  });

  it.each<RpcAccountScenario>(['healthy', 'backup-active', 'no-backup', 'unconfigured'])(
    'shows nothing while an account takes requests or none is set up (%s)',
    async (scenario) => {
      accountsAre(scenario);
      const { queryClient } = renderPage(<RpcCreditsHold />);
      await waitFor(() => expect(queryClient.getQueryData(queryKeys.solanaAccounts)).toBeDefined());
      expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
    },
  );

  it('shows nothing when the accounts cannot be read', async () => {
    server.use(
      http.get('/api/solana/accounts', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Backend unreachable' }, { status: 503 }),
      ),
    );
    const { queryClient } = renderPage(<RpcCreditsHold />);
    await waitFor(() =>
      expect(queryClient.getQueryState(queryKeys.solanaAccounts)?.status).toBe('error'),
    );
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('automation status alerts', () => {
  it('explain the hold while no RPC account has credits', async () => {
    accountsAre('all-out');
    renderPage(<StatusAlerts status={status} />);
    expect(await screen.findByText(HOLD)).toBeInTheDocument();
  });

  it('leave the credits out when no RPC is configured at all', async () => {
    accountsAre('all-out');
    renderPage(<StatusAlerts status={{ ...status, rpcConfigured: false }} />);
    expect(await screen.findByText('No Solana RPC configured')).toBeInTheDocument();
    expect(screen.queryByText(HOLD)).not.toBeInTheDocument();
  });
});

import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { createHandlers } from '../../mocks/handlers';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { SolanaPage } from './SolanaPage';

describe('SolanaPage', () => {
  it('explains how to configure the RPC without asking for the URL', async () => {
    const { user } = renderPage(<SolanaPage />);
    expect(await screen.findByText('No Solana RPC configured')).toBeInTheDocument();
    expect(screen.getByText('SOLANA_RPC_URL')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check address' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Run connectivity check' }));
    // The status chip and the check result both read "Not configured".
    await waitFor(() =>
      expect(screen.getAllByText('Not configured', { selector: '.console-chip' })).toHaveLength(2),
    );
    expect(screen.getByText('SOLANA_RPC_URL is not set')).toBeInTheDocument();
  });

  it('runs a connectivity check and an address check when configured', async () => {
    server.use(...createHandlers({ solanaConfigured: true }));
    const { user } = renderPage(<SolanaPage />);
    await screen.findByText('api.devnet.solana.com');

    await user.click(screen.getByRole('button', { name: 'Run connectivity check' }));
    expect(await screen.findByText('182 ms')).toBeInTheDocument();
    expect(screen.getByText('OK')).toBeInTheDocument();
    // The cluster reads in words, with the reported name kept as its code.
    expect(screen.getAllByText('Devnet').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Technical code: devnet').length).toBeGreaterThan(0);

    const input = screen.getByLabelText('Address');
    await user.type(input, 'not-an-address');
    await user.click(screen.getByRole('button', { name: 'Check address' }));
    expect(await screen.findByText('Enter a base58 Solana address (32 bytes)')).toBeInTheDocument();

    await user.clear(input);
    await user.type(input, 'So11111111111111111111111111111111111111112');
    await user.click(screen.getByRole('button', { name: 'Check address' }));
    expect(await screen.findByText('Checked', { selector: '.console-chip' })).toBeInTheDocument();
    expect(screen.getByText('meta.postTokenBalances[].uiTokenAmount.amount')).toBeInTheDocument();
    expect(screen.getAllByText('missing')).toHaveLength(2);
    expect(screen.getAllByText('present')).toHaveLength(6);
  });

  it('keeps the status on screen when a refresh fails', async () => {
    server.use(...createHandlers({ solanaConfigured: true }));
    const { queryClient } = renderPage(<SolanaPage />);
    expect(await screen.findByText('api.devnet.solana.com')).toBeInTheDocument();
    server.use(
      http.get('/api/solana/status', () =>
        HttpResponse.json({ code: 'UNAVAILABLE', message: 'Backend unreachable' }, { status: 503 }),
      ),
    );
    await queryClient.refetchQueries();
    expect(await screen.findByText(/Couldn.t refresh/)).toBeInTheDocument();
    expect(screen.getByText('api.devnet.solana.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run connectivity check' })).toBeInTheDocument();
  });

  it('shows a rate-limited check with a countdown', async () => {
    server.use(...createHandlers({ solanaConfigured: true }));
    // Added last, so it takes precedence over the configured check handler.
    server.use(
      http.post('/api/solana/check', () =>
        HttpResponse.json(
          { code: 'PROVIDER_RATE_LIMITED', message: 'Too many requests', retryAfterSeconds: 30 },
          { status: 429 },
        ),
      ),
    );
    const { user } = renderPage(<SolanaPage />);
    await screen.findByText('api.devnet.solana.com');
    await user.click(screen.getByRole('button', { name: 'Run connectivity check' }));
    expect(await screen.findByText('The provider is busy (rate limited)')).toBeInTheDocument();
    expect(screen.getByText(/seconds until you can try again/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try now' })).toBeDisabled();
  });
});

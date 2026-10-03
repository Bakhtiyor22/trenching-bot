import { screen, waitFor } from '@testing-library/react';
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
    // The status badge and the check result both read "Not configured".
    await waitFor(() =>
      expect(screen.getAllByText('Not configured', { selector: '.mantine-Badge-label' })).toHaveLength(2),
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

    const input = screen.getByLabelText('Address');
    await user.type(input, 'not-an-address');
    await user.click(screen.getByRole('button', { name: 'Check address' }));
    expect(await screen.findByText('Enter a base58 Solana address (32 bytes)')).toBeInTheDocument();

    await user.clear(input);
    await user.type(input, 'So11111111111111111111111111111111111111112');
    await user.click(screen.getByRole('button', { name: 'Check address' }));
    expect(await screen.findByText('Checked', { selector: '.mantine-Badge-label' })).toBeInTheDocument();
    expect(screen.getByText('meta.postTokenBalances[].uiTokenAmount.amount')).toBeInTheDocument();
    expect(screen.getAllByText('missing')).toHaveLength(2);
    expect(screen.getAllByText('present')).toHaveLength(6);
  });
});

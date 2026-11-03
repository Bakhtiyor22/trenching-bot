import { spotlight } from '@mantine/spotlight';
import { act, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../test/render';
import { CommandPalette } from './CommandPalette';

describe('CommandPalette', () => {
  it('goes to API usage and New run, and shows run P&L signed', async () => {
    const { user, router } = renderPage(<CommandPalette />, {
      path: '*',
      url: '/',
    });
    act(() => spotlight.open());
    expect(await screen.findByText('API usage')).toBeInTheDocument();
    expect(screen.getByText('New run')).toBeInTheDocument();
    // Run P&L carries its sign: "+" for a gain, the true minus for a loss.
    expect((await screen.findAllByText(/^[+−]\d[\d,]*\.\d+ SOL$/)).length).toBeGreaterThan(0);

    await user.click(screen.getByText('API usage'));
    expect(router.state.location.pathname).toBe('/usage');
  });
});

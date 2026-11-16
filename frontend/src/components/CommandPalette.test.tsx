import { spotlight } from '@mantine/spotlight';
import { act, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { createAutomationDb, mockDayOf } from '../mocks/automationFixtures';
import { listedRuns } from '../mocks/runListFixtures';
import { renderPage } from '../test/render';
import { shortId } from '../lib/format';
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

  it('finds a pasted mint: its runs and its automation buy on the day page', async () => {
    const mint = listedRuns[0]!.mints[0]!;
    const buy = createAutomationDb().items.find((i) => i.mint === mint)!;
    const { user, router } = renderPage(<CommandPalette />, { path: '*', url: '/' });
    act(() => spotlight.open());
    await user.type(await screen.findByRole('textbox', { name: 'Command palette search' }), mint);
    expect(await screen.findByText(`Runs of ${shortId(mint)}`)).toBeInTheDocument();
    expect(screen.getByText(/^10 runs · opens the Runs page/)).toBeInTheDocument();
    await user.click(await screen.findByText(`Automation buy of ${shortId(mint)}`));
    expect(router.state.location.pathname).toBe(`/automation/days/${mockDayOf(buy.blockTime!)}`);
    expect(router.state.location.search).toBe(`?item=${buy.id}`);
  });

  it('says when a pasted mint has no run and no buy, and names automation without "nightly"', async () => {
    const { user } = renderPage(<CommandPalette />, { path: '*', url: '/' });
    act(() => spotlight.open());
    expect(await screen.findByText('Automation: sweep scoreboard')).toBeInTheDocument();
    expect(screen.queryByText(/nightly/)).toBeNull();
    const search = screen.getByRole('textbox', { name: 'Command palette search' });
    await user.type(search, 'So11111111111111111111111111111111111111112');
    expect(
      await screen.findByText('No run and no automation buy holds this token.'),
    ).toBeInTheDocument();
  });
});

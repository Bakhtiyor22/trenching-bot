import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { sampleObservations } from '../mocks/fixtures';
import { renderPage } from '../test/render';
import { ObservationTable } from './ObservationTable';

describe('ObservationTable action target', () => {
  it('opens the selected observation page, highlights its stable row and scrolls to it', async () => {
    const scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
    const observations = Array.from({ length: 56 }, (_, index) => ({
      ...sampleObservations[0]!,
      id: `recorded:trade/${index}`,
    }));
    renderPage(<ObservationTable observations={observations} selectedId="recorded:trade/55" />);

    const row = (await screen.findByText('recorded:trade/55')).closest('tr');
    expect(row).toHaveAttribute('id', 'observation-recorded%3Atrade%2F55');
    expect(row).toHaveAttribute('data-highlighted', 'true');
    expect(screen.queryByText('recorded:trade/0')).not.toBeInTheDocument();
    await waitFor(() => expect(scroll).toHaveBeenCalled());
    scroll.mockRestore();
  });
});

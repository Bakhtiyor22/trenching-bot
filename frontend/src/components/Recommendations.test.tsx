import { screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { renderPage } from '../test/render';
import { Recommendations } from './Recommendations';

beforeEach(() => localStorage.clear());

it('runs a comparison action once and never offers Undo', async () => {
  const apply = vi.fn();
  const { user } = renderPage(
    <Recommendations
      items={[
        {
          id: 'ADD_LEGACY_COMPARISON',
          appliesTo: 'RUN',
          setting: null,
          facts: { dipPercent: '15', takeProfitPercent: '30', stopLossPercent: '15' },
          caveatCode: null,
        },
      ]}
      onApply={apply}
      onUndo={vi.fn()}
    />,
  );
  await user.click(screen.getByRole('button', { name: 'Add comparison run' }));
  expect(apply).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Add comparison run' })).toBeDisabled();
});

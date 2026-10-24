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

it('describes a DECIDED cutoff by the agreed levels and clocks and its minimum recording', () => {
  renderPage(
    <Recommendations
      items={[
        {
          id: 'CUTOFF_DECIDED',
          appliesTo: 'CAPTURE',
          setting: { field: 'cutoffMode', value: 'DECIDED' },
          facts: {
            dipPercent: '20',
            takeProfitPercent: '55',
            stopLossPercent: '40',
            dipWindowSeconds: 30,
            maxHoldSeconds: 180,
            hardExitStartSeconds: 240,
            minimumRecordingSeconds: 240,
          },
          caveatCode: 'CUTOFF_USES_AGREED_STRATEGY',
        },
      ]}
      onApply={vi.fn()}
      onUndo={vi.fn()}
    />,
  );
  expect(
    screen.getByText(/20% dip within 30 s, \+55% \/ −40%, time exit at 3 \/ 4 min/),
  ).toBeInTheDocument();
  expect(
    screen.getByText(
      /Windows are cut by the agreed strategy and kept at least 240 s after Decu's buy/,
    ),
  ).toBeInTheDocument();
  expect(screen.queryByText(/old default/i)).not.toBeInTheDocument();
});

it('warns that a legacy comparison may need a FULL capture', () => {
  renderPage(
    <Recommendations
      items={[
        {
          id: 'ADD_LEGACY_COMPARISON',
          appliesTo: 'RUN',
          setting: null,
          facts: { dipPercent: '15', takeProfitPercent: '30', stopLossPercent: '15' },
          caveatCode: 'COMPARISON_MAY_NEED_FULL_CAPTURE',
        },
      ]}
      onApply={vi.fn()}
      onUndo={vi.fn()}
    />,
  );
  expect(screen.getByText(/capture with FULL to compare/)).toBeInTheDocument();
});

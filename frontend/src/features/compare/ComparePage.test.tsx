import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EDGE_RUN_ID, SAMPLE_RUN_ID, SAMPLE_TOTAL_PNL } from '../../mocks/fixtures';
import { formatSignedDecimal } from '../../lib/decimal';
import { renderPage } from '../../test/render';
import { ComparePage } from './ComparePage';

function renderCompare(url: string) {
  return renderPage(<ComparePage />, { path: '/compare', url });
}

describe('ComparePage', () => {
  it('asks for two runs when the URL names fewer', async () => {
    renderCompare(`/compare?runs=${SAMPLE_RUN_ID}`);
    expect(await screen.findByText('Pick at least two runs')).toBeInTheDocument();
  });

  it('shows the exact P&L gap, what differs and each token side by side', async () => {
    const { user } = renderCompare(`/compare?runs=${SAMPLE_RUN_ID},${EDGE_RUN_ID}`);
    // The edge run realized zero, so A − B is the sample's own total.
    const gap = await screen.findByRole('region', { name: 'P&L difference' });
    expect(within(gap).getByText(formatSignedDecimal(SAMPLE_TOTAL_PNL, { trim: true }))).toBeInTheDocument();
    expect(within(gap).getByText('Δ P&L A − B')).toBeInTheDocument();
    expect(screen.getByText(/^Same configuration, different datasets/)).toBeInTheDocument();

    await user.click(screen.getByRole('switch', { name: 'Only differences' }));
    const inputs = screen.getByRole('table', { name: 'Inputs side by side' });
    expect(within(inputs).getAllByRole('row')).toHaveLength(2);
    expect(within(inputs).getByText('Dataset')).toBeInTheDocument();
    expect(within(inputs).getByText('differs')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /All tokens/ }));
    const tokens = screen.getByRole('table', { name: 'Tokens side by side' });
    expect(within(tokens).getByText('TP')).toBeInTheDocument();
    expect(within(tokens).getByText('OPEN')).toBeInTheDocument();
  });

  it('drops a run from the URL when it is removed', async () => {
    const { user, router } = renderCompare(`/compare?runs=${SAMPLE_RUN_ID},${EDGE_RUN_ID}`);
    await user.click(await screen.findByRole('button', { name: /Remove run 9a8b…1e0f/ }));
    expect(router.state.location.search).toBe(`?runs=${SAMPLE_RUN_ID}`);
    expect(await screen.findByText('Pick at least two runs')).toBeInTheDocument();
  });

  it('shows an unknown run as an error on its own card and keeps the others', async () => {
    renderCompare(`/compare?runs=${SAMPLE_RUN_ID},nope,${EDGE_RUN_ID}`);
    const failed = await screen.findByRole('alert');
    expect(within(failed).getByText('Run nope not found')).toBeInTheDocument();
    expect(within(failed).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    // The two loaded runs still compare, keeping their letters A and C.
    const gap = await screen.findByRole('region', { name: 'P&L difference' });
    expect(within(gap).getByText('Δ P&L A − C')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '4f0c…c5d6' })).toBeInTheDocument();
  });
});

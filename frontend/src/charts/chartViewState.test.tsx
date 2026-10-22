import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useSearchParams } from 'react-router';
import { renderPage } from '../test/render';
import {
  chartSearchParams,
  followChartExtent,
  readChartWindow,
  useChartView,
} from './chartViewState';

const extent = { from: 100_000, to: 200_000 };
const cycle = { from: 120_000, to: 180_000 };

function ViewHarness() {
  const [, setParams] = useSearchParams();
  const view = useChartView({ kind: 'run', extent, resetRange: cycle });
  return (
    <>
      <output aria-label="Window">{JSON.stringify(view.window)}</output>
      <output aria-label="Layers">{view.layers.join(',')}</output>
      <output aria-label="Metric">{view.metric}</output>
      <output aria-label="Style">{view.style}</output>
      <button onClick={view.fit}>Fit</button>
      <button onClick={view.reset}>Reset</button>
      <button onClick={() => view.zoom(0.5)}>Zoom in</button>
      <button onClick={() => view.pan(0.25)}>Pan right</button>
      <button onClick={() => view.setMetric('price')}>Price</button>
      <button onClick={() => view.setStyle('candles')}>Candles</button>
      <button onClick={() => view.setLayers(['decu', 'watchlist'])}>Only wallet</button>
      <button
        onClick={() =>
          setParams((current) => {
            const next = new URLSearchParams(current);
            next.delete('from');
            next.delete('to');
            next.set('action', 'buy-2');
            return next;
          })
        }
      >
        Select action
      </button>
    </>
  );
}

describe('chart view state', () => {
  it('round-trips URL windows, metric, style and layers while retaining the selected action', async () => {
    const { user, router } = renderPage(<ViewHarness />, { url: '/?action=buy-1&pair=token' });
    expect(screen.getByLabelText('Window')).toHaveTextContent(JSON.stringify(cycle));
    await user.click(screen.getByRole('button', { name: 'Fit' }));
    expect(screen.getByLabelText('Window')).toHaveTextContent(JSON.stringify(extent));
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(screen.getByLabelText('Window')).toHaveTextContent(
      JSON.stringify({ from: 125_000, to: 175_000 }),
    );
    await user.click(screen.getByRole('button', { name: 'Pan right' }));
    expect(screen.getByLabelText('Window')).toHaveTextContent(
      JSON.stringify({ from: 137_500, to: 187_500 }),
    );
    await user.click(screen.getByRole('button', { name: 'Price' }));
    await user.click(screen.getByRole('button', { name: 'Candles' }));
    await user.click(screen.getByRole('button', { name: 'Only wallet' }));
    const params = new URLSearchParams(router.state.location.search);
    expect(readChartWindow(params)).toEqual({ from: 137_500, to: 187_500 });
    expect(params.get('view')).toBe('price');
    expect(params.get('style')).toBe('candles');
    expect(params.get('layers')).toBe('decu,watchlist');
    expect(params.get('action')).toBe('buy-1');
    await user.click(screen.getByRole('button', { name: 'Reset' }));
    expect(screen.getByLabelText('Window')).toHaveTextContent(JSON.stringify(cycle));
  });

  it('restores layer and value choices directly from a reloaded URL', () => {
    renderPage(<ViewHarness />, {
      url: '/?view=price&style=candles&layers=decu,watchlist&from=1970-01-01T00:02:10Z&to=1970-01-01T00:02:40Z',
    });
    expect(screen.getByLabelText('Window')).toHaveTextContent(
      JSON.stringify({ from: 130_000, to: 160_000 }),
    );
    expect(screen.getByLabelText('Layers')).toHaveTextContent('decu,watchlist');
    expect(screen.getByLabelText('Metric')).toHaveTextContent('price');
    expect(screen.getByLabelText('Style')).toHaveTextContent('candles');
  });

  it('initializes its default window once and preserves subsequent action navigation', async () => {
    const { user, router } = renderPage(<ViewHarness />);
    await user.click(screen.getByRole('button', { name: 'Select action' }));
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('action')).toBe('buy-2');
    expect(params.has('from')).toBe(false);
    expect(params.has('to')).toBe(false);
  });

  it('follows a growing extent only when the previous window touched its right edge', () => {
    const next = { from: 100_000, to: 230_000 };
    expect(followChartExtent({ from: 150_000, to: 200_000 }, extent, next)).toEqual({
      from: 180_000,
      to: 230_000,
    });
    expect(followChartExtent({ from: 120_000, to: 170_000 }, extent, next)).toEqual({
      from: 120_000,
      to: 170_000,
    });
  });

  it('carries chart state in navigation links and excludes unrelated page options', () => {
    const params = new URLSearchParams(
      'pair=old&from=a&to=b&view=price&style=line&layers=paper&action=x&fill=y&quote=usdt',
    );
    const linked = new URLSearchParams(chartSearchParams(params, 'new').slice(1));
    expect(Object.fromEntries(linked)).toEqual({
      pair: 'new',
      from: 'a',
      to: 'b',
      view: 'price',
      style: 'line',
      layers: 'paper',
      action: 'x',
      fill: 'y',
    });
    expect(readChartWindow(new URLSearchParams('from=invalid&to=invalid'))).toBeNull();
  });
});

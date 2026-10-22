import { describe, expect, it } from 'vitest';
import { clampRange, panRange, zoomRange, tradeRange } from './chartRange';

describe('chart navigation', () => {
  const extent = { from: 100_000, to: 200_000 };
  it('keeps the span when panning into either boundary', () => {
    expect(clampRange({ from: 80_000, to: 120_000 }, extent)).toEqual({
      from: 100_000,
      to: 140_000,
    });
    expect(panRange({ from: 160_000, to: 200_000 }, 0.25, extent)).toEqual({
      from: 160_000,
      to: 200_000,
    });
  });
  it('zooms around the centre and clamps a larger view to the full extent', () => {
    expect(zoomRange(extent, 0.5, extent)).toEqual({ from: 125_000, to: 175_000 });
    expect(zoomRange(extent, 2, extent)).toEqual(extent);
  });
  it('pads the selected trade by ten seconds before and thirty after its exit', () => {
    expect(tradeRange(120_000, 150_000, extent)).toEqual({ from: 110_000, to: 180_000 });
  });
  it('keeps a two-second minimum and zooms around the cursor', () => {
    expect(zoomRange({ from: 120_000, to: 120_500 }, 0.5, extent)).toEqual({
      from: 119_250,
      to: 121_250,
    });
    expect(zoomRange(extent, 0.5, extent, 120_000)).toEqual({ from: 110_000, to: 160_000 });
    expect(clampRange({ from: 100_000, to: 100_100 }, { from: 100_000, to: 101_000 })).toEqual({
      from: 100_000,
      to: 101_000,
    });
  });
});

import { describe, expect, it } from 'vitest';
import type { ObservedChartMarker } from '../api/types';
import {
  actionCardPosition,
  actionKey,
  groupMarkerPositions,
  markerLabel,
  trimDecimal,
} from './marketMarkers';

export const buy: ObservedChartMarker = {
  kind: 'OBSERVED',
  observationId: 'buy',
  trader: '1'.repeat(32),
  isTrackedWallet: true,
  side: 'BUY',
  solLamports: '2400000000',
  tokenRaw: '123456789123456789',
  price: '0.0000000775',
  mcap: '15500.000',
  slot: 42,
  time: '2026-10-15T15:23:24Z',
  estimatedTime: '2026-10-15T15:23:24Z',
  signature: null,
  source: 'fixture',
};

describe('market markers', () => {
  it('preserves exact amounts and stable action keys', () => {
    expect(trimDecimal('18446744073.709551615000')).toBe('18446744073.709551615');
    expect(trimDecimal('1000')).toBe('1000');
    expect(markerLabel(buy)).toBe('Decu buy, 2.4 SOL at 15:23:24 UTC');
    expect(actionKey(buy)).toBe('buy');
  });
  it('stacks only Decu centers strictly less than 28 pixels apart', () => {
    const point = (id: string, x: number, y = 100) => ({
      action: { ...buy, observationId: id },
      point: { x, y: y + 26 },
      center: { x, y },
    });
    expect(groupMarkerPositions([point('a', 0), point('b', 27.9)])).toHaveLength(1);
    expect(groupMarkerPositions([point('a', 0), point('b', 28)])).toHaveLength(2);
    expect(groupMarkerPositions([point('a', 0), point('b', 10, 130)])).toHaveLength(2);
    expect(
      groupMarkerPositions([
        point('a', 0),
        { ...point('other', 2), action: { ...buy, isTrackedWallet: false } },
      ]),
    ).toHaveLength(2);
  });
  it('keeps an action card inside the plot, never above its top or over its marker', () => {
    const card = { width: 300, height: 360 };
    const plot = { width: 1300, height: 620 };
    // Upper half: below the marker when it fits, centred on it.
    expect(actionCardPosition({ x: 600, y: 200 }, card, plot)).toEqual({ x: 450, y: 226 });
    // Lower half: above the marker when it fits.
    expect(actionCardPosition({ x: 600, y: 450 }, { width: 300, height: 300 }, plot)).toEqual({
      x: 450,
      y: 124,
    });
    // Too tall for either: beside the marker, on its right near the left edge…
    expect(actionCardPosition({ x: 40, y: 340 }, { width: 300, height: 463 }, plot)).toEqual({
      x: 66,
      y: 108.5,
    });
    // …and on its left near the right edge.
    expect(actionCardPosition({ x: 1250, y: 340 }, { width: 300, height: 463 }, plot)).toEqual({
      x: 924,
      y: 108.5,
    });
    // Taller than the plot and no room beside it: starts at the plot's top.
    expect(
      actionCardPosition(
        { x: 150, y: 500 },
        { width: 300, height: 700 },
        { width: 320, height: 620 },
      ),
    ).toEqual({
      x: 8,
      y: 0,
    });
    // Not yet measured: below the marker.
    expect(
      actionCardPosition({ x: 100, y: 100 }, { width: 0, height: 0 }, { width: 640, height: 0 }),
    ).toEqual({
      x: 100,
      y: 126,
    });
  });
});

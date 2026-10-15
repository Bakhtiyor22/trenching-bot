import { describe, expect, it } from 'vitest';
import type { ObservedChartMarker } from '../api/types';
import { actionKey, groupMarkerPositions, markerLabel, trimDecimal } from './marketMarkers';

export const buy: ObservedChartMarker = { kind: 'OBSERVED', observationId: 'buy', trader: '1'.repeat(32), isTrackedWallet: true, side: 'BUY', solLamports: '2400000000', tokenRaw: '123456789123456789', price: '0.0000000775', mcap: '15500.000', slot: 42, time: '2026-10-15T15:23:24Z', signature: null, source: 'fixture' };

describe('market markers', () => {
  it('preserves exact amounts and stable action keys', () => {
    expect(trimDecimal('18446744073.709551615000')).toBe('18446744073.709551615');
    expect(trimDecimal('1000')).toBe('1000');
    expect(markerLabel(buy)).toBe('Decu buy, 2.4 SOL at 15:23:24 UTC');
    expect(actionKey(buy)).toBe('buy');
  });
  it('stacks only Decu centers strictly less than 28 pixels apart', () => {
    const point = (id: string, x: number, y = 100) => ({ action: { ...buy, observationId: id }, point: { x, y: y + 26 }, center: { x, y } });
    expect(groupMarkerPositions([point('a', 0), point('b', 27.9)])).toHaveLength(1);
    expect(groupMarkerPositions([point('a', 0), point('b', 28)])).toHaveLength(2);
    expect(groupMarkerPositions([point('a', 0), point('b', 10, 130)])).toHaveLength(2);
    expect(groupMarkerPositions([point('a', 0), { ...point('other', 2), action: { ...buy, isTrackedWallet: false } }])).toHaveLength(2);
  });
});

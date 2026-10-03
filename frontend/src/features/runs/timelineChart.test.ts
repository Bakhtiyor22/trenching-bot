import { describe, expect, it } from 'vitest';
import { edgeScenarios, sampleScenarios } from '../../mocks/fixtures';
import { timelineMarkers } from './timelineChart';

describe('timelineMarkers', () => {
  it('marks the source buy, three entries and three exits for the sample run', () => {
    const markers = timelineMarkers(sampleScenarios);
    expect(markers.sourceBuys).toEqual([{ slot: 100, label: 'Source buy' }]);
    expect(markers.entries.map((m) => [m.slot, m.price, m.label])).toEqual([
      [101, '10.05', '+1'],
      [102, '10.05', '+2'],
      [103, '10.05', '+3'],
    ]);
    expect(markers.exits.map((m) => [m.slot, m.price])).toEqual([
      [106, '10.945'],
      [107, '10.945'],
      [108, '10.945'],
    ]);
    expect(markers.rejections).toEqual([]);
  });

  it('marks rejected scenarios at their target slot and has no exit for an open position', () => {
    const markers = timelineMarkers(edgeScenarios);
    expect(markers.entries).toHaveLength(1);
    expect(markers.exits).toHaveLength(0);
    expect(markers.rejections).toEqual([
      { slot: 202, label: '+2 rejected: Stale observation' },
      { slot: 203, label: '+3 rejected: Invalid quality' },
    ]);
  });
});

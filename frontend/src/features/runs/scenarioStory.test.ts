import { describe, expect, it } from 'vitest';
import { edgeScenarios, sampleScenarios } from '../../mocks/fixtures';
import { runLanes, scenarioEvents } from './scenarioStory';

const [takeProfit, stopLoss, noDip] = sampleScenarios;

describe('scenarioEvents', () => {
  it('tells a take-profit cycle from first buy to exit, slot by slot', () => {
    const events = scenarioEvents(takeProfit!, 'SOL');
    expect(events.map((e) => [e.slot, e.title])).toEqual([
      [1000, 'Wallet first buy'],
      [1002, 'Dip reached'],
      [1003, 'Entry filled'],
      [1005, 'Take profit crossed'],
      [1006, 'Exit filled'],
    ]);
    expect(events[2]!.detail).toBe(
      '0.0₇8616345143543821057475551350438 · impact +0.19% · 1.25% fees',
    );
    expect(events[4]!.detail).toBe('0.0₆11474828329182461687631176780630 · P&L 0.028260434000 SOL');
  });

  it('ends a cycle without entry, or a position that never exited, with the reason', () => {
    expect(scenarioEvents(noDip!, 'SOL').at(-1)).toMatchObject({
      title: 'No entry',
      detail: 'NO_DIP',
    });
    expect(scenarioEvents(edgeScenarios[0]!, 'SOL').at(-1)).toMatchObject({
      title: 'Still open',
      detail: 'HORIZON_REACHED',
    });
    expect(scenarioEvents(stopLoss!, 'SOL').map((e) => e.title)).toContain('Stop loss crossed');
  });
});

describe('runLanes', () => {
  it('aligns every cycle on its own first buy', () => {
    const { lanes, span } = runLanes(sampleScenarios);
    expect(lanes.map((l) => [l.outcome.tag, l.dip, l.fill, l.exit])).toEqual([
      ['TP', 2, 3, 6],
      ['SL', 1, 2, 5],
      ['—', null, null, null],
    ]);
    expect(span).toBeGreaterThan(6);
  });
});

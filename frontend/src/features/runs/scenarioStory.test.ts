import { describe, expect, it } from 'vitest';
import {
  edgeScenarios,
  referenceUnavailableScenario,
  sampleScenarios,
  soldBeforeDipScenario,
  walletSoldScenario,
} from '../../mocks/fixtures';
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

describe('scenarioEvents with Decu sells', () => {
  it('ends a cycle Decu sold before the dip at his sell, and a Decu-sold exit at its start', () => {
    expect(scenarioEvents(soldBeforeDipScenario, 'SOL').at(-1)).toMatchObject({
      slot: 3005,
      title: 'Decu sold before the dip',
    });
    expect(scenarioEvents(walletSoldScenario, 'SOL').map((e) => e.title)).toContain(
      'Exit started: Decu sold',
    );
    expect(scenarioEvents(referenceUnavailableScenario, 'SOL')[0]?.detail).toBe(
      'P0 unknown · dip level unknown',
    );
  });
});

describe('runLanes span', () => {
  it('spans a no-entry cycle to the end of its dip window, not just the few slots of a dip', () => {
    // 30 s at 207 ms per slot is about 145 slots, inside the 4,500-slot horizon.
    const noDip = sampleScenarios[2]!;
    const { lanes, span } = runLanes([noDip], { dipWindowMs: 30000 }, 207);
    expect(lanes[0]?.watchEnd).toBe(145);
    expect(span).toBeGreaterThan(145);
    expect(span).toBeLessThan(200);
  });

  it('spans an untimed no-entry cycle to its horizon and a cycle Decu sold to his sell', () => {
    expect(runLanes([sampleScenarios[2]!]).span).toBeGreaterThan(4500);
    expect(runLanes([soldBeforeDipScenario], { dipWindowMs: 30000 }, 207).lanes[0]?.watchEnd).toBe(
      5,
    );
    expect(runLanes([referenceUnavailableScenario]).lanes[0]?.watchEnd).toBe(0);
  });
});

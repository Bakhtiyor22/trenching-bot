import { describe, expect, it } from 'vitest';
import {
  edgeScenarios,
  sampleObservations,
  sampleScenarios,
  STOP_LOSS_EXIT_PRICE,
  STOP_LOSS_LEVEL,
  TAKE_PROFIT_ENTRY_PRICE,
  TAKE_PROFIT_EXIT_PRICE,
  TAKE_PROFIT_LEVEL,
} from '../../mocks/fixtures';
import { LEVEL_COLORS, LEVELS_SERIES, timelineMarkers, timelineOption, usdtValues } from './timelineChart';

describe('timelineMarkers', () => {
  it('marks each first buy, entry fill, exit and the cycle levels for the sample run', () => {
    const markers = timelineMarkers(sampleScenarios);
    expect(markers.sourceBuys).toEqual([
      { slot: 1000, label: 'Source buy' },
      { slot: 2000, label: 'Source buy' },
      { slot: 3000, label: 'Source buy' },
    ]);
    expect(markers.entries.map((m) => [m.slot, m.price, m.label])).toEqual([
      [1003, TAKE_PROFIT_ENTRY_PRICE, 'entry'],
      [2002, '0.00000008015764656323810068866023294541', 'entry'],
    ]);
    expect(markers.exits.map((m) => [m.slot, m.price, m.label])).toEqual([
      [1006, TAKE_PROFIT_EXIT_PRICE, 'TP'],
      [2005, STOP_LOSS_EXIT_PRICE, 'SL'],
    ]);
    expect(markers.rejections).toEqual([{ slot: 3001, label: 'No entry: No dip' }]);
    expect(markers.levels.slice(0, 4)).toEqual([
      { price: '0.0000001', label: 'P0', color: LEVEL_COLORS.reference },
      { price: '0.000000085', label: 'Dip', color: LEVEL_COLORS.dip },
      { price: TAKE_PROFIT_LEVEL, label: 'Take profit', color: LEVEL_COLORS.takeProfit },
      { price: STOP_LOSS_LEVEL, label: 'Stop loss', color: LEVEL_COLORS.stopLoss },
    ]);
  });

  it('marks cycles without an entry at their target slot and has no exit for an open position', () => {
    const markers = timelineMarkers(edgeScenarios);
    expect(markers.entries).toHaveLength(1);
    expect(markers.exits).toHaveLength(0);
    expect(markers.rejections).toEqual([
      { slot: 200, label: 'No entry: Dip below min market cap' },
      { slot: 200, label: 'No entry: Migrated before entry' },
    ]);
  });

  it('draws no strategy levels in USDT, where a level has no single value', () => {
    const view = { runId: 'run', rateSource: null, unavailableReason: null, observations: [], scenarios: [],
      realizedPnlUsdt: null, realizedPnlUnavailableReason: null };
    expect(timelineMarkers(sampleScenarios, usdtValues(view)).levels).toEqual([]);
  });
});

describe('timelineOption', () => {
  it('draws each level as a line across the whole slot range, labelled at its end', () => {
    const [takeProfit] = sampleScenarios;
    const option = timelineOption(sampleObservations.slice(0, 5), [takeProfit!]);
    const series = option.series as Array<{ name: string; data: Array<[number, number]>; endLabel?: { formatter: string } }>;
    const levels = series.filter((s) => s.name === LEVELS_SERIES);
    const axis = option.xAxis as { min: number; max: number };
    expect(levels.map((l) => l.data)).toEqual(
      [0.0000001, 0.000000085, Number(TAKE_PROFIT_LEVEL), Number(STOP_LOSS_LEVEL)].map((price) => [
        [axis.min, price],
        [axis.max, price],
      ]),
    );
    expect(levels.map((l) => l.endLabel?.formatter)).toEqual([
      'P0 0.0₆1',
      'Dip 0.0₇85',
      'Take profit 0.0₆11201248686606967374718216755569',
      'Stop loss 0.0₇7323893372012247898854218647872',
    ]);
  });
});

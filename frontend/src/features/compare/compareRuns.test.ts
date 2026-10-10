import { describe, expect, it } from 'vitest';
import type { RunDetail } from '../../api/types';
import { SAMPLE_TOTAL_PNL, edgeRunDetail, sampleRunDetail } from '../../mocks/fixtures';
import {
  alignByMint,
  differenceSummary,
  inputRows,
  parseCompareIds,
  pnlDeltas,
  sharedRows,
} from './compareRuns';

const withConfig = (detail: RunDetail, takeProfitBps: number, hash: string): RunDetail => ({
  ...detail,
  run: { ...detail.run, configurationHash: hash },
  config: { ...detail.config, takeProfitBps },
});

describe('parseCompareIds', () => {
  it('trims, drops blanks and duplicates, and keeps at most three', () => {
    expect(parseCompareIds(' a, b,,a , c ,d')).toEqual(['a', 'b', 'c']);
    expect(parseCompareIds(null)).toEqual([]);
  });
});

describe('inputRows', () => {
  it('marks only the inputs the runs disagree on', () => {
    const tuned = withConfig(sampleRunDetail, 5000, 'other-hash');
    const rows = inputRows([sampleRunDetail, tuned]);
    expect(rows.filter((r) => r.differs).map((r) => r.label)).toEqual([
      'Configuration hash',
      'Take profit',
    ]);
    expect(rows.find((r) => r.label === 'Take profit')?.values).toEqual([
      '3000 bps (30%)',
      '5000 bps (50%)',
    ]);
  });
});

describe('pnlDeltas', () => {
  it('subtracts exactly within one quote mint and refuses across mints', () => {
    // The edge run realized 0.000000000000, so the gap is the sample's own total, to the last digit.
    expect(pnlDeltas([sampleRunDetail, edgeRunDetail])).toEqual([SAMPLE_TOTAL_PNL]);
    const better: RunDetail = {
      ...edgeRunDetail,
      run: { ...edgeRunDetail.run, realizedPnl: '0.000000000001' },
    };
    expect(pnlDeltas([sampleRunDetail, better])).toEqual(['-0.013487824001']);
    const otherMint: RunDetail = {
      ...edgeRunDetail,
      run: { ...edgeRunDetail.run, quoteMint: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v' },
    };
    expect(pnlDeltas([sampleRunDetail, otherMint])).toEqual([null]);
  });
});

describe('differenceSummary', () => {
  it('attributes the gap to the data, the settings or both', () => {
    expect(differenceSummary([sampleRunDetail, edgeRunDetail])).toMatch(
      /^Same configuration, different datasets/,
    );
    expect(differenceSummary([sampleRunDetail, withConfig(sampleRunDetail, 5000, 'h')])).toMatch(
      /^Same dataset, different configurations/,
    );
    expect(differenceSummary([sampleRunDetail, withConfig(edgeRunDetail, 5000, 'h')])).toMatch(
      /^Different datasets and different configurations/,
    );
  });
});

describe('alignByMint', () => {
  it('lines up one cycle per token per run and finds the shared ones', () => {
    const tuned = withConfig(sampleRunDetail, 5000, 'other-hash');
    const rows = alignByMint([sampleRunDetail, tuned]);
    expect(rows).toHaveLength(sampleRunDetail.scenarios.length);
    expect(
      rows.every((r) => r.cells[0]?.pair.base === r.mint && r.cells[1]?.pair.base === r.mint),
    ).toBe(true);
    expect(sharedRows(rows)).toHaveLength(rows.length);
  });

  it('leaves a gap where a run never saw the token', () => {
    const [first, ...rest] = edgeRunDetail.scenarios;
    const unseen: RunDetail = {
      ...edgeRunDetail,
      scenarios: [{ ...first!, pair: { ...first!.pair, base: 'OtherMint111' } }, ...rest],
    };
    const rows = alignByMint([sampleRunDetail, unseen]);
    expect(rows.find((r) => r.mint === 'OtherMint111')?.cells[0]).toBeNull();
    expect(rows.find((r) => r.mint === first!.pair.base)?.cells[1]).toBeNull();
    expect(sharedRows(rows).length).toBe(rows.length - 2);
  });
});

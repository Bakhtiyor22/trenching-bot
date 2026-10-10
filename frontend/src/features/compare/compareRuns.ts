import type { RunDetail, Scenario } from '../../api/types';
import { addDecimals, negateDecimal } from '../../lib/decimal';
import { MAX_COMPARED_RUNS } from '../../paths';
import { RUN_INPUTS } from '../runs/configLabels';

/** Run ids from the `runs` query parameter: trimmed, de-duplicated, at most {@link MAX_COMPARED_RUNS}. */
export function parseCompareIds(param: string | null): string[] {
  const ids = (param ?? '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
  return [...new Set(ids)].slice(0, MAX_COMPARED_RUNS);
}

export interface InputRow {
  label: string;
  values: string[];
  differs: boolean;
}

/** Every run input side by side, each row marked when the runs disagree on it. */
export function inputRows(details: RunDetail[]): InputRow[] {
  return RUN_INPUTS.map(({ label, value }) => {
    const values = details.map(value);
    return { label, values, differs: new Set(values).size > 1 };
  });
}

/**
 * Exact P&L of the first run minus each other run's, or null for a run in another quote mint, since
 * amounts in different mints are never subtracted.
 */
export function pnlDeltas(details: RunDetail[]): Array<string | null> {
  const [first, ...others] = details;
  if (!first) return [];
  return others.map((other) =>
    other.run.quoteMint === first.run.quoteMint
      ? addDecimals(first.run.realizedPnl, negateDecimal(other.run.realizedPnl))
      : null,
  );
}

/** What separates the runs, so a P&L gap can be attributed to the data, the settings, or both. */
export function differenceSummary(details: RunDetail[]): string {
  if (details.length < 2) return '';
  const distinct = (pick: (d: RunDetail) => string) => new Set(details.map(pick)).size > 1;
  const datasets = distinct((d) => d.run.datasetId);
  const configs = distinct((d) => d.run.configurationHash);
  const engines = distinct((d) => d.run.engineVersion);
  if (datasets && configs)
    return 'Different datasets and different configurations: the gap mixes both.';
  if (datasets)
    return 'Same configuration, different datasets: the gap comes from the data, not the strategy.';
  if (configs) return 'Same dataset, different configurations: the gap comes from the settings.';
  if (engines)
    return 'Same dataset and configuration on different engine versions: the gap comes from the engine.';
  return 'Same dataset, configuration and engine.';
}

export interface AlignedRow {
  /** The token mint (the pair's base). */
  mint: string;
  /** One cell per run: that run's cycle for the token, or null when the run never saw it. */
  cells: Array<Scenario | null>;
}

/** Cycles lined up by token, in order of first appearance across the runs. One cycle per token per run. */
export function alignByMint(details: RunDetail[]): AlignedRow[] {
  const rows = new Map<string, AlignedRow>();
  details.forEach((detail, index) => {
    for (const scenario of [...detail.scenarios].sort((a, b) => a.sourceSlot - b.sourceSlot)) {
      const mint = scenario.pair.base;
      const row = rows.get(mint) ?? { mint, cells: details.map(() => null) };
      row.cells[index] = scenario;
      rows.set(mint, row);
    }
  });
  return [...rows.values()];
}

/** Rows whose token every compared run traded. */
export function sharedRows(rows: AlignedRow[]): AlignedRow[] {
  return rows.filter((row) => row.cells.every((cell) => cell !== null));
}

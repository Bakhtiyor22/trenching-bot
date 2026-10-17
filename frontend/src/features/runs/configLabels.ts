import type { RunDetail } from '../../api/types';
import { formatDecimal } from '../../lib/decimal';
import { formatSlot, mintLabel } from '../../lib/format';
import { levelPercent } from '../../lib/percent';

/** A server percent string, with an exact fallback for older recordings. */
export function bpsLabel(bps: number, percent?: string): string {
  return levelPercent(percent, bps);
}

/** Slots at Solana's nominal 400 ms, rounded to whole minutes. */
export function horizonMinutes(slots: number): number {
  return Math.round((slots * 0.4) / 60);
}

/** The fill latency, e.g. "1 slot after each trigger". */
export function latencyLabel(slots: number): string {
  return `${formatSlot(slots)} ${slots === 1 ? 'slot' : 'slots'} after each trigger`;
}

/** The fixed cost per transaction, exact, e.g. "0.001000000000 SOL per transaction". */
export function transactionCostLabel(sol: string): string {
  return `${formatDecimal(sol)} SOL per transaction`;
}

/** Everything that decides a run's result, as display strings, in the order the run detail lists it. */
export const RUN_INPUTS: Array<{ label: string; value: (detail: RunDetail) => string }> = [
  { label: 'Dataset', value: (d) => d.run.datasetId },
  { label: 'Configuration hash', value: (d) => d.run.configurationHash },
  { label: 'Engine version', value: (d) => d.run.engineVersion },
  { label: 'Strategy', value: (d) => `${d.config.strategyName} v${d.config.strategyVersion}` },
  { label: 'Notional', value: (d) => formatDecimal(d.config.notional) },
  { label: 'Dip below first buy', value: (d) => bpsLabel(d.config.dipBps, d.config.dipPercent) },
  { label: 'Minimum market cap', value: (d) => `$${formatDecimal(d.config.minMarketCapUsd)}` },
  {
    label: 'Take profit',
    value: (d) => bpsLabel(d.config.takeProfitBps, d.config.takeProfitPercent),
  },
  { label: 'Stop loss', value: (d) => bpsLabel(d.config.stopLossBps, d.config.stopLossPercent) },
  { label: 'Horizon', value: (d) => `${formatSlot(d.config.horizonSlots)} slots` },
  { label: 'Latency', value: (d) => latencyLabel(d.config.latencySlots) },
  { label: 'Transaction cost', value: (d) => transactionCostLabel(d.config.transactionCostSol) },
  { label: 'Max observation age', value: (d) => `${formatSlot(d.config.maxAgeSlots)} slots` },
  { label: 'Quote mint', value: (d) => mintLabel(d.config.quoteMint) },
];

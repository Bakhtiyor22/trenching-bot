import type { Config, RunDetail } from '../../api/types';
import {
  compareDecimals,
  formatDecimal,
  isDecimalString,
  trimFractionZeros,
} from '../../lib/decimal';
import { formatSlot, mintLabel } from '../../lib/format';
import { formatPercent, levelPercent } from '../../lib/percent';
import { FIELD_LABELS, formValuesFrom, type RunFormValues } from './runForm';

export type SettingField = Exclude<keyof RunFormValues, 'datasetId'>;
export const SETTING_LABELS: Record<SettingField, string> = {
  ...FIELD_LABELS,
  notional: 'Position size',
  minMarketCapUsd: 'Minimum market cap',
  transactionCostSol: 'Cost per transaction',
  quoteMint: 'Quote currency',
};

export function formatSettingValue(field: SettingField, value: string | number): string {
  const text = trimFractionZeros(String(value));
  if (field.endsWith('Percent')) return formatPercent(text);
  if (field === 'notional' || field === 'transactionCostSol') return `${formatDecimal(text)} SOL`;
  if (field === 'minMarketCapUsd') return `$${formatDecimal(text)}`;
  if (field === 'quoteMint') return mintLabel(text);
  return `${formatDecimal(text)} slots`;
}

export function settingEquals(current: string | number, agreed: string | number): boolean {
  return isDecimalString(String(current)) && isDecimalString(String(agreed))
    ? compareDecimals(String(current), String(agreed)) === 0
    : current === agreed;
}

export function changedSettings(values: RunFormValues, config: Config): SettingField[] {
  const agreed = formValuesFrom(config, values.datasetId);
  return (Object.keys(SETTING_LABELS) as SettingField[]).filter(
    (field) => !settingEquals(values[field], agreed[field]),
  );
}

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

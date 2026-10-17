import { levelPercent } from '../../lib/percent';
import type { Scenario } from '../../api/types';
import { compactDecimal, formatDecimal } from '../../lib/decimal';
import { humanizeCode, shortId, signedBps } from '../../lib/format';
import { terminal } from '../../theme';
import { scenarioOutcome, type ScenarioOutcome } from './scenarioOutcome';

export interface StoryEvent {
  slot: number;
  title: string;
  /** One line of exact detail; tiny prices use the compact subscript form. */
  detail: string;
  color: string;
}

/** A cycle as the slot-by-slot events that make it up, from the wallet's first buy to the exit or the reason there was none. */
export function scenarioEvents(s: Scenario, unit: string): StoryEvent[] {
  const events: StoryEvent[] = [
    {
      slot: s.sourceSlot,
      title: 'Wallet first buy',
      detail: `P0 ${compactDecimal(s.referencePrice)} · dip level ${compactDecimal(s.dipPrice)}`,
      color: terminal.text,
    },
  ];
  if (s.trigger) {
    events.push({
      slot: s.trigger.slot,
      title: 'Dip reached',
      detail: `${compactDecimal(s.trigger.price)} · market cap $${formatDecimal(s.trigger.marketCapUsd)}`,
      color: terminal.info,
    });
  }
  if (s.fill) {
    events.push({
      slot: s.fill.actualSlot,
      title: 'Entry filled',
      detail: `${compactDecimal(s.fill.executionPrice)} · impact ${signedBps(s.fill.priceImpactBps, s.fill.priceImpactPercent)} · ${levelPercent(s.fill.feePercent, s.fill.feeBps)} fees`,
      color: terminal.accent,
    });
  } else {
    events.push({
      slot: s.horizonEndSlot,
      title: 'No entry',
      detail: s.rejectionReason ?? s.watch,
      color: terminal.dimmed,
    });
    return events;
  }
  const position = s.position;
  if (position?.exitTrigger) {
    const tp = position.exitTrigger.reason === 'TAKE_PROFIT';
    events.push({
      slot: position.exitTrigger.slot,
      title: `${humanizeCode(position.exitTrigger.reason)} crossed`,
      detail: tp
        ? `take profit at ${compactDecimal(position.takeProfitPrice)}`
        : `stop loss at ${compactDecimal(position.stopLossPrice)}`,
      color: tp ? terminal.gain : terminal.loss,
    });
  }
  const close = position?.close;
  if (close) {
    const outcome = scenarioOutcome(s);
    events.push({
      slot: close.slot,
      title: 'Exit filled',
      detail: `${compactDecimal(close.executionPrice)} · P&L ${formatDecimal(close.realizedPnl)} ${unit}`,
      color: outcome.hex,
    });
  } else if (position) {
    events.push({
      slot: s.horizonEndSlot,
      title: 'Still open',
      detail: s.exitUnavailableReason ?? 'no exit',
      color: terminal.violet,
    });
  }
  return events;
}

export interface Lane {
  intentId: string;
  token: string;
  outcome: ScenarioOutcome;
  /** Offsets in slots after the first buy; null when the cycle never got there. */
  dip: number | null;
  fill: number | null;
  exit: number | null;
}

/** Every cycle aligned on its own first buy, so time-to-dip and holding time compare at a glance. */
export function runLanes(scenarios: Scenario[]): { lanes: Lane[]; span: number } {
  const lanes = scenarios.map((s): Lane => {
    const offset = (slot: number | undefined | null) => (slot == null ? null : slot - s.sourceSlot);
    return {
      intentId: s.intentId,
      token: shortId(s.pair.base),
      outcome: scenarioOutcome(s),
      dip: offset(s.trigger?.slot),
      fill: offset(s.fill?.actualSlot),
      exit: offset(s.position?.close?.slot),
    };
  });
  const reached = lanes
    .flatMap((l) => [l.dip, l.fill, l.exit])
    .filter((v): v is number => v != null);
  // Pad past the last event so a marker never sits on the edge; at least a few slots wide.
  const span = Math.max(4, Math.ceil(Math.max(0, ...reached) * 1.15) + 1);
  return { lanes, span };
}

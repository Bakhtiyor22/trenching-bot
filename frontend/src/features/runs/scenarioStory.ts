import { levelPercent } from '../../lib/percent';
import type { Scenario } from '../../api/types';
import { codeLabel } from '../../lib/codes';
import { compactDecimal, formatDecimal, formatSignedDecimal } from '../../lib/decimal';
import { humanizeCode, shortId, signedBps } from '../../lib/format';
import { scenarioOutcome, type ScenarioOutcome } from './scenarioOutcome';
import { watchEndOffset, type WatchConfig } from './watchEnd';

export interface StoryEvent {
  slot: number;
  title: string;
  /** One line of exact detail; tiny prices use the compact subscript form. */
  detail: string;
  /** CSS colour (a theme variable, so it follows the scheme) for the timeline dot. */
  color: string;
}

const STORY_COLORS = {
  text: 'var(--console-text)',
  info: 'var(--console-info)',
  accent: 'var(--console-accent)',
  dimmed: 'var(--console-dimmed)',
  rejected: 'var(--console-rejected)',
  gain: 'var(--console-gain)',
  loss: 'var(--console-loss)',
  neutral: 'var(--console-neutral)',
} as const;

/** A reason code in words; the code itself stays in the scenario card. */
function reasonWords(code: string): string {
  return codeLabel(code);
}

/** A cycle as the slot-by-slot events that make it up, from the wallet's first buy to the exit or the reason there was none. */
export function scenarioEvents(s: Scenario, unit: string): StoryEvent[] {
  const events: StoryEvent[] = [
    {
      slot: s.sourceSlot,
      title: 'Wallet first buy',
      // REFERENCE_UNAVAILABLE carries Decu's average as a placeholder, never the reference.
      detail:
        s.watch === 'REFERENCE_UNAVAILABLE'
          ? 'P0 unknown · dip level unknown'
          : `P0 ${compactDecimal(s.referencePrice)} · dip level ${compactDecimal(s.dipPrice)}`,
      color: STORY_COLORS.text,
    },
  ];
  if (s.trigger) {
    events.push({
      slot: s.trigger.slot,
      title: 'Dip reached',
      detail: `${compactDecimal(s.trigger.price)} · market cap $${formatDecimal(s.trigger.marketCapUsd)}`,
      color: STORY_COLORS.info,
    });
  }
  if (s.fill) {
    events.push({
      slot: s.fill.actualSlot,
      title: 'Entry filled',
      detail: `${compactDecimal(s.fill.executionPrice)} · impact ${signedBps(s.fill.priceImpactBps, s.fill.priceImpactPercent)} · ${levelPercent(s.fill.feePercent, s.fill.feeBps)} fees`,
      color: STORY_COLORS.accent,
    });
  } else if (s.walletSell) {
    events.push({
      slot: s.walletSell.slot,
      title: 'Decu sold before the dip',
      detail: reasonWords(s.rejectionReason ?? s.watch),
      color: STORY_COLORS.dimmed,
    });
    return events;
  } else {
    events.push({
      slot: s.horizonEndSlot,
      title: 'No entry',
      detail: reasonWords(s.rejectionReason ?? s.watch),
      color: STORY_COLORS.dimmed,
    });
    return events;
  }
  const position = s.position;
  if (position?.exitTrigger?.reason === 'WALLET_SOLD') {
    // Its slot is our fill's when Decu sold before our buy landed.
    events.push({
      slot: position.exitTrigger.slot,
      title: 'Exit started: Decu sold',
      detail: 'sell the whole position',
      color: STORY_COLORS.rejected,
    });
  } else if (position?.exitTrigger) {
    const tp = position.exitTrigger.reason === 'TAKE_PROFIT';
    events.push({
      slot: position.exitTrigger.slot,
      title: `${humanizeCode(position.exitTrigger.reason)} crossed`,
      detail: tp
        ? `take profit at ${compactDecimal(position.takeProfitPrice)}`
        : `stop loss at ${compactDecimal(position.stopLossPrice)}`,
      color: tp ? STORY_COLORS.gain : STORY_COLORS.loss,
    });
  }
  const close = position?.close;
  if (close) {
    const outcome = scenarioOutcome(s);
    events.push({
      slot: close.slot,
      title: 'Exit filled',
      detail: `${compactDecimal(close.executionPrice)} · P&L ${formatSignedDecimal(close.realizedPnl)} ${unit}`,
      color: outcome.hex,
    });
  } else if (position) {
    events.push({
      slot: s.horizonEndSlot,
      title: 'Still open',
      detail: s.exitUnavailableReason ? reasonWords(s.exitUnavailableReason) : 'no exit',
      color: STORY_COLORS.neutral,
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
  /** Where the dip watch ended: the dip, Decu's sell, or the dip window or horizon; 0 when nothing was watched. */
  watchEnd: number;
}

/**
 * Every cycle aligned on its own first buy, so time-to-dip and holding time compare at a glance. The span covers every
 * watch to its end, so a cycle without an entry shows how long it was watched.
 */
export function runLanes(
  scenarios: Scenario[],
  config?: WatchConfig,
  millisPerSlot?: number | null,
): { lanes: Lane[]; span: number } {
  const lanes = scenarios.map((s): Lane => {
    const offset = (slot: number | undefined | null) => (slot == null ? null : slot - s.sourceSlot);
    return {
      intentId: s.intentId,
      token: shortId(s.pair.base),
      outcome: scenarioOutcome(s),
      dip: offset(s.trigger?.slot),
      fill: offset(s.fill?.actualSlot),
      exit: offset(s.position?.close?.slot),
      watchEnd: watchEndOffset(s, config, millisPerSlot),
    };
  });
  const reached = lanes
    .flatMap((l) => [l.dip, l.fill, l.exit, l.watchEnd])
    .filter((v): v is number => v != null);
  // Pad past the last event so a marker never sits on the edge; at least a few slots wide.
  const span = Math.max(4, Math.ceil(Math.max(0, ...reached) * 1.15) + 1);
  return { lanes, span };
}

import type { Scenario } from '../../api/types';
import { humanizeCode } from '../../lib/format';
import { terminal } from '../../theme';

export interface ScenarioOutcome {
  /** Short tag for dense lists: TP, SL, OPEN, or a dash for no entry. */
  tag: string;
  /** Full label, e.g. "Take profit" or "No entry". */
  label: string;
  /** Mantine color for badges. */
  color: 'teal' | 'red' | 'yellow' | 'gray' | 'orange';
  /** Raw color for bars and dots. */
  hex: string;
  /** Realized P&L for a closed position, else null. */
  pnl: string | null;
}

/** A cycle's end state: closed by take profit or stop loss (or another reason), still open, or never entered. */
export function scenarioOutcome(scenario: Scenario): ScenarioOutcome {
  const close = scenario.position?.close;
  if (close) {
    if (close.reason === 'TAKE_PROFIT')
      return {
        tag: 'TP',
        label: 'Take profit',
        color: 'teal',
        hex: terminal.gain,
        pnl: close.realizedPnl,
      };
    if (close.reason === 'STOP_LOSS')
      return {
        tag: 'SL',
        label: 'Stop loss',
        color: 'red',
        hex: terminal.loss,
        pnl: close.realizedPnl,
      };
    return {
      tag: 'EXIT',
      label: humanizeCode(close.reason),
      color: 'orange',
      hex: terminal.rejected,
      pnl: close.realizedPnl,
    };
  }
  if (scenario.position)
    return { tag: 'OPEN', label: 'Open', color: 'yellow', hex: terminal.violet, pnl: null };
  return { tag: '—', label: 'No entry', color: 'gray', hex: terminal.dimmed, pnl: null };
}

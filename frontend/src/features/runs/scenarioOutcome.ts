import type { Scenario } from '../../api/types';
import type { StatusTone } from '../../components/StatusChip';
import { humanizeCode } from '../../lib/format';

export interface ScenarioOutcome {
  /** Short tag for dense lists: TP, SL, TIME, SOLD, OPEN, or a dash for no entry. */
  tag: string;
  /** Full label, e.g. "Take profit" or "No entry". */
  label: string;
  /** Status chip tone; the label always goes with it. */
  tone: StatusTone;
  /** Status chip glyph, so the outcome never relies on colour alone. */
  glyph?: string;
  /** CSS colour (a theme variable, so it follows the scheme) for bars and dots. */
  hex: string;
  /** Realized P&L for a closed position, else null. */
  pnl: string | null;
}

const GAIN = 'var(--console-gain)';
const LOSS = 'var(--console-loss)';
const ATTENTION = 'var(--console-rejected)';

/** A cycle's end state: closed by take profit, stop loss or time exit (or another reason), still open, or never entered. */
export function scenarioOutcome(scenario: Scenario): ScenarioOutcome {
  const close = scenario.position?.close;
  if (close) {
    const closed = { pnl: close.realizedPnl };
    if (close.reason === 'TAKE_PROFIT')
      return { ...closed, tag: 'TP', label: 'Take profit', tone: 'success', glyph: '✓', hex: GAIN };
    if (close.reason === 'STOP_LOSS')
      return { ...closed, tag: 'SL', label: 'Stop loss', tone: 'failure', glyph: '✕', hex: LOSS };
    if (close.reason === 'TIME_EXIT')
      return { ...closed, tag: 'TIME', label: 'Time exit', tone: 'attention', glyph: '▲', hex: ATTENTION };
    if (close.reason === 'WALLET_SOLD')
      return { ...closed, tag: 'SOLD', label: 'Sold because Decu sold', tone: 'attention', glyph: '▲', hex: ATTENTION };
    return { ...closed, tag: 'EXIT', label: humanizeCode(close.reason), tone: 'attention', glyph: '▲', hex: ATTENTION };
  }
  if (scenario.position)
    return { tag: 'OPEN', label: 'Open', tone: 'neutral', glyph: '◷', hex: 'var(--console-neutral)', pnl: null };
  return { tag: '—', label: 'No entry', tone: 'neutral', glyph: '–', hex: 'var(--console-dimmed)', pnl: null };
}
